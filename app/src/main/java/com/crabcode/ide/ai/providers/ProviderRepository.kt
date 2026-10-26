package com.crabcode.ide.ai.providers

import android.content.Context
import com.crabcode.ide.core.auth.UserAccount
import com.crabcode.ide.core.auth.UserSpace
import com.crabcode.ide.core.auth.UserVault
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-user provider registry: config/providers.json (structure) +
 * UserVault (secrets). Mirrors desktop providers.ts state shape.
 */
@Serializable
data class ActiveSelection(val providerId: String? = null, val modelId: String? = null)

class ProviderRepository(
    private val context: Context,
    private val space: UserSpace,
    private val account: UserAccount
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val userHash = com.crabcode.ide.core.auth.GoogleAuth.userHash(account.sub)
    private val vault get() = UserVault.get(context)

    private val file: File get() = File(space.config, "providers.json")
    private val activeFile: File get() = File(space.config, "active_provider.json")

    fun load(): List<ProviderConfig> {
        if (!file.exists()) {
            val defaults = ProviderCatalog.defaults()
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(defaults))
            return defaults
        }
        return runCatching { json.decodeFromString<List<ProviderConfig>>(file.readText()) }
            .getOrElse { ProviderCatalog.defaults() }
    }

    fun save(providers: List<ProviderConfig>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(providers))
    }

    fun active(): ActiveSelection {
        if (!activeFile.exists()) return ActiveSelection()
        return runCatching { json.decodeFromString<ActiveSelection>(activeFile.readText()) }
            .getOrElse { ActiveSelection() }
    }

    fun setActive(providerId: String?, modelId: String?) {
        activeFile.parentFile?.mkdirs()
        activeFile.writeText(json.encodeToString(ActiveSelection(providerId, modelId)))
    }

    fun apiKey(providerId: String): String? = vault.get(userHash, "prov_$providerId")

    fun setApiKey(providerId: String, key: String?) {
        if (key.isNullOrBlank()) vault.remove(userHash, "prov_$providerId")
        else vault.put(userHash, "prov_$providerId", key)
    }

    fun wipeSecrets() = vault.wipeUser(userHash)

    fun resolveActive(): Pair<ProviderConfig, String>? {
        val providers = load()
        val sel = active()
        val provider = providers.firstOrNull { it.id == sel.providerId } ?: providers.firstOrNull { it.enabled }
            ?: return null
        val model = sel.modelId ?: provider.activeModel ?: provider.models.firstOrNull()?.id ?: return null
        return provider to model
    }
}
