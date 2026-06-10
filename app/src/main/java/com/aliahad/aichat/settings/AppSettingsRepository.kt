package com.aliahad.aichat.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.ChatQualityMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

class AppSettingsRepository(
    private val context: Context,
    private val tokenCipher: TokenCipher,
) {
    private object Keys {
        val backend = stringPreferencesKey("backend")
        val chosenAutoBackend = stringPreferencesKey("chosen_auto_backend")
        val contextSize = intPreferencesKey("context_size")
        val maxNewTokens = intPreferencesKey("max_new_tokens")
        val temperature = floatPreferencesKey("temperature")
        val thinking = booleanPreferencesKey("thinking")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val lastQualityMode = stringPreferencesKey("last_quality_mode")
    }

    val backendMode: Flow<BackendMode> = context.settingsDataStore.data.map {
        BackendMode.CPU
    }

    val chosenAutoBackend: Flow<BackendMode?> = context.settingsDataStore.data.map {
        it[Keys.chosenAutoBackend]?.let { value -> runCatching { BackendMode.valueOf(value) }.getOrNull() }
    }

    val generationSettings: Flow<GenerationSettings> = context.settingsDataStore.data.map {
        GenerationSettings(
            contextSize = it[Keys.contextSize] ?: 4096,
            maxNewTokens = it[Keys.maxNewTokens] ?: 512,
            temperature = it[Keys.temperature] ?: 0.3f,
            thinkingEnabled = it[Keys.thinking] ?: false,
            systemPrompt = it[Keys.systemPrompt] ?: "You are a helpful, concise assistant.",
        ).normalized()
    }

    val lastQualityMode: Flow<ChatQualityMode> = context.settingsDataStore.data.map {
        it[Keys.lastQualityMode]?.let { value ->
            runCatching { ChatQualityMode.valueOf(value) }.getOrNull()
        } ?: ChatQualityMode.FAST
    }

    suspend fun setBackend(mode: BackendMode) {
        context.settingsDataStore.edit { it[Keys.backend] = mode.name }
    }

    suspend fun setChosenAutoBackend(mode: BackendMode) {
        require(mode != BackendMode.AUTO)
        context.settingsDataStore.edit { it[Keys.chosenAutoBackend] = mode.name }
    }

    suspend fun updateGeneration(settings: GenerationSettings) {
        val value = settings.normalized()
        context.settingsDataStore.edit {
            it[Keys.contextSize] = value.contextSize
            it[Keys.maxNewTokens] = value.maxNewTokens
            it[Keys.temperature] = value.temperature
            it[Keys.thinking] = value.thinkingEnabled
            it[Keys.systemPrompt] = value.systemPrompt
        }
    }

    suspend fun setLastQualityMode(mode: ChatQualityMode) {
        context.settingsDataStore.edit { it[Keys.lastQualityMode] = mode.name }
    }

    fun hasToken(): Boolean = tokenCipher.hasToken()
    fun maskedToken(): String? = tokenCipher.maskedToken()
    fun token(): String? = tokenCipher.readToken()
    fun saveToken(token: String) = tokenCipher.saveToken(token)
    fun clearToken() = tokenCipher.clearToken()
}
