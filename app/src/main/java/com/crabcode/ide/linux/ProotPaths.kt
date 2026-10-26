package com.crabcode.ide.linux

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Builds PRoot invocations for the Debian userspace.
 *
 * Layout on device:
 *  - nativeLibraryDir/libproot.so   — PRoot binary shipped as a native library
 *    (executables in nativeLibraryDir are allowed to exec even under W^X rules;
 *    combined with targetSdk 28 this keeps app-data binaries executable too).
 *  - filesDir/linux/lib/libtalloc.so.2 → symlink to nativeLibraryDir/libtalloc.so
 *  - filesDir/linux/rootfs/          — Debian 12 bookworm (installed from Releases)
 *
 * Bindings: /dev /proc /sys (system), user home → /home/crab, project → /workspace.
 * Fakes root (-0) like the well-known Android distro launchers so apt/dpkg work.
 */
object ProotPaths {

    fun prootBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    fun tallocBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libtalloc.so")

    fun libDir(context: Context): File = File(File(context.filesDir, "linux"), "lib")

    fun rootfsDir(context: Context): File = File(File(context.filesDir, "linux"), "rootfs")

    fun binDir(context: Context): File = File(File(context.filesDir, "linux"), "bin")

    fun ensureLayout(context: Context) {
        libDir(context).mkdirs()
        binDir(context).mkdirs()
        // libtalloc.so.2 symlink: proot links against the SONAME, Android stores
        // the file as libtalloc.so inside nativeLibraryDir.
        val soname = File(libDir(context), "libtalloc.so.2")
        if (!soname.exists()) {
            runCatching {
                java.nio.file.Files.createSymbolicLink(
                    soname.toPath(), tallocBinary(context).toPath()
                )
            }.onFailure {
                // FAT/exotic fs fallback: physical copy.
                soname.writeBytes(tallocBinary(context).readBytes())
            }
        }
    }

    fun isInstalled(context: Context): Boolean =
        File(rootfsDir(context), "bin").exists() &&
        File(rootfsDir(context), "bin/bash").exists() &&
        prootBinary(context).exists()

    fun hostArch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "x86_64" -> "x86_64"
        else -> "arm64"
    }

    /** Full argv for launching a login shell inside the rootfs. */
    fun shellArgv(context: Context, userHome: File, workdir: File, command: String? = null): List<String> {
        val base = prootBase(context, userHome, workdir)
        val env = listOf(
            "/usr/bin/env", "-i",
            "HOME=/home/crab", "USER=crab", "LOGNAME=crab",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8", "LC_ALL=C.UTF-8",
            "SHELL=/bin/bash"
        )
        return if (command == null) base + env + listOf("/bin/bash", "--login")
        else base + env + listOf("/bin/bash", "-c", command)
    }

    private fun prootBase(context: Context, userHome: File, workdir: File): List<String> {
        val rootfs = rootfsDir(context).absolutePath
        return listOf(
            prootBinary(context).absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${userHome.absolutePath}:/home/crab",
            "-b", "${workdir.absolutePath}:/workspace",
            "-w", if (File(workdir, "").exists()) "/workspace" else "/"
        )
    }

    fun prootEnv(context: Context): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to "${libDir(context).absolutePath}:${context.applicationInfo.nativeLibraryDir}",
        "PATH" to "${binDir(context).absolutePath}:/system/bin:/system/xbin",
        "HOME" to context.filesDir.absolutePath,
        "TERM" to "xterm-256color",
        "PROOT_NO_SECCOMP" to "1"
    )
}
