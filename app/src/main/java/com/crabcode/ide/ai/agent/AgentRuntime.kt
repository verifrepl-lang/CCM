package com.crabcode.ide.ai.agent

import com.crabcode.ide.ai.providers.AgentMessage
import com.crabcode.ide.ai.providers.ChatClients
import com.crabcode.ide.ai.providers.ProviderConfig
import com.crabcode.ide.ai.providers.StreamEvent
import com.crabcode.ide.ai.providers.ToolCall
import com.crabcode.ide.ai.providers.ToolResultMessage
import com.crabcode.ide.ai.tools.AgentMode
import com.crabcode.ide.ai.tools.ApprovalGate
import com.crabcode.ide.ai.tools.ApprovalRequest
import com.crabcode.ide.ai.tools.ToolRegistry
import com.crabcode.ide.data.CrabStore
import com.crabcode.ide.data.MessageEntity
import com.crabcode.ide.data.SessionEntity
import com.crabcode.ide.terminal.TerminalManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Registry of approvals awaiting the user's answer (persisted as Room rows). */
object ApprovalBroker {
    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()

    fun create(id: String): CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { pending[id] = it }

    fun respond(id: String, allow: Boolean): Boolean {
        val d = pending.remove(id) ?: return false
        d.complete(allow)
        return true
    }

    fun cancelAll() {
        pending.values.forEach { runCatching { it.complete(false) } }
        pending.clear()
    }
}

/**
 * The agent execution cycle of mobile CrabCode — a port of the desktop
 * agent.ts loop: gather context → select tools → execute within policy →
 * stream progress → present changes → persist the session.
 *
 * Room is the single source of truth: every element (text, tool call, result,
 * plan, status, error, approval, file change) is a message row, so the task
 * can be resumed after process death by rebuilding context from the DB.
 */
