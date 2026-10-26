package com.crabcode.ide.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `config roundtrip`() {
        val cfg = McpConfig(
            listOf(
                McpServerConfig("fs", "stdio", command = "npx -y @modelcontextprotocol/server-filesystem /workspace"),
                McpServerConfig("remote", "http", url = "https://example.com/mcp", autoApproveTools = listOf("search"))
            )
        )
        val text = json.encodeToString(McpConfig.serializer(), cfg)
        val back = json.decodeFromString<McpConfig>(text)
        assertEquals(cfg, back)
        assertEquals("stdio", back.servers[0].transport)
        assertEquals(listOf("search"), back.servers[1].autoApproveTools)
    }

    @Test
    fun `json-rpc frame shape`() {
        val frame = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1L); put("method", "tools/list")
            put("params", buildJsonObject { })
        }
        assertEquals("2.0", frame["jsonrpc"].toString().trim('"'))
        assertTrue(frame.toString().contains("tools/list"))
    }
}
