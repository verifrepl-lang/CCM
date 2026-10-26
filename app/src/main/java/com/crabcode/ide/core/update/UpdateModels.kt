package com.crabcode.ide.core.update

import kotlinx.serialization.Serializable

/**
 * env-manifest.json — published with every GitHub Release.
 * Describes rootfs/proot artifacts for OTA environment installation.
 */
@Serializable
data class EnvManifest(
    val manifestVersion: Int = 1,
    val appVersion: String,
    val rootfs: RootfsArtifact,               // primary (arm64)
    val rootfsX86_64: RootfsArtifact? = null, // x86_64 devices / emulators
    val notes: String? = null
) {
    @Serializable
    data class RootfsArtifact(
        val version: String,
        val arch: String,
        val file: String,
        val sha256: String,
        val size: Long
    )

    /** Artifact matching the host architecture (arm64 | x86_64). */
    fun rootfsForArch(arch: String): RootfsArtifact? = when (arch) {
        "x86_64" -> rootfsX86_64 ?: rootfs.takeIf { it.arch == "x86_64" }
        else -> rootfs.takeIf { it.arch == arch }
    }
}

/** GitHub Release with its assets (subset of the REST payload). */
@Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList()
) {
    @Serializable
    data class GitHubAsset(
        val name: String,
        val size: Long,
        val browser_download_url: String
    )
}

/** Rollout artifact of the app itself. */
@Serializable
data class AppUpdateInfo(
    val version: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val notes: String
)
