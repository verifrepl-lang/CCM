package com.crabcode.ide.core.update

import com.crabcode.ide.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * GitHub Releases client. Owner/repo come from BuildConfig (gradle.properties:
 * CRAB_GITHUB_OWNER / CRAB_GITHUB_REPO) so the whole project is fork-friendly.
 */
class GitHubReleases(
    private val http: OkHttpClient,
    private val owner: String = BuildConfig.GITHUB_OWNER,
    private val repo: String = BuildConfig.GITHUB_REPO
) {
    private val json = Json { ignoreUnknownKeys = true }

    val baseUrl: String get() = "https://github.com/$owner/$repo"
    val releasesUrl: String get() = "$baseUrl/releases"

    suspend fun latestRelease(): GitHubRelease = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val body = http.newCall(Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()).awaitText()
        json.decodeFromString(body)
    }

    suspend fun envManifest(release: GitHubRelease): EnvManifest = withContext(Dispatchers.IO) {
        val asset = release.assets.firstOrNull { it.name == BuildConfig.OTA_ENV_MANIFEST }
            ?: error("Release ${release.tag_name} has no ${BuildConfig.OTA_ENV_MANIFEST}")
        val body = http.newCall(Request.Builder().url(asset.browser_download_url).build()).awaitText()
        json.decodeFromString(body)
    }

    suspend fun appUpdate(release: GitHubRelease): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@withContext null
        val sha256 = release.assets
            .firstOrNull { it.name == "${apk.name}.sha256" }
            ?.let { asset ->
                http.newCall(Request.Builder().url(asset.browser_download_url).build())
                    .awaitText().trim().substringBefore(' ').lowercase()
            } ?: ""
        AppUpdateInfo(
            version = release.tag_name.removePrefix("v"),
            apkUrl = apk.browser_download_url,
            apkSize = apk.size,
            sha256 = sha256,
            notes = release.body ?: ""
        )
    }

    companion object {
        suspend fun okhttp3.Call.awaitText(): String = suspendCancellableCoroutine { cont ->
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val body = response.body?.string() ?: ""
                    response.close()
                    if (cont.isActive) {
                        if (response.isSuccessful) cont.resume(body)
                        else cont.resumeWithException(IllegalStateException("HTTP ${response.code}"))
                    }
                }
            })
            cont.invokeOnCancellation { runCatching { cancel() } }
        }

        fun sha256(file: File): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
