package com.crabcode.ide.core.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Mandatory Google Sign-In (Credential Manager, official OIDC flow).
 * The ID token is verified locally against Google's JWKS [JwksVerifier].
 *
 * NOTE: put your OAuth client id into google_client_id config — see README.
 */
class GoogleAuth(
    private val context: Context,
    private val http: okhttp3.OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val clientId: String by lazy {
        // google_client_id.txt ships empty; fill it with your Web OAuth client id.
        context.assets.open("google_client_id.txt").bufferedReader().use { it.readText().trim() }
    }

    suspend fun signIn(): UserAccount = withContext(Dispatchers.IO) {
        require(clientId.isNotEmpty() && clientId.endsWith(".apps.googleusercontent.com")) {
            context.getString(com.crabcode.ide.R.string.err_no_client_id)
        }
        val manager = CredentialManager.create(context)
        val googleOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()
        val response = manager.getCredential(context, request)
        val credential = response.credential
        require(credential is GoogleIdTokenCredential) { "Unsupported credential" }
        val verifier = JwksVerifier(context, http, clientId)
        val account = verifier.verifyIdToken(credential.idToken)

        // Persist verified session
        val file = accountFile(context)
        file.parentFile!!.mkdirs()
        file.writeText(json.encodeToString(account))
        account
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        accountFile(context).delete()
    }

    companion object {
        fun currentAccount(context: Context): UserAccount? {
            val f = accountFile(context)
            if (!f.exists()) return null
            return runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString<UserAccount>(f.readText())
            }.getOrNull()
        }

        fun accountFile(context: Context) = java.io.File(context.filesDir, "account.json")

        /** Stable per-user directory name — isolation of projects, chats, env, secrets. */
        fun userHash(sub: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(sub.toByteArray()).copyOfRange(0, 12)
                .joinToString("") { "%02x".format(it) }
    }
}
