package com.crabcode.ide.ai.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Streaming chat clients for the provider families supported by desktop CrabCode.
 * All parsing is line-based SSE — verifiable with MockWebServer in unit tests.
 */
class ChatClients(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun stream(
        provider: ProviderConfig,
        apiKey: String?,
        model: String,
        messages: List<AgentMessage>,
        tools: List<ToolSchema>,
        maxTokens: Int = 8192
    ): Flow<StreamEvent> = when (provider.api) {
        "anthropic" -> anthropic(provider, apiKey, model, messages, tools, maxTokens)
        "gemini" -> gemini(provider, apiKey, model, messages, tools)
        else -> openaiCompatible(provider, apiKey, model, messages, tools)
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------ OpenAI family

    private fun openaiCompatible(
        provider: ProviderConfig, apiKey: String?, model: String,
        messages: List<AgentMessage>, tools: List<ToolSchema>
    ): Flow<StreamEvent> = sseFlow(
        url = "${provider.baseUrl.trimEnd('/')}/chat/completions",
        headers = {
            it.header("Authorization", "Bearer ${apiKey ?: ""}")
            if (provider.catalogId == "openrouter") it.header("HTTP-Referer", "https://github.com/crabcode")
        },
        body = buildOpenAiBody(model, messages, tools)
    ) { event -> parseOpenAiEvent(event) }

    private fun buildOpenAiBody(model: String, messages: List<AgentMessage>, tools: List<ToolSchema>): String {
        val msgs = buildJsonArray {
            messages.forEach { m ->
                when (m.role) {
                    "tool" -> m.toolResults.forEach { tr ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tr.toolCallId)
                            put("content", tr.content)
                        })
                    }
                    "assistant" -> add(buildJsonObject {
                        put("role", "assistant")
                        put("content", m.content)
                        if (m.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                            m.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("id", tc.id)
                                        put("name", tc.name)
                                        put("arguments", tc.argumentsJson)
                                    }
                                })
                            }
                        })
                    })
                    else -> add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            }
        }
        val body = buildJsonObject {
            put("model", model)
            put("messages", msgs)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", json.parseToJsonElement(t.parametersJson))
                        }
                    })
                }
            })
        }
        return body.toString()
    }

    private fun parseOpenAiEvent(data: String): List<StreamEvent> {
        if (data == "[DONE]") return listOf(StreamEvent.Done)
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<StreamEvent>()
        val choices = obj["choices"]?.jsonArray ?: emptyList()
        if (choices.isNotEmpty()) {
            val choice = choices[0].jsonObject
            val delta = choice["delta"]?.jsonObject
            if (delta != null) {
                val reasoning = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                    ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull
                if (reasoning != null) out += StreamEvent.ReasoningDelta(reasoning)
                val content = delta["content"]?.jsonPrimitive?.contentOrNull
                if (content != null) out += StreamEvent.TextDelta(content)
                delta["tool_calls"]?.jsonArray?.forEach { tcEl ->
                    val tc = tcEl.jsonObject
                    val idx = tc["index"]?.jsonPrimitive?.int ?: 0
                    val fn = tc["function"]?.jsonObject
                    val id = tc["id"]?.jsonPrimitive?.contentOrNull
                    val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
                    if (id != null && name != null) out += StreamEvent.ToolCallStart(idx, id, name)
                    fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotEmpty()) out += StreamEvent.ToolCallDelta(idx, it)
                    }
                }
            }
            val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (finish == "tool_calls" || finish == "stop") {
                // tool_call ends are inferred below on Done
            }
        }
        obj["usage"]?.jsonObject?.let { u ->
            out += StreamEvent.Usage(
                u["prompt_tokens"]?.jsonPrimitive?.int,
                u["completion_tokens"]?.jsonPrimitive?.int
            )
        }
        return out
    }

    // ------------------------------------------------------------ Anthropic

    private fun anthropic(
        provider: ProviderConfig, apiKey: String?, model: String,
        messages: List<AgentMessage>, tools: List<ToolSchema>, maxTokens: Int
    ): Flow<StreamEvent> {
        val openToolCalls = mutableMapOf<Int, String>()
        return sseFlow(
            url = "${provider.baseUrl.trimEnd('/')}/v1/messages",
            headers = {
                it.header("x-api-key", apiKey ?: "")
                it.header("anthropic-version", "2023-06-01")
            },
            body = buildAnthropicBody(model, messages, tools, maxTokens)
        ) { event -> parseAnthropicEvent(event, openToolCalls) }
    }

    private fun buildAnthropicBody(model: String, messages: List<AgentMessage>, tools: List<ToolSchema>, maxTokens: Int): String {
        val system = messages.firstOrNull { it.role == "system" }?.content
        val rest = messages.filter { it.role != "system" }
        val msgs = buildJsonArray {
            rest.forEach { m ->
                if (m.role == "tool") {
                    m.toolResults.forEach { tr ->
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", tr.toolCallId)
                                    put("content", tr.content)
                                    put("is_error", tr.isError)
                                })
                            })
                        })
                    }
                } else {
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", buildJsonArray {
                            if (m.content.isNotEmpty()) add(buildJsonObject {
                                put("type", "text"); put("text", m.content)
                            })
                            m.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("input", runCatching { json.parseToJsonElement(tc.argumentsJson) }
                                        .getOrDefault(buildJsonObject { }))
                                })
                            }
                        })
                    })
                }
            }
        }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", true)
            if (system != null) put("system", system)
            put("messages", msgs)
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", json.parseToJsonElement(t.parametersJson))
                    })
                }
            })
        }
        return body.toString()
    }

    private fun parseAnthropicEvent(data: String, openToolCalls: MutableMap<Int, String>): List<StreamEvent> {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<StreamEvent>()
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "message_start" -> {
                val usage = obj["message"]?.jsonObject?.get("usage")?.jsonObject
                out += StreamEvent.Usage(usage?.get("input_tokens")?.jsonPrimitive?.int, null)
            }
            "content_block_start" -> {
                val idx = obj["index"]?.jsonPrimitive?.int ?: 0
                val block = obj["content_block"]?.jsonObject ?: return emptyList()
                if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    openToolCalls[idx] = id
                    out += StreamEvent.ToolCallStart(idx, id, name)
                }
            }
            "content_block_delta" -> {
                val idx = obj["index"]?.jsonPrimitive?.int ?: 0
                val delta = obj["delta"]?.jsonObject ?: return emptyList()
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull?.let { out += StreamEvent.TextDelta(it) }
                    "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull?.let { out += StreamEvent.ReasoningDelta(it) }
                    "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let {
                        if (it.isNotEmpty()) out += StreamEvent.ToolCallDelta(idx, it)
                    }
                }
            }
            "content_block_stop" -> {
                val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                if (openToolCalls.remove(idx) != null) out += StreamEvent.ToolCallEnd(idx)
            }
            "message_delta" -> {
                val usage = obj["usage"]?.jsonObject
                out += StreamEvent.Usage(null, usage?.get("output_tokens")?.jsonPrimitive?.int)
            }
            "message_stop" -> out += StreamEvent.Done
            "error" -> {
                val err = obj["error"]?.jsonObject
                out += StreamEvent.Failed(err?.get("message")?.jsonPrimitive?.contentOrNull ?: "Anthropic stream error")
            }
        }
        return out
    }

    // ------------------------------------------------------------ Gemini

    private fun gemini(
        provider: ProviderConfig, apiKey: String?, model: String,
        messages: List<AgentMessage>, tools: List<ToolSchema>
    ): Flow<StreamEvent> {
        val seq = IntArray(1)
        return sseFlow(
            url = "${provider.baseUrl.trimEnd('/')}/models/$model:streamGenerateContent?alt=sse",
            headers = { it.header("x-goog-api-key", apiKey ?: "") },
            body = buildGeminiBody(messages, tools)
        ) { event -> parseGeminiEvent(event, seq) }
    }

    private fun buildGeminiBody(messages: List<AgentMessage>, tools: List<ToolSchema>): String {
        val system = messages.firstOrNull { it.role == "system" }?.content
        val rest = messages.filter { it.role != "system" }
        val contents = buildJsonArray {
            rest.forEach { m ->
                if (m.role == "tool") {
                    m.toolResults.forEach { tr ->
                        add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    putJsonObject("functionResponse") {
                                        put("name", tr.name)
                                        put("response", runCatching {
                                            json.parseToJsonElement(tr.content)
                                        }.getOrDefault(buildJsonObject { put("result", tr.content) }))
                                    }
                                })
                            })
                        })
                    }
                } else {
                    add(buildJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        put("parts", buildJsonArray {
                            if (m.content.isNotEmpty()) add(buildJsonObject { put("text", m.content) })
                            m.toolCalls.forEach { tc ->
                                add(buildJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", tc.name)
                                        put("args", runCatching { json.parseToJsonElement(tc.argumentsJson) }
                                            .getOrDefault(buildJsonObject { }))
                                    }
                                })
                            }
                        })
                    })
                }
            }
        }
        val body = buildJsonObject {
            if (system != null) putJsonObject("systemInstruction") {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            }
            put("contents", contents)
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        tools.forEach { t ->
                            add(buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", json.parseToJsonElement(t.parametersJson))
                            })
                        }
                    })
                })
            })
        }
        return body.toString()
    }

    private fun parseGeminiEvent(data: String, seq: IntArray): List<StreamEvent> {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<StreamEvent>()
        val candidates = obj["candidates"]?.jsonArray ?: emptyList()
        if (candidates.isNotEmpty()) {
            val cand = candidates[0].jsonObject
            cand["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { partEl ->
                val part = partEl.jsonObject
                part["text"]?.jsonPrimitive?.contentOrNull?.let { out += StreamEvent.TextDelta(it) }
                part["functionCall"]?.jsonObject?.let { fc ->
                    val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val args = fc["args"]?.jsonObject ?: buildJsonObject { }
                    seq[0]++
                    out += StreamEvent.ToolCallStart(0, "fc_${seq[0]}", name)
                    out += StreamEvent.ToolCallDelta(0, args.toString())
                    out += StreamEvent.ToolCallEnd(0)
                }
            }
        }
        obj["usageMetadata"]?.jsonObject?.let { u ->
            out += StreamEvent.Usage(
                u["promptTokenCount"]?.jsonPrimitive?.int,
                u["candidatesTokenCount"]?.jsonPrimitive?.int
            )
        }
        return out
    }

    // ------------------------------------------------------------ SSE core

    private fun sseFlow(
        url: String,
        headers: (okhttp3.Request.Builder) -> Unit,
        body: String,
        onEvent: (String) -> List<StreamEvent>
    ): Flow<StreamEvent> = flow {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { headers(this) }
            .build()
        val call = http.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(400) ?: ""
                    val quota = response.code == 429 || errBody.contains("quota", ignoreCase = true) ||
                        errBody.contains("insufficient", ignoreCase = true) ||
                        errBody.contains("billing", ignoreCase = true)
                    emit(StreamEvent.Failed("HTTP ${response.code}: $errBody", response.code, quota))
                    return@use
                }
                val source = response.body!!.source()
                val sb = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8LineStrict() ?: break
                    when {
                        line.startsWith("data:") -> sb.append(line.removePrefix("data:").trim()).append('\n')
                        line.isEmpty() && sb.isNotEmpty() -> {
                            val payload = sb.toString().trim()
                            sb.clear()
                            if (payload.isNotEmpty()) onEvent(payload).forEach { emit(it) }
                        }
                        else -> Unit // "event:" names, comments (":…") and other fields are ignored
                    }
                }
                if (sb.isNotEmpty()) {
                    val payload = sb.toString().trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") onEvent(payload).forEach { emit(it) }
                }
                emit(StreamEvent.Done)
            }
        } catch (e: java.io.IOException) {
            emit(StreamEvent.Failed("Сеть недоступна: ${e.message}"))
        }
    }
}
