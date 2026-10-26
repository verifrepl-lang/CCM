package com.crabcode.ide.ai.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatClientsTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun provider(api: String) = ProviderConfig(
        id = "t", catalogId = "test", name = "Test", api = api,
        baseUrl = server.url("/v1").toString().removeSuffix("/v1")
    )

    @Test
    fun `openai streaming parse`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"content":"Привет"}}]}
            
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"pa"}}]}}]}
            
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.py\"}"}}]}}]}
            
            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}
            
            data: [DONE]
            
        """.trimIndent().replace("\n\n", "\n\n")
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val events = ChatClients(http)
            .stream(provider("openai"), "key", "gpt-x", listOf(AgentMessage("user", "hi")), emptyList())
            .toList()
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "Привет" })
        val start = events.filterIsInstance<StreamEvent.ToolCallStart>().first()
        assertEquals("read_file", start.name)
        val args = events.filterIsInstance<StreamEvent.ToolCallDelta>().joinToString("") { it.argsFragment }
        assertEquals("{\"path\":\"a.py\"}", args)
        assertTrue(events.any { it is StreamEvent.Usage })
        assertTrue(events.last() is StreamEvent.Done)
    }

    @Test
    fun `openai request body shape`() = runTest {
        server.enqueue(
            MockResponse().setBody("data: [DONE]\n\n").setHeader("Content-Type", "text/event-stream")
        )
        ChatClients(http).stream(provider("openai"), "k", "m", listOf(AgentMessage("user", "hi")), emptyList()).toList()
        val recorded = server.takeRequest()
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("m", (body["model"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(true, (body["stream"] as kotlinx.serialization.json.JsonPrimitive).content == "true")
        assertEquals("Bearer k", recorded.getHeader("Authorization"))
    }

    @Test
    fun `anthropic headers and event parse`() = runTest {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":25}}}
            
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu_1","name":"write_file"}}
            
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}
            
            event: content_block_stop
            data: {"type":"content_block_stop","index":0}
            
            event: message_stop
            data: {"type":"message_stop"}
            
        """.trimIndent()
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val events = ChatClients(http)
            .stream(provider("anthropic"), "key", "claude-x", listOf(AgentMessage("user", "hi")), emptyList())
            .toList()
        val recorded = server.takeRequest()
        assertEquals("key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        assertTrue(events.any { it is StreamEvent.ToolCallStart && it.name == "write_file" })
        assertTrue(events.any { it is StreamEvent.ToolCallEnd })
        assertTrue(events.last() is StreamEvent.Done)
    }

    @Test
    fun `quota exhausted mapping`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"quota exceeded"}}"""))
        val events = ChatClients(http)
            .stream(provider("openai"), "k", "m", listOf(AgentMessage("user", "hi")), emptyList())
            .toList()
        val failed = events.filterIsInstance<StreamEvent.Failed>().first()
        assertTrue(failed.quotaExhausted)
    }

    @Test
    fun `gemini tool call parsed`() = runTest {
        val sse = """
            data: {"candidates":[{"content":{"parts":[{"text":"Делаю"}]}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2}}
            
            data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"list_dir","args":{"path":"."}}}]}}],"finishReason":"STOP"}]
            
        """.trimIndent()
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val events = ChatClients(http)
            .stream(provider("gemini"), "key", "gemini-x", listOf(AgentMessage("user", "hi")), emptyList())
            .toList()
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("streamGenerateContent"))
        assertEquals("key", recorded.getHeader("x-goog-api-key"))
        assertTrue(events.any { it is StreamEvent.TextDelta && it.text == "Делаю" })
        assertTrue(events.any { it is StreamEvent.ToolCallStart && it.name == "list_dir" })
    }
}
