package com.crabcode.ide.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Per-user secret vault (API keys, MCP secrets) backed by
 * EncryptedSharedPreferences (AES-256-GCM, AndroidKeyStore master key).
 *
 * Keys are namespaced with the user hash, so accounts never share credentials —
 * mirrors `apiKeyEnc` isolation of the desktop ProviderConfig.
 */
class UserVault private constructor(private val prefs: SharedPreferences) {

    fun put(userHash: String, key: String, value: String) =
        prefs.edit().putString("$userHash:$key", value).apply()

    fun get(userHash: String, key: String): String? = prefs.getString("$userHash:$key", null)

    fun remove(userHash: String, key: String) = prefs.edit().remove("$userHash:$key").apply()

    fun wipeUser(userHash: String) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$userHash:") }.forEach { edit.remove(it) }
        edit.apply()
    }

    companion object {
        @Volatile private var instance: UserVault? = null

        fun get(context: Context): UserVault =
            instance ?: synchronized(this) {
                instance ?: UserVault(
                    EncryptedSharedPreferences.create(
                        context,
                        "crab_vault",
                        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                ).also { instance = it }
            }
    }
}
