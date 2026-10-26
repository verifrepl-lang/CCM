package com.crabcode.ide.core.auth

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Local verification of Google ID tokens (RS256) against Google's JWKS endpoint.
 *
 * Rationale: the mobile app is fully local (no shared backend exists for the port),
 * so the "trusted side" verification required by the spec is performed on-device
 * against https://www.googleapis.com/oauth2/v3/certs. The token issuer, audience
 * and expiry are checked exactly like a server-side verifier would do.
 *
 * Tokens are cached: the JWKS is fetched once per session and kept in memory + disk.
 */
class JwksVerifier(
    private val context: Context,
    private val http: OkHttpClient,
    private val clientId: String
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedKeys: Map<String, PublicKey>? = null

    suspend fun verifyIdToken(idToken: String): UserAccount = withContext(Dispatchers.IO) {
        val parts = idToken.split(".")
        require(parts.size == 3) { "Malformed JWT" }

        val headerJson = String(base64UrlDecode(parts[0]))
        val header = json.parseToJsonElement(headerJson).jsonObject
        val alg = header["alg"]?.jsonPrimitive?.content
        val kid = header["kid"]?.jsonPrimitive?.content
        require(alg == "RS256") { "Unsupported algorithm: $alg" }

        val payloadJson = String(base64UrlDecode(parts[1]))
        val payload = json.parseToJsonElement(payloadJson).jsonObject

        val iss = payload["iss"]?.jsonPrimitive?.content
        val aud = payload["aud"]?.jsonPrimitive?.content
        val exp = payload["exp"]?.jsonPrimitive?.content?.toLongOrNull()
        require(iss == "https://accounts.google.com" || iss == "accounts.google.com") { "Bad issuer" }
        require(aud == clientId) { "Token audience does not match this app" }
        require(exp != null && exp * 1000 > System.currentTimeMillis()) { "Token expired" }

        val key = keyFor(kid ?: error("Missing kid"))
        val signedInput = (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)
        val signature = base64UrlDecode(parts[2])
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(signedInput)
        require(verifier.verify(signature)) { "Signature verification failed" }

        UserAccount(
            sub = payload["sub"]?.jsonPrimitive?.content ?: error("Missing sub"),
            email = payload["email"]?.jsonPrimitive?.content ?: "",
            name = payload["name"]?.jsonPrimitive?.content ?: (payload["email"]?.jsonPrimitive?.content ?: "Пользователь"),
            picture = payload["picture"]?.jsonPrimitive?.content
        )
    }

    private suspend fun keyFor(kid: String): PublicKey {
        cachedKeys?.get(kid)?.let { return it }
        val keys = fetchJwks()
        return keys[kid] ?: error("Unknown signing key: $kid")
    }

    private suspend fun fetchJwks(): Map<String, PublicKey> = withContext(Dispatchers.IO) {
        val cacheFile = File(context.cacheDir, "google_jwks.json")
        val body: String = try {
            val fresh = http.newCall(
                Request.Builder().url("https://www.googleapis.com/oauth2/v3/certs").build()
            ).await()
            cacheFile.writeText(fresh)
            fresh
        } catch (e: Exception) {
            // Offline path: previously fetched JWKS lets us still verify tokens.
            cacheFile.takeIf { it.exists() }?.readText() ?: throw e
        }

        val parsed = json.parseToJsonElement(body).jsonObject
        val result = mutableMapOf<String, PublicKey>()
        for (jwk in parsed["keys"]!!.jsonArray) {
            val obj = jwk.jsonObject
            val jwkKid = obj["kid"]?.jsonPrimitive?.content ?: continue
            val kty = obj["kty"]?.jsonPrimitive?.content
            if (kty != "RSA") continue
            val n = base64UrlDecode(obj["n"]?.jsonPrimitive?.content ?: continue)
            val e = base64UrlDecode(obj["e"]?.jsonPrimitive?.content ?: continue)
            val spec = X509EncodedKeySpec(buildRsaSubjectPublicKeyInfo(n, e))
            result[jwkKid] = KeyFactory.getInstance("RSA").generatePublic(spec)
        }
        cachedKeys = result
        result
    }

    private fun base64UrlDecode(s: String): ByteArray =
        Base64.decode(s.replace('-', '+').replace('_', '/').padEnd((s.length + 3) / 4 * 4, '='), Base64.DEFAULT)

    private fun buildRsaSubjectPublicKeyInfo(modulus: ByteArray, exponent: ByteArray): ByteArray {
        fun derLength(len: Int): ByteArray {
            if (len < 0x80) return byteArrayOf(len.toByte())
            if (len <= 0xFF) return byteArrayOf(0x81, len.toByte())
            return byteArrayOf(0x82, (len shr 8).toByte(), len.toByte())
        }
        fun der(tag: Byte, value: ByteArray): ByteArray {
            val len = derLength(value.size)
            return byteArrayOf(tag) + len + value
        }
        fun stripLeadingZero(b: ByteArray): ByteArray =
            if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b

        val pubKey = der(0x30, der(0x02, stripLeadingZero(modulus)) + der(0x02, stripLeadingZero(exponent)))
        val algId = byteArrayOf(0x30, 0x0D, 0x06, 0x09, 0x2A, -0x76 - 0x20, 0x48, -0x72 - 0x24, 0x03, 0x01, 0x01, 0x01, 0x05, 0x00)
        return der(0x30, algId + der(0x03, byteArrayOf(0x00) + pubKey))
    }
}

private suspend fun okhttp3.Call.await(): String = suspendCancellableCoroutine { cont ->
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
