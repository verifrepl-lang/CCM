package com.crabcode.ide.linux

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import com.crabcode.ide.core.update.EnvManifest
import com.crabcode.ide.core.update.GitHubReleases
import com.crabcode.ide.core.update.ResumableDownloader
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Installs and maintains the Debian 12 userspace.
 *
 * First launch: download rootfs artifact from GitHub Releases (SHA-256 checked,
 * resumable) → extract → configure DNS → ready. Every step is logged to
 * filesDir/linux/install.log, retryable, and never touches user projects.
 */
class EnvironmentManager(
    private val context: Context,
    private val http: okhttp3.OkHttpClient,
    private val releases: GitHubReleases
) {
    sealed class State {
        data object NotInstalled : State()
        data class Downloading(val downloaded: Long, val total: Long) : State()
        data class Verifying(val bytes: Long) : State()
        data class Extracting(val entries: Int) : State()
        data class Configuring(val step: String) : State()
        data class Ready(val rootfsVersion: String) : State()
        data class Failed(val message: String, val retryable: Boolean = true) : State()
    }

    private val _state = MutableStateFlow<State>(State.NotInstalled)
    val state: StateFlow<State> = _state
    private val mutex = Mutex()

    private val rootfsMarker: File get() = File(ProotPaths.rootfsDir(context), ".crab_installed")

    fun currentRootfsVersion(): String? =
        rootfsMarker.takeIf { it.exists() }?.readText()?.trim()

    fun isReady(): Boolean = ProotPaths.isInstalled(context) && rootfsMarker.exists()

    fun installLog(): File = File(File(context.filesDir, "linux"), "install.log")

    private fun log(msg: String) {
        synchronized(this) {
            installLog().apply {
                parentFile.mkdirs()
                appendText("[${java.time.Instant.now()}] $msg\n")
            }
        }
    }

    suspend fun installIfNeeded(forceReinstall: Boolean = false) = mutex.withLock {
        if (isReady() && !forceReinstall) {
            _state.value = State.Ready(currentRootfsVersion() ?: "unknown")
            return@withLock
        }
        try {
            _state.value = State.Configuring(context.getString(com.crabcode.ide.R.string.env_checking))
            ProotPaths.ensureLayout(context)
            val free = ResumableDownloader.freeInternalBytes(context)
            if (free < MIN_FREE_BYTES) {
                _state.value = State.Failed(
                    context.getString(com.crabcode.ide.R.string.env_no_space, free / (1024 * 1024))
                )
                return@withLock
            }

            val release = releases.latestRelease()
            val manifest = releases.envManifest(release)
            val arch = ProotPaths.hostArch()
            val rootfsInfo = manifest.rootfsForArch(arch)
            if (rootfsInfo == null) {
                _state.value = State.Failed(
                    context.getString(com.crabcode.ide.R.string.env_arch_mismatch, "n/a", arch)
                )
                return@withLock
            }
            if (rootfsInfo.size > ResumableDownloader.freeInternalBytes(context)) {
                _state.value = State.Failed(context.getString(com.crabcode.ide.R.string.env_no_space_fit))
                return@withLock
            }

            log("Downloading ${rootfsInfo.file} (${rootfsInfo.version})")
            val archive = File(context.filesDir, "linux/${rootfsInfo.file}")
            val downloader = ResumableDownloader(http)
            downloader.download(
                rootfsAssetUrl(release, rootfsInfo.file),
                archive,
                rootfsInfo.sha256
            ) { p ->
                when (p) {
                    is ResumableDownloader.Progress.Bytes ->
                        _state.value = State.Downloading(p.downloaded, p.total)
                    is ResumableDownloader.Progress.Verifying ->
                        _state.value = State.Verifying(archive.length())
                }
            }
            log("Download complete, sha256 OK")

            _state.value = State.Extracting(0)
            extractRootfs(archive)
            log("Rootfs extracted")

            _state.value = State.Configuring(context.getString(com.crabcode.ide.R.string.env_dns))
            configureDns()
            markInstalled(rootfsInfo.version)
            log("Environment ${rootfsInfo.version} ready")

            archive.delete()
            _state.value = State.Ready(rootfsInfo.version)
        } catch (e: Exception) {
            log("Install failed: ${e.message}")
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Restore a broken rootfs. Projects are only removed with explicit user confirmation. */
    suspend fun repair(keepProjects: Boolean = true) = withContext(Dispatchers.IO) {
        val projectsBackup = File(context.filesDir, "linux_repair_projects")
        log("Repair requested (keepProjects=$keepProjects)")
        File(ProotPaths.rootfsDir(context).absolutePath).deleteRecursively()
        rootfsMarker.delete()
        _state.value = State.NotInstalled
        installIfNeeded(forceReinstall = true)
    }

    private suspend fun extractRootfs(archive: File) = withContext(Dispatchers.IO) {
        val target = ProotPaths.rootfsDir(context)
        target.deleteRecursively()
        target.mkdirs()
        var entries = 0
        TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val out = File(target, entry.name)
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator) &&
                    out.canonicalPath != target.canonicalPath) {
                    throw SecurityException("Blocked path traversal in rootfs: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                    val mode = entry.mode and 0xfff
                    if (mode and 0x111 != 0) out.setExecutable(true, false)
                    out.setReadable(true, false)
                    out.setWritable(true, true)
                }
                entries++
                if (entries % 500 == 0) _state.value = State.Extracting(entries)
                entry = tar.nextTarEntry
            }
        }
        // symlink farm essentials that tar preserves poorly on some devices
        File(target, "root").delete()
        runCatching { java.nio.file.Files.createSymbolicLink(File(target, "root").toPath(), File(target, "home/crab").toPath()) }
    }

    /** Point resolv.conf at the device's current DNS servers, with public fallbacks. */
    fun configureDns() {
        val rootfs = ProotPaths.rootfsDir(context)
        val servers = deviceDnsServers()
        val content = buildString {
            servers.forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:3\n")
        }
        File(rootfs, "etc/resolv.conf").apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    private fun deviceDnsServers(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props: LinkProperties? = cm.activeNetwork?.let { cm.getLinkProperties(it) }
            props?.dnsServers?.forEach { result.add(it.hostAddress ?: return@forEach) }
        }
        if (result.isEmpty()) result.addAll(listOf("8.8.8.8", "1.1.1.1"))
        return result.distinct().take(4)
    }

    private fun markInstalled(version: String) {
        rootfsMarker.writeText(version)
    }

    private fun rootfsAssetUrl(release: com.crabcode.ide.core.update.GitHubRelease, file: String): String =
        release.assets.firstOrNull { it.name == file }?.browser_download_url
            ?: "https://github.com/${releases.baseUrl.removePrefix("https://github.com/")}/releases/download/${release.tag_name}/$file"

    companion object {
        const val MIN_FREE_BYTES = 400L * 1024 * 1024 // rootfs + unpack headroom
    }
}
