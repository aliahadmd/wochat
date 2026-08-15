package com.aliahad.aichat.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore by preferencesDataStore("settings")

class AppSettingsRepository(
    private val context: Context,
    private val tokenCipher: TokenCipher,
    private val dataStore: DataStore<Preferences>? = null,
) {
    // Test seam: production callers use the process-wide delegate singleton; tests inject
    // a fresh DataStore instance for per-test isolation.
    private val store: DataStore<Preferences> get() = dataStore ?: context.settingsDataStore

    private object Keys {
        val backend = stringPreferencesKey("backend")
        val chosenAutoBackend = stringPreferencesKey("chosen_auto_backend")
        val vulkanQuarantines = stringPreferencesKey("vulkan_quarantines")
        val maxNewTokens = intPreferencesKey("max_new_tokens")
        val maxAnswerTokens = intPreferencesKey("max_answer_tokens")
        val temperature = floatPreferencesKey("temperature")
        val thinking = booleanPreferencesKey("thinking")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val lastQualityMode = stringPreferencesKey("last_quality_mode")
        val memoryEnabled = booleanPreferencesKey("memory_enabled")
        val collectionPaused = booleanPreferencesKey("collection_paused")
        val allowMeteredModelDownloads = booleanPreferencesKey("allow_metered_model_downloads")
    }

    val backendMode: Flow<BackendMode> = store.data.map {
        it[Keys.backend]
            ?.let { value -> runCatching { BackendMode.valueOf(value) }.getOrNull() }
            ?: BackendMode.CPU
    }

    val chosenAutoBackend: Flow<BackendMode?> = store.data.map {
        it[Keys.chosenAutoBackend]?.let { value -> runCatching { BackendMode.valueOf(value) }.getOrNull() }
    }

    val vulkanQuarantines: Flow<Set<String>> = store.data.map {
        decodeStringSet(it[Keys.vulkanQuarantines])
    }

    val generationSettings: Flow<GenerationSettings> = store.data.map {
        GenerationSettings(
            maxNewTokens = it[Keys.maxNewTokens] ?: 1024,
            maxAnswerTokens = it[Keys.maxAnswerTokens] ?: 8192,
            temperature = it[Keys.temperature] ?: 0.3f,
            thinkingEnabled = it[Keys.thinking] ?: false,
            systemPrompt = it[Keys.systemPrompt] ?: "You are a helpful, concise assistant.",
        ).normalized()
    }

    val lastQualityMode: Flow<ChatQualityMode> = store.data.map {
        it[Keys.lastQualityMode]?.let { value ->
            runCatching { ChatQualityMode.valueOf(value) }.getOrNull()
        } ?: ChatQualityMode.FAST
    }

    val thinkingEnabled: Flow<Boolean> = store.data.map {
        it[Keys.thinking] ?: false
    }

    val memoryEnabled: Flow<Boolean> = store.data.map {
        it[Keys.memoryEnabled] ?: true
    }

    val collectionPaused: Flow<Boolean> = store.data.map {
        it[Keys.collectionPaused] ?: false
    }

    val allowMeteredModelDownloads: Flow<Boolean> = store.data.map {
        it[Keys.allowMeteredModelDownloads] ?: false
    }

    suspend fun setBackend(mode: BackendMode) {
        store.edit { it[Keys.backend] = mode.name }
    }

    suspend fun setAllowMeteredModelDownloads(enabled: Boolean) {
        store.edit { it[Keys.allowMeteredModelDownloads] = enabled }
    }

    suspend fun setChosenAutoBackend(mode: BackendMode) {
        require(mode != BackendMode.AUTO)
        store.edit { it[Keys.chosenAutoBackend] = mode.name }
    }

    suspend fun effectiveBackend(
        modelSha256: String? = null,
        deviceFingerprint: String? = null,
        runtimeRevision: String? = null,
    ): BackendMode {
        val selected = when (val configured = backendMode.first()) {
            BackendMode.AUTO -> chosenAutoBackend.first() ?: BackendMode.CPU
            else -> configured
        }
        return if (selected == BackendMode.VULKAN &&
            modelSha256 != null &&
            isVulkanQuarantined(modelSha256, deviceFingerprint, runtimeRevision)
        ) {
            BackendMode.CPU
        } else {
            selected
        }
    }

    suspend fun quarantineVulkan(modelSha256: String, deviceFingerprint: String, runtimeRevision: String) {
        val key = backendQuarantineKey(modelSha256, deviceFingerprint, runtimeRevision)
        store.edit { preferences ->
            preferences[Keys.vulkanQuarantines] =
                (decodeStringSet(preferences[Keys.vulkanQuarantines]) + key).sorted().joinToString(",")
            if (preferences[Keys.backend] == BackendMode.VULKAN.name) {
                preferences[Keys.backend] = BackendMode.CPU.name
            } else {
                preferences[Keys.chosenAutoBackend] = BackendMode.CPU.name
            }
        }
    }

    suspend fun selectCpuAfterVulkanRejection() {
        store.edit { preferences ->
            if (preferences[Keys.backend] == BackendMode.VULKAN.name) {
                preferences[Keys.backend] = BackendMode.CPU.name
            } else {
                preferences[Keys.chosenAutoBackend] = BackendMode.CPU.name
            }
        }
    }

    suspend fun clearVulkanQuarantine(
        modelSha256: String,
        deviceFingerprint: String,
        runtimeRevision: String,
    ) {
        val key = backendQuarantineKey(modelSha256, deviceFingerprint, runtimeRevision)
        store.edit { preferences ->
            preferences[Keys.vulkanQuarantines] =
                (decodeStringSet(preferences[Keys.vulkanQuarantines]) - key).sorted().joinToString(",")
        }
    }

    suspend fun isVulkanQuarantined(
        modelSha256: String,
        deviceFingerprint: String? = null,
        runtimeRevision: String? = null,
    ): Boolean {
        val quarantines = vulkanQuarantines.first()
        return if (deviceFingerprint != null && runtimeRevision != null) {
            backendQuarantineKey(modelSha256, deviceFingerprint, runtimeRevision) in quarantines
        } else {
            quarantines.any { it.startsWith("$modelSha256:") }
        }
    }

    suspend fun updateGeneration(settings: GenerationSettings) {
        val value = settings.normalized()
        store.edit {
            it[Keys.maxNewTokens] = value.maxNewTokens
            it[Keys.maxAnswerTokens] = value.maxAnswerTokens
            it[Keys.temperature] = value.temperature
            it[Keys.thinking] = value.thinkingEnabled
            it[Keys.systemPrompt] = value.systemPrompt
        }
    }

    suspend fun setThinkingEnabled(enabled: Boolean) {
        store.edit { it[Keys.thinking] = enabled }
    }

    suspend fun setLastQualityMode(mode: ChatQualityMode) {
        store.edit { it[Keys.lastQualityMode] = mode.name }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        store.edit { it[Keys.memoryEnabled] = enabled }
    }

    suspend fun setCollectionPaused(paused: Boolean) {
        store.edit { it[Keys.collectionPaused] = paused }
    }

    fun hasToken(): Boolean = tokenCipher.hasToken()
    fun maskedToken(): String? = tokenCipher.maskedToken()
    fun token(): String? = tokenCipher.readToken()
    fun saveToken(token: String) = tokenCipher.saveToken(token)
    fun clearToken() = tokenCipher.clearToken()

    private fun decodeStringSet(raw: String?): Set<String> = raw
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        .orEmpty()

    private fun backendQuarantineKey(
        modelSha256: String,
        deviceFingerprint: String,
        runtimeRevision: String,
    ): String = "$modelSha256:$deviceFingerprint:$runtimeRevision"
}
