package com.crabcode.ide.core.update

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resumable artifact downloader with SHA-256 verification.
 *
 * Resume: HTTP Range against the .part file. Integrity: final checksum compare,
 * .part is only promoted to the target name after it matches.
 */
class ResumableDownloader(private val http: okhttp3.OkHttpClient) {

    sealed interface Progress {
        data class Bytes(val downloaded: Long, val total: Long) : Progress
        data class Verifying(val file: File) : Progress
    }

    @Throws(IOException::class)
    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String,
        onProgress: (Progress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        part.parentFile!!.mkdirs()

        var attempts = 0
        while (true) {
            try {
                downloadRange(url, part, onProgress)
                onProgress(Progress.Verifying(target))
                val actual = GitHubReleases.sha256(part)
                if (expectedSha256.isNotEmpty() && !actual.equals(expectedSha256, ignoreCase = true)) {
                    part.delete()
                    throw IOException("SHA-256 mismatch: expected $expectedSha256, got $actual")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("Cannot move ${part.name}")
                return@withContext target
            } catch (e: IOException) {
                attempts++
                if (attempts >= 3 || part.exists().not()) {
                    // No partial data to resume with → let the caller show the error.
                    if (part.exists()) part.delete()
                    throw e
                }
                // partial file survives → retry resumes from where it stopped
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun downloadRange(url: String, part: File, onProgress: (Progress) -> Unit) {
        val existing = if (part.exists()) part.length() else 0L
        val request = okhttp3.Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val resumed = response.code == 206
            if (!resumed && existing > 0) {
                // Server ignored Range → restart from scratch.
                part.delete()
            }
            val totalHeader = response.headers["Content-Range"]
                ?.substringAfter('/')
                ?.toLongOrNull()
                ?: response.body!!.contentLength()

            val body = response.body!!.byteStream()
            java.io.RandomAccessFile(part, "rw").use { raf ->
                if (resumed) raf.seek(existing)
                val buf = ByteArray(1 shl 15)
                var downloaded = if (resumed) existing else 0L
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    downloaded += n
                    onProgress(Progress.Bytes(downloaded, totalHeader))
                }
                raf.fd.sync()
            }
        }
    }

    companion object {
        /** Bytes of free internal storage (rootfs destination). */
        fun freeInternalBytes(context: Context): Long =
            android.os.StatFs(context.filesDir.absolutePath).availableBytes

        fun freeExternalBytes(): Long =
            android.os.StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    }
}