class AgentRuntime(
    private val scope: CoroutineScope,
    private val store: CrabStore,
    private val sessionProvider: () -> SessionEntity,
    val projectRoot: File,
    private val userHome: File,
    private val provider: ProviderConfig,
    private val apiKey: String?,
    private val model: String,
    private val clients: ChatClients,
    private val executor: com.crabcode.ide.linux.ProotExecutor,
    private val terminal: TerminalManager,
    private val mcp: com.crabcode.ide.ai.mcp.McpManager,
    private val dialogueLanguage: String,
    private val onNotification: (AgentNotification) -> Unit
) {
    enum class AgentNotification { TASK_DONE, NEEDS_APPROVAL, ERROR, QUOTA }

    enum class Status { IDLE, RUNNING, AWAITING_APPROVAL, STOPPED, FAILED }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var runJob: Job? = null

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> get() = _status

    val dao get() = store.db.messageDao()

    private val gate = object : ApprovalGate {
        override suspend fun request(approval: ApprovalRequest): Boolean {
            val id = approval.id
            val deferred = ApprovalBroker.create(id)
            dao.upsert(
                MessageEntity(
                    id = id, sessionId = sessionId(), kind = MessageEntity.KIND_APPROVAL_REQUEST,
                    content = approval.title, toolName = approval.kind.name,
                    toolArgsJson = buildJsonObject {
                        put("detail", approval.detail)
                        put("command", approval.payload["command"] ?: "")
                        put("path", approval.payload["path"] ?: "")
                    }.toString(),
                    createdAt = now(), updatedAt = now()
                )
            )
            _status.value = Status.AWAITING_APPROVAL
            onNotification(AgentNotification.NEEDS_APPROVAL)
            val allowed = deferred.await()
            dao.upsert(
                MessageEntity(
                    id = id + "_r", sessionId = sessionId(), kind = MessageEntity.KIND_APPROVAL_RESPONSE,
                    content = if (allowed) "Одобрено: ${approval.title}" else "Отклонено: ${approval.title}",
                    createdAt = now(), updatedAt = now()
                )
            )
            _status.value = Status.RUNNING
            return allowed
        }
    }

    private fun sessionId(): String = sessionProvider().id

    fun stop() {
        ApprovalBroker.cancelAll()
        runJob?.cancel()
        _status.value = Status.STOPPED
    }

    fun shutdown() {
        stop()
        runJob?.cancelChildren()
    }

    /** Appends the user message (non-blocking) and starts the cycle. */
    fun submitUserMessage(text: String) {
        scope.launch {
            dao.upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(), sessionId = sessionId(),
                    kind = MessageEntity.KIND_TEXT, role = "user", content = text,
                    createdAt = now(), updatedAt = now()
                )
            )
            resume()
        }
    }

    /** (Re-)starts the loop — fresh tasks and recovery after process death. */
    fun resume() {
        if (_status.value == Status.RUNNING) return
        runJob?.cancel()
        runJob = scope.launch { runLoop() }
    }

    private suspend fun runLoop() = mutex.withLock {
        _status.value = Status.RUNNING
        try {
            val mode = AgentMode.valueOf(sessionProvider().mode)
            val toolRegistry = ToolRegistry(
                projectRoot = projectRoot,
                userHome = userHome,
                commandRunner = { cmd ->
                    val res = executor.exec(
                        cmd,
                        com.crabcode.ide.linux.ProotExecutor.ExecOptions(workdir = projectRoot, userHome = userHome)
                    )
                    terminal.appendAgentConsole("$ $cmd\r\n${res.stdout}\r\n${res.stderr}\r\n")
                    ToolRegistry.ProotCommandResult(res.exitCode, res.stdout + "\n" + res.stderr)
                },
                approvalGate = gate,
                mode = mode,
                onFileChange = { path, kind ->
                    dao.upsert(
                        MessageEntity(
                            id = UUID.randomUUID().toString(), sessionId = sessionId(),
                            kind = MessageEntity.KIND_FILE_CHANGE, content = path, toolName = kind.name,
                            createdAt = now(), updatedAt = now()
                        )
                    )
                },
                onPlanUpdate = { stepsJson ->
                    dao.upsert(
                        MessageEntity(
                            id = UUID.randomUUID().toString(), sessionId = sessionId(),
                            kind = MessageEntity.KIND_PLAN, content = stepsJson,
                            createdAt = now(), updatedAt = now()
                        )
                    )
                },
                onCommandOutput = { terminal.appendAgentConsole(it) },
                mcpTools = { mcp.toolRefs().map { ToolRegistry.McpToolRef(it.server, it.tool, it.description) } },
                mcpCall = { server, tool, args -> mcp.callTool(server, tool, args) }
            )

            var round = 0
            while (round < MAX_ROUNDS && currentCoroutineContext().isActive) {
                round++
                val messages = buildContext()
                val textId = UUID.randomUUID().toString()
                val text = StringBuilder()
                val toolCallsById = LinkedHashMap<String, ToolCall>()
                val toolArgsById = mutableMapOf<String, StringBuilder>()
                var failed: StreamEvent.Failed? = null

                clients.stream(provider, apiKey, model, messages, toolRegistry.schemas())
                    .collect { ev ->
                        when (ev) {
                            is StreamEvent.TextDelta -> {
                                text.append(ev.text)
                                dao.upsert(
                                    MessageEntity(
                                        id = textId, sessionId = sessionId(), kind = MessageEntity.KIND_TEXT,
                                        role = "assistant", content = text.toString(), streamOpen = true,
                                        createdAt = createdAtOrNow(textId), updatedAt = now()
                                    )
                                )
                            }
                            is StreamEvent.ReasoningDelta -> {
                                dao.upsert(
                                    MessageEntity(
                                        id = textId + "_think", sessionId = sessionId(), kind = MessageEntity.KIND_TEXT,
                                        role = "assistant", content = REASONING_TAG + ev.text, streamOpen = true,
                                        createdAt = now(), updatedAt = now()
                                    )
                                )
                            }
                            is StreamEvent.ToolCallStart -> {
                                toolCallsById[ev.id] = ToolCall(ev.id, ev.name)
                                toolArgsById[ev.id] = StringBuilder()
                                dao.upsert(
                                    MessageEntity(
                                        id = "status", sessionId = sessionId(), kind = MessageEntity.KIND_STATUS,
                                        content = "Инструмент: ${ev.name}", createdAt = now(), updatedAt = now()
                                    )
                                )
                            }
                            is StreamEvent.ToolCallDelta -> toolArgsById.keys.lastOrNull()?.let { toolArgsById[it]?.append(ev.argsFragment) }
                            is StreamEvent.ToolCallEnd -> Unit
                            is StreamEvent.Usage -> Unit
                            is StreamEvent.Failed -> failed = ev
                            StreamEvent.Done -> Unit
                        }
                    }

                if (failed != null) {
                    val f = failed!!
                    dao.upsert(
                        MessageEntity(
                            id = UUID.randomUUID().toString(), sessionId = sessionId(),
                            kind = MessageEntity.KIND_ERROR, content = f.message,
                            toolStatus = if (f.quotaExhausted) "quota" else "error",
                            createdAt = now(), updatedAt = now()
                        )
                    )
                    onNotification(if (f.quotaExhausted) AgentNotification.QUOTA else AgentNotification.ERROR)
                    _status.value = Status.FAILED
                    return@withLock
                }

                dao.get(textId)?.let { dao.upsert(it.copy(streamOpen = false, updatedAt = now())) }
                dao.get(textId + "_think")?.let {
                    dao.upsert(it.copy(streamOpen = false, updatedAt = now()))
                }
                dao.deleteRow("status")

                if (toolCallsById.isEmpty()) {
                    _status.value = Status.IDLE
                    onNotification(AgentNotification.TASK_DONE)
                    touchSession()
                    return@withLock
                }

                // record the assistant tool-call message for the chat timeline
                val callRowId = UUID.randomUUID().toString()
                dao.upsert(
                    MessageEntity(
                        id = callRowId, sessionId = sessionId(),
                        kind = MessageEntity.KIND_TOOL_CALL, role = "assistant",
                        toolName = toolCallsById.values.joinToString(",") { it.name },
                        toolArgsJson = toolCallsById.values.joinToString("\n") { tc ->
                            "${tc.id}|${tc.name}|${toolArgsById[tc.id]?.toString() ?: "{}"}"
                        },
                        toolStatus = "running", createdAt = now(), updatedAt = now()
                    )
                )

                val results = toolCallsById.values.map { tc ->
                    val argsJson = toolArgsById[tc.id]?.toString()?.takeIf { it.isNotBlank() } ?: "{}"
                    val result = runCatching { toolRegistry.execute(tc.name, argsJson) }
                        .getOrElse { "Ошибка: ${it.message}" }
                    dao.upsert(
                        MessageEntity(
                            id = UUID.randomUUID().toString(), sessionId = sessionId(),
                            kind = MessageEntity.KIND_TOOL_RESULT, role = "tool",
                            content = result.take(20_000), toolName = tc.name,
                            toolStatus = if (result.startsWith("Ошибка")) "error" else "ok",
                            createdAt = now(), updatedAt = now()
                        )
                    )
                    ToolResultMessage(tc.id, tc.name, result.take(20_000), result.startsWith("Ошибка"))
                }

                dao.get(callRowId)?.let { dao.upsert(it.copy(toolStatus = "done", updatedAt = now())) }

                if (!currentCoroutineContext().isActive) {
                    _status.value = Status.STOPPED
                    return@withLock
                }
            }
            _status.value = Status.IDLE
        } catch (e: kotlinx.coroutines.CancellationException) {
            _status.value = Status.STOPPED
        } catch (e: Exception) {
            dao.upsert(
                MessageEntity(
                    id = UUID.randomUUID().toString(), sessionId = sessionId(),
                    kind = MessageEntity.KIND_ERROR, content = e.message ?: e.javaClass.simpleName,
                    createdAt = now(), updatedAt = now()
                )
            )
            onNotification(AgentNotification.ERROR)
            _status.value = Status.FAILED
        } finally {
            touchSession()
        }
    }

    /** Rebuild model context from persisted rows — recovery-safe by design. */
    private suspend fun buildContext(): List<AgentMessage> {
        val rows = dao.observeForSession(sessionId()).first()
        val out = mutableListOf(
            AgentMessage("system", SystemPrompt.build(projectRoot, sessionProvider().mode, dialogueLanguage))
        )
        rows.forEach { row ->
            when (row.kind) {
                MessageEntity.KIND_TEXT -> when {
                    row.role == "user" -> out += AgentMessage("user", row.content)
                    row.role == "assistant" && row.content.startsWith(REASONING_TAG) -> Unit // reasoning not resent
                    row.role == "assistant" && row.content.isNotBlank() -> out += AgentMessage("assistant", row.content)
                }
                MessageEntity.KIND_TOOL_CALL -> {
                    val calls = row.toolArgsJson?.split("\n")?.mapNotNull { line ->
                        val p = line.split("|", limit = 3)
                        if (p.size == 3) ToolCall(p[0], p[1], p[2]) else null
                    } ?: emptyList()
                    if (calls.isNotEmpty()) out += AgentMessage("assistant", toolCalls = calls)
                }
                MessageEntity.KIND_TOOL_RESULT -> out += AgentMessage(
                    "tool", "",
                    toolResults = listOf(
                        ToolResultMessage("pending_${row.id}", row.toolName ?: "tool", row.content)
                    )
                )
                MessageEntity.KIND_APPROVAL_RESPONSE -> out += AgentMessage("user", "[Подтверждение] ${row.content}")
                MessageEntity.KIND_ERROR -> out += AgentMessage(
                    "user", "[Система] Ошибка: ${row.content}. Учти это и продолжай."
                )
                else -> Unit
            }
        }
        // context budget ~ trimmed to the newest messages
        var budget = CONTEXT_CHAR_BUDGET
        val trimmed = mutableListOf(out.first())
        for (i in out.size - 1 downTo 1) {
            val cost = out[i].content.length + out[i].toolCalls.sumOf { it.argumentsJson.length } + 200
            if (budget - cost < 0) break
            budget -= cost
            trimmed.add(1, out[i])
        }
        return trimmed
    }

    private suspend fun createdAtOrNow(id: String): Long = dao.get(id)?.createdAt ?: now()

    private suspend fun touchSession() {
        val s = sessionProvider()
        store.db.sessionDao().upsert(s.copy(updatedAt = now()))
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val MAX_ROUNDS = 25
        const val REASONING_TAG = "⟦reasoning⟧"
        const val CONTEXT_CHAR_BUDGET = 300_000
    }
}
