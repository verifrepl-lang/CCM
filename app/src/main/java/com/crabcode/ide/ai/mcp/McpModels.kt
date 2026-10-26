package com.crabcode.ide.ai.mcp

import kotlinx.serialization.Serializable

/**
 * MCP server connection record (per-user config, stored in config/mcp.json).
 * Same transports as desktop CrabCode: stdio (launched inside the PRoot env)
 * and HTTP (Streamable HTTP / SSE endpoint).
 */
@Serializable
data class McpServerConfig(
    val name: String,
    val transport: String = "stdio",        // stdio | http
    val command: String? = null,            // stdio: run inside Linux env
    val url: String? = null,                // http
    val enabled: Boolean = true,
    val env: Map<String, String> = emptyMap(),
    val autoApproveTools: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class McpConfig(val servers: List<McpServerConfig> = emptyList())

@Serializable
data class McpToolInfo(
    val name: String,
    val description: String = "",
    val server: String
)

enum class McpStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class McpServerState(
    val config: McpServerConfig,
    val status: McpStatus = McpStatus.DISCONNECTED,
    val tools: List<McpToolInfo> = emptyList(),
    val lastError: String? = null,
    val protocolVersion: String? = null
)
