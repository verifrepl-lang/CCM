package com.crabcode.ide.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "crab_settings")

/**
 * Application settings (DataStore). User-scoped secrets live in
 * [com.crabcode.ide.core.auth.UserVault] instead — never mixed with shared prefs.
 */
class AppSettings(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("theme")            // crab-dark | crab-light | system
        val fontScale = floatPreferencesKey("ui_font_scale") // 0.8f .. 1.6f
        val terminalFontSp = floatPreferencesKey("terminal_font_sp")
        val soundAgentDone = booleanPreferencesKey("sound_agent_done")
        val soundAgentApproval = booleanPreferencesKey("sound_agent_approval")
        val soundError = booleanPreferencesKey("sound_error")
        val notifyAgentDone = booleanPreferencesKey("notify_agent_done")
        val notifyApproval = booleanPreferencesKey("notify_approval")
        val notifyError = booleanPreferencesKey("notify_error")
        val notifyEnv = booleanPreferencesKey("notify_env")
        val otaAutoCheck = booleanPreferencesKey("ota_auto_check")
        val otaSkippedVersion = stringPreferencesKey("ota_skipped_version")
        val dialogueLanguage = stringPreferencesKey("dialogue_language")
    }

    val theme: Flow<String> = context.dataStore.data.map { it[Keys.theme] ?: "crab-dark" }
    val fontScale: Flow<Float> = context.dataStore.data.map { it[Keys.fontScale] ?: 1f }
    val terminalFontSp: Flow<Float> = context.dataStore.data.map { it[Keys.terminalFontSp] ?: 12f }

    val soundAgentDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.soundAgentDone] ?: true }
    val soundAgentApproval: Flow<Boolean> = context.dataStore.data.map { it[Keys.soundAgentApproval] ?: true }
    val soundError: Flow<Boolean> = context.dataStore.data.map { it[Keys.soundError] ?: true }
    val notifyAgentDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.notifyAgentDone] ?: true }
    val notifyApproval: Flow<Boolean> = context.dataStore.data.map { it[Keys.notifyApproval] ?: true }
    val notifyError: Flow<Boolean> = context.dataStore.data.map { it[Keys.notifyError] ?: true }
    val notifyEnv: Flow<Boolean> = context.dataStore.data.map { it[Keys.notifyEnv] ?: true }
    val otaAutoCheck: Flow<Boolean> = context.dataStore.data.map { it[Keys.otaAutoCheck] ?: true }
    val otaSkippedVersion: Flow<String> = context.dataStore.data.map { it[Keys.otaSkippedVersion] ?: "" }
    val dialogueLanguage: Flow<String> = context.dataStore.data.map { it[Keys.dialogueLanguage] ?: "ru" }

    suspend fun setTheme(v: String) = context.dataStore.edit { it[Keys.theme] = v }
    suspend fun setFontScale(v: Float) = context.dataStore.edit { it[Keys.fontScale] = v.coerceIn(0.8f, 1.6f) }
    suspend fun setTerminalFontSp(v: Float) = context.dataStore.edit { it[Keys.terminalFontSp] = v.coerceIn(8f, 24f) }
    suspend fun setSoundAgentDone(v: Boolean) = context.dataStore.edit { it[Keys.soundAgentDone] = v }
    suspend fun setSoundAgentApproval(v: Boolean) = context.dataStore.edit { it[Keys.soundAgentApproval] = v }
    suspend fun setSoundError(v: Boolean) = context.dataStore.edit { it[Keys.soundError] = v }
    suspend fun setNotifyAgentDone(v: Boolean) = context.dataStore.edit { it[Keys.notifyAgentDone] = v }
    suspend fun setNotifyApproval(v: Boolean) = context.dataStore.edit { it[Keys.notifyApproval] = v }
    suspend fun setNotifyError(v: Boolean) = context.dataStore.edit { it[Keys.notifyError] = v }
    suspend fun setNotifyEnv(v: Boolean) = context.dataStore.edit { it[Keys.notifyEnv] = v }
    suspend fun setOtaAutoCheck(v: Boolean) = context.dataStore.edit { it[Keys.otaAutoCheck] = v }
    suspend fun setOtaSkippedVersion(v: String) = context.dataStore.edit { it[Keys.otaSkippedVersion] = v }
    suspend fun setDialogueLanguage(v: String) = context.dataStore.edit { it[Keys.dialogueLanguage] = v }

    suspend fun themeOnce(): String = theme.first()
}
