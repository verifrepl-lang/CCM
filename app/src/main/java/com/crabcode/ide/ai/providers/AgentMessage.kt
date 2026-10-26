package com.crabcode.ide.ai.providers

import kotlinx.serialization.Serializable

/** Unified conversation representation shared by all API families. */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String = "{}"
)

@Serializable
data class ToolResultMessage(
    val toolCallId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false
)

@Serializable
data class AgentMessage(
    val role: String,                       // system | user | assistant | tool
    val content: String = "",
    val images: List<String> = emptyList(), // data URLs
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResultMessage> = emptyList()
)

/** A declared tool (JSON Schema subset shared with the desktop TOOL_DEFS). */
@Serializable
data class ToolSchema(
    val name: String,
    val description: String,
    val parametersJson: String = "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
)

/** Events emitted while a model answers — the mobile streaming contract. */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ToolCallStart(val index: Int, val id: String, val name: String) : StreamEvent
    data class ToolCallDelta(val index: Int, val argsFragment: String) : StreamEvent
    data class ToolCallEnd(val index: Int) : StreamEvent
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : StreamEvent
    data object Done : StreamEvent
    data class Failed(val message: String, val httpStatus: Int? = null, val quotaExhausted: Boolean = false) : StreamEvent
}

class ProviderUnavailableException(message: String, val quotaExhausted: Boolean = false) : Exception(message)
