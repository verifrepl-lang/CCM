package com.crabcode.ide.terminal

import android.content.Context
import com.crabcode.ide.core.auth.UserSpace
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Owns all interactive terminal sessions plus the separate agent output console.
 * Sessions of different users/projects never mix: each instance is created
 * per [UserSpace] and persists history/snapshots under its own directory.
 */
class TerminalManager(
    private val context: Context,
    private val space: UserSpace
) {
    @Serializable
    data class StoredSession(val id: String, val title: String, val workdirPath: String, val history: List<String>)

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> get() = _sessions

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> get() = _activeId

    /** Separate read-only console that mirrors agent command execution output. */
    val agentConsole = AnsiTerminal(80, 200)

    private val _agentRevision = MutableStateFlow(0L)
    val agentRevision: StateFlow<Long> get() = _agentRevision

    val storeFile: File get() = File(space.config, "terminal_sessions.json")

    fun restore() {
        if (!storeFile.exists()) return
        runCatching {
            val stored = json.decodeFromString<List<StoredSession>>(storeFile.readText())
            // Re-create sessions (processes cannot survive app restart on Android),
            // but keep titles, cwd and command history.
            stored.forEach { createSession(File(it.workdirPath), it.title, it.history) }
        }
    }

    fun persist() {
        runCatching {
            val data = _sessions.value.map {
                StoredSession(it.id, it.title, it.workdir.absolutePath, it.history.takeLast(200))
            }
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(json.encodeToString(data))
        }
    }

    fun createSession(workdir: File, title: String = "bash", history: List<String> = emptyList()): TerminalSession {
        val s = TerminalSession(context, UUID.randomUUID().toString(), title, workdir, space.home)
        s.history.addAll(history)
        _sessions.value = _sessions.value + s
        _activeId.value = s.id
        return s
    }

    fun start(session: TerminalSession) {
        runCatching { session.start() }
            .onFailure { appendAgentConsole("\r\n\u001B[31m[Ошибка запуска сессии: ${it.message}]\u001B[0m\r\n") }
    }

    fun close(id: String) {
        _sessions.value.firstOrNull { it.id == id }?.let { s ->
            s.kill()
            _sessions.value = _sessions.value - s
        }
        if (_activeId.value == id) _activeId.value = _sessions.value.lastOrNull()?.id
    }

    fun switch(id: String) { _activeId.value = id }

    fun active(): TerminalSession? = _sessions.value.firstOrNull { it.id == _activeId.value }

    /** Feed agent command output into the dedicated agent console (not user terminals). */
    fun appendAgentConsole(text: String) {
        synchronized(agentConsole) { agentConsole.feed(text) }
        _agentRevision.value = _agentRevision.value + 1
    }

    fun agentConsoleText(): String = synchronized(agentConsole) { agentConsole.allText() }

    fun shutdown() {
        _sessions.value.forEach { runCatching { it.kill() } }
        persist()
    }
}
