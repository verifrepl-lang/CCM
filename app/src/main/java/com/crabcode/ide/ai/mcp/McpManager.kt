package com.crabcode.ide.ai.mcp

import android.content.Context
import com.crabcode.ide.linux.ProotPaths
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient

/**
 * MCP runtime — a Kotlin port of the desktop mcpRuntime.ts:
 * JSON-RPC 2.0, stdio sessions spawned inside the PRoot environment and
 * Streamable HTTP sessions, 30 s request timeout, isolated server failures.
 */
class McpManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val configDir: File,
    private val userHome: File
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sessions = mutableMapOf<String, Session>()
    private val nextId = AtomicLong(1)

    private val _states = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val states: StateFlow<Map<String, McpServerState>> get() = _states

    private val configFile: File get() = File(configDir, "mcp.json")

    fun loadConfig(): McpConfig {
        if (!configFile.exists()) return McpConfig()
        return runCatching { json.decodeFromString<McpConfig>(configFile.readText()) }
            .getOrDefault(McpConfig())
    }

    fun saveConfig(cfg: McpConfig) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(json.encodeToString(McpConfig.serializer(), cfg))
    }

    fun toolRefs(): List<ToolRef> = _states.value.values
        .filter { it.status == McpStatus.CONNECTED }
        .flatMap { st -> st.tools.map { ToolRef(st.config.name, it.name, "[${st.config.name}] ${it.description}") } }

    data class ToolRef(val server: String, val tool: String, val description: String)

    suspend fun connectAll() {
        val cfg = loadConfig()
        cfg.servers.filter { it.enabled }.forEach { server ->
            runCatching { connect(server) }
        }
    }

    suspend fun disconnectAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        _states.value = loadConfig().servers.associate { it.name to McpServerState(it) }
    }

    suspend fun connect(server: McpServerConfig): McpServerState = withContext(Dispatchers.IO) {
        disconnect(server.name)
        updateState(server.name) { it.copy(status = McpStatus.CONNECTING, lastError = null) }
        try {
            val session: Session = when (server.transport) {
                "stdio" -> StdioSession(context, http, server, userHome, ::newId)
                "http", "sse" -> HttpSession(http, server, ::newId)
                else -> error("Неизвестный транспорт: ${server.transport}")
            }
            sessions[server.name] = session

            // MCP handshake
            val initResult = session.request(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", "2025-03-26")
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("clientInfo") {
                        put("name", "CrabCode-Mobile"); put("version", "1.0.6")
                    }
                }
            ) ?: error("initialize timeout")
            val protocol = initResult["protocolVersion"]?.jsonPrimitive?.content

            session.notify("notifications/initialized")

            val toolsResult = session.request("tools/list", buildJsonObject { })
            val tools = toolsResult?.get("tools")?.jsonArray?.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                McpToolInfo(
                    name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    server = server.name
                )
            } ?: emptyList()

            val state = McpServerState(server, McpStatus.CONNECTED, tools, null, protocol)
            updateState(server.name) { state }
            state
        } catch (e: Exception) {
            val state = McpServerState(server, McpStatus.ERROR, emptyList(), e.message)
            updateState(server.name) { state }
            state
        }
    }

    suspend fun disconnect(name: String) {
        sessions.remove(name)?.let { runCatching { it.close() } }
        val cfg = loadConfig()
        cfg.servers.firstOrNull { it.name == name }?.let {
            updateState(name) { McpServerState(it) }
        }
    }

    suspend fun callTool(server: String, tool: String, argsJson: String): String {
        val session = sessions[server] ?: run {
            val cfg = loadConfig().servers.firstOrNull { it.name == server }
                ?: return "MCP-сервер не найден: $server"
            val state = connect(cfg)
            if (state.status != McpStatus.CONNECTED) return "MCP-сервер $server недоступен: ${state.lastError}"
            sessions[server]!!
        }
        val args = runCatching { json.parseToJsonElement(argsJson) }.getOrNull()
            ?: buildJsonObject { }
        val result = withTimeoutOrNull(REQUEST_TIMEOUT) {
            session.request(
                "tools/call",
                buildJsonObject {
                    put("name", tool)
                    put("arguments", args)
                }
            )
        } ?: return "Таймаут вызова $server.$tool"
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull == "true"
        val content = result["content"]?.jsonArray
        val text = content?.joinToString("\n") { el ->
            (el as? JsonObject)?.get("text")?.jsonPrimitive?.content ?: el.toString()
        } ?: result.toString()
        return if (isError) "Ошибка MCP: $text" else text
    }

    private fun newId(): Long = nextId.getAndIncrement()

    private fun updateState(name: String, transform: (McpServerState) -> McpServerState) {
        _states.value = _states.value.toMutableMap().apply {
            val current = get(name) ?: McpServerState(loadConfig().servers.firstOrNull { it.name == name } ?: return)
            put(name, transform(current))
        }
    }

    // ---------------------------------------------------------------- transport

    interface Session {
        suspend fun request(method: String, params: JsonObject): JsonObject?
        suspend fun notify(method: String)
        fun close()
    }

    /** stdio MCP server spawned inside the PRoot Debian environment. */
    class StdioSession(
        private val context: Context,
        http: OkHttpClient,
        private val server: McpServerConfig,
        private val userHome: File,
        private val newId: () -> Long
    ) : Session {
        private val json = Json { ignoreUnknownKeys = true }
        private var process: Process? = null
        private val pending = mutableMapOf<Long, CompletableDeferred<JsonObject>>()
        private var readerThread: Thread? = null
        private val stderrLog = StringBuilder()

        @Synchronized
        private fun ensureProcess(): Process {
            process?.let { if (it.isAlive) return it }
            val workdir = File(userHome, "mcp")
            workdir.mkdirs()
            val cmd = server.command ?: error("Не задана команда для stdio MCP-сервера ${server.name}")
            val argv = ProotPaths.shellArgv(context, userHome, workdir, cmd)
            val pb = ProcessBuilder(argv).apply {
                environment().clear()
                environment().putAll(ProotPaths.prootEnv(context))
                server.env.forEach { (k, v) -> environment()[k] = v }
                redirectErrorStream(false)
            }
            val proc = pb.start()
            process = proc
            readerThread = Thread({ pump(proc) }, "mcp-${server.name}").apply {
                isDaemon = true; start()
            }
            return proc
        }

        private fun pump(proc: Process) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
                    val id = obj["id"]?.jsonPrimitive?.longOrNull
                    if (id != null) {
                        val result = obj["result"] as? JsonObject ?: buildJsonObject { put("ok", true) }
                        pending.remove(id)?.complete(result)
                    }
                }
            }
            pending.values.forEach { runCatching { it.complete(buildJsonObject { put("error", "session closed") }) } }
            pending.clear()
        }

        override suspend fun request(method: String, params: JsonObject): JsonObject? {
            return withContext(Dispatchers.IO) {
                val proc = ensureProcess()
                val id = newId()
                val deferred = CompletableDeferred<JsonObject>()
                pending[id] = deferred
                val msg = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
                }
                runCatching {
                    proc.outputStream.write((msg.toString() + "\n").toByteArray())
                    proc.outputStream.flush()
                }.onFailure {
                    pending.remove(id); return@withContext null
                }
                withTimeoutOrNull(REQUEST_TIMEOUT) { deferred.await() }
            }
        }

        override suspend fun notify(method: String) {
            withContext(Dispatchers.IO) {
                val proc = ensureProcess()
                val msg = buildJsonObject {
                    put("jsonrpc", "2.0"); put("method", method)
                }
                runCatching {
                    proc.outputStream.write((msg.toString() + "\n").toByteArray())
                    proc.outputStream.flush()
                }
            }
        }

        override fun close() {
            runCatching { process?.destroy() }
        }
    }

    /** Streamable HTTP transport (POST JSON-RPC; SSE or JSON response). */
    class HttpSession(
        private val http: OkHttpClient,
        private val server: McpServerConfig,
        private val newId: () -> Long
    ) : Session {
        private val json = Json { ignoreUnknownKeys = true }
        @Volatile private var sessionId: String? = null

        override suspend fun request(method: String, params: JsonObject): JsonObject? =
            withContext(Dispatchers.IO) {
                val url = server.url ?: error("Не задан URL для HTTP MCP-сервера ${server.name}")
                val id = newId()
                val body = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
                }.toString()
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
                    .header("Accept", "application/json, text/event-stream")
                    .apply { sessionId?.let { header("mcp-session-id", it) } }
                    .apply { server.headers.forEach { (k, v) -> header(k, v) } }
                    .build()
                runCatching {
                    http.newCall(req).execute().use { resp ->
                        resp.header("mcp-session-id")?.let { sessionId = it }
                        if (!resp.isSuccessful) return@use null
                        val contentType = resp.header("Content-Type") ?: "application/json"
                        val text = resp.body?.string() ?: return@use null
                        if (contentType.contains("event-stream")) {
                            parseSse(text)
                        } else {
                            runCatching { json.parseToJsonElement(text).jsonObject["result"] as? JsonObject }
                                .getOrNull()
                        }
                    }
                }.getOrNull()
            }

        private fun parseSse(text: String): JsonObject? {
            val data = text.lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("") { it.removePrefix("data:").trim() }
            if (data.isEmpty()) return null
            return runCatching { json.parseToJsonElement(data).jsonObject["result"] as? JsonObject }.getOrNull()
        }

        override suspend fun notify(method: String) {
            withContext(Dispatchers.IO) {
                val url = server.url ?: return@withContext
                val body = buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }.toString()
                runCatching {
                    http.newCall(
                        okhttp3.Request.Builder()
                            .url(url)
                            .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
                            .header("Accept", "application/json, text/event-stream")
                            .apply { sessionId?.let { header("mcp-session-id", it) } }
                            .apply { server.headers.forEach { (k, v) -> header(k, v) } }
                            .build()
                    ).execute().use { }
                }
            }
        }

        override fun close() { sessionId = null }
    }

    companion object {
        const val REQUEST_TIMEOUT = 30_000L
    }
}
