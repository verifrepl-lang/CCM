package com.crabcode.ide.terminal

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import com.crabcode.ide.linux.NativePty
import com.crabcode.ide.linux.ProotPaths
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A real Linux shell session: forkpty() + PRoot Debian login shell.
 * Reader thread pumps PTY output into [terminal]; input is written to master fd.
 */
class TerminalSession(
    val context: Context,
    val id: String,
    var title: String,
    val workdir: File,
    val userHome: File
) {
    val terminal = AnsiTerminal()
    private var masterFd: Int = -1
    private var pid: Int = -1
    private var readerThread: Thread? = null

    private val _state = MutableStateFlow(State.CREATED)
    val state: StateFlow<State> get() = _state

    /** Command history for this session (up/down arrows). */
    val history = mutableListOf<String>()

    private val _onBell = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val onBell: SharedFlow<Unit> get() = _onBell

    enum class State { CREATED, RUNNING, EXITED }

    @Volatile var exitCode: Int? = null; private set

    fun start() {
        check(_state.value == State.CREATED) { "Session already started" }
        check(ProotPaths.isInstalled(context)) { "Linux environment is not installed" }
        ProotPaths.ensureLayout(context)

        val argv = ProotPaths.shellArgv(context, userHome, workdir)
        val env = ProotPaths.prootEnv(context)

        val res = NativePty.forkPty(
            terminal.rows, terminal.cols,
            argv.toTypedArray(),
            env.entries.map { "${it.key}=${it.value}" }.toTypedArray()
        )
        pid = res[0]
        masterFd = res[1]
        check(pid > 0) { "forkPty failed, errno=$masterFd" }

        _state.value = State.RUNNING
        readerThread = Thread(::pumpLoop, "crab-term-$id").apply {
            isDaemon = true
            start()
        }
    }

    private fun pumpLoop() {
        val pfd = ParcelFileDescriptor.adoptFd(masterFd)
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(8192)
        while (_state.value == State.RUNNING) {
            val n = runCatching { input.read(buf) }.getOrElse { break }
            if (n < 0) break
            if (n == 0) continue
            val decoded = runCatching { String(buf, 0, n, Charsets.UTF_8) }.getOrElse { String(buf, 0, n, Charsets.ISO_8859_1) }
            synchronized(terminal) { terminal.feed(decoded) }
            if (terminal.consumeBell()) _onBell.tryEmit(Unit)
        }
        exitCode = runCatching { NativePty.waitPidNoHang(pid) }.getOrNull()?.takeIf { it >= 0 }
        _state.value = State.EXITED
        runCatching { pfd.close() }
    }

    fun write(data: String) {
        if (_state.value != State.RUNNING) return
        val bytes = data.toByteArray(Charsets.UTF_8)
        runCatching {
            val pfd = ParcelFileDescriptor.dup(masterFd)
            FileOutputStream(pfd.fileDescriptor).use { it.write(bytes) }
        }
    }

    /** Send via the input box with trailing Enter; records history. */
    fun sendCommand(cmd: String) {
        if (cmd.isBlank()) { write("\r"); return }
        if (history.isEmpty() || history.last() != cmd) {
            history.add(cmd)
            if (history.size > 500) history.removeAt(0)
        }
        write(cmd + "\r")
    }

    fun interrupt() { write("\u0003") }   // Ctrl+C
    fun suspendJob() { write("\u001A") }  // Ctrl+Z
    fun eof() { write("\u0004") }         // Ctrl+D
    fun signal(sig: Int) { if (pid > 0) runCatching { NativePty.sendSignal(pid, sig) } }

    fun resize(cols: Int, rows: Int) {
        if (masterFd >= 0) runCatching { NativePty.setWindowSize(masterFd, rows, cols) }
        synchronized(terminal) { terminal.resize(cols, rows) }
    }

    fun kill() {
        if (_state.value != State.RUNNING) return
        runCatching { if (pid > 0) Os.kill(pid, 15) }
        runCatching { if (masterFd >= 0) Os.close(masterFd) }
        _state.value = State.EXITED
    }

    /** Text snapshot (search / export / agent console). */
    fun snapshotText(): String = synchronized(terminal) { terminal.allText() }
}
