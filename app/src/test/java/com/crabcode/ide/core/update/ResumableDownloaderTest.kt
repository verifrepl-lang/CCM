package com.crabcode.ide.core.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `downloads and verifies checksum`() = runTest {
        val payload = "hello crab".repeat(100).toByteArray()
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .setHeader("Content-Type", "application/octet-stream")
        )
        val target = File(tmp.root, "out.bin")
        val d = ResumableDownloader(http)
        val result = d.download(server.url("/f").toString(), target, sha256(payload))
        assertEquals(payload.size.toLong(), result.length())
        assertEquals(0, server.takeRequest().headers["Range"]?.let { 1 } ?: 0)
    }

    @Test
    fun `mismatched checksum rejected`() = runTest {
        server.enqueue(MockResponse().setBody("abc"))
        val target = File(tmp.root, "bad.bin")
        try {
            ResumableDownloader(http).download(server.url("/f").toString(), target, "deadbeef")
            assertTrue(false)
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertTrue(!target.exists())
    }

    @Test
    fun `resume uses range header`() = runTest {
        val payload = "0123456789".repeat(100).toByteArray()
        val part = File(tmp.root, "big.bin.part")
        part.writeBytes(payload.copyOfRange(0, 500))

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 500-${payload.size - 1}/${payload.size}")
                .setBody(okio.Buffer().write(payload.copyOfRange(500, payload.size)))
        )
        val target = File(tmp.root, "big.bin")
        ResumableDownloader(http).download(server.url("/f").toString(), target, sha256(payload))
        assertEquals("bytes=500-", server.takeRequest().getHeader("Range"))
        assertEquals(payload.size.toLong(), target.length())
        assertTrue(!part.exists())
    }
}
