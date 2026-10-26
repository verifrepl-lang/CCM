package com.crabcode.ide.linux

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes one-shot commands inside the PRoot Debian environment
 * (the mobile counterpart of the desktop run_command/propose_command tools).
 * Real processes, real stdout/stderr — no emulation.
 */
class ProotExecutor(private val context: Context) {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    data class ExecOptions(
        val workdir: File,
        val userHome: File,
        val timeoutMs: Long = 120_000,
        val maxOutputChars: Int = 200_000
    )

    fun isReady(): Boolean = ProotPaths.isInstalled(context)

    suspend fun exec(command: String, options: ExecOptions): Result = withContext(Dispatchers.IO) {
        require(isReady()) { "Linux environment is not installed" }
        ProotPaths.ensureLayout(context)
        EnvironmentManagerDNSRefresh(context)

        val argv = ProotPaths.shellArgv(context, options.userHome, options.workdir, command)
        val pb = ProcessBuilder(argv).apply {
            environment().clear()
            environment().putAll(ProotPaths.prootEnv(context))
            redirectErrorStream(false)
        }
        val proc = pb.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val t1 = Thread { proc.inputStream.bufferedReader().forEachLine { if (stdout.length < options.maxOutputChars) stdout.appendLine(it) } }
        val t2 = Thread { proc.errorStream.bufferedReader().forEachLine { if (stderr.length < options.maxOutputChars) stderr.appendLine(it) } }
        t1.isDaemon = true; t2.isDaemon = true
        t1.start(); t2.start()

        val finished = withTimeoutOrNull(options.timeoutMs) {
            try { proc.waitFor() } catch (e: InterruptedException) { Thread.currentThread().interrupt(); -1 }
        }
        if (finished == null) {
            runCatching { proc.destroy() }
            runCatching { proc.waitFor(3, TimeUnit.SECONDS) || run { proc.destroyForcibly(); true } }
            return@withContext Result(-1, trim(stdout), trim(stderr) + "\n[Таймаут ${options.timeoutMs / 1000}s — процесс остановлен]", timedOut = true)
        }
        runCatching { t1.join(2000); t2.join(2000) }
        Result(proc.exitValue(), trim(stdout), trim(stderr))
    }

    private fun trim(sb: StringBuilder): String = sb.toString().trimEnd()

    /** Quick non-capturing check used by the env screen (true if exit 0). */
    suspend fun checkAlive(userHome: File): Boolean =
        exec("echo ok && uname -sm", ExecOptions(workdir = File(userHome, "tmp"), userHome = userHome, timeoutMs = 30_000)).ok
}

/** Refreshes resolv.conf before a session if the device network changed. */
class EnvironmentManagerDNSRefresh(private val context: Context) {
    operator fun invoke() {
        runCatching {
            val em = EnvironmentManagerHolder.instance ?: return
            em.configureDns()
        }
    }
}

/** Holds the singleton used by the DNS refresh hook. */
object EnvironmentManagerHolder {
    @Volatile var instance: EnvironmentManager? = null
}
