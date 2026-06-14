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
import com.aliahad.aichat.overlay.DEFAULT_FLOATING_PROMPT_TEMPLATES
import com.aliahad.aichat.overlay.FloatingPromptTemplate
import com.aliahad.aichat.overlay.MAX_FLOATING_PROMPT_LABEL_CHARS
import com.aliahad.aichat.overlay.MAX_FLOATING_PROMPT_TEXT_CHARS
import com.aliahad.aichat.overlay.ScreenAssistantPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.settingsDataStore by preferencesDataStore("settings")

class AppSettingsRepository(
    private val context: Context,
    private val tokenCipher: TokenCipher,
) {
    private object Keys {
        val backend = stringPreferencesKey("backend")
        val chosenAutoBackend = stringPreferencesKey("chosen_auto_backend")
        val maxNewTokens = intPreferencesKey("max_new_tokens")
        val maxAnswerTokens = intPreferencesKey("max_answer_tokens")
        val temperature = floatPreferencesKey("temperature")
        val thinking = booleanPreferencesKey("thinking")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val lastQualityMode = stringPreferencesKey("last_quality_mode")
        val memoryEnabled = booleanPreferencesKey("memory_enabled")
        val collectionPaused = booleanPreferencesKey("collection_paused")
        val actionAllowlist = stringPreferencesKey("action_allowlist")
        val floatingAssistantEnabled = booleanPreferencesKey("floating_assistant_enabled")
        val floatingAssistantBubbleX = intPreferencesKey("floating_assistant_bubble_x")
        val floatingAssistantBubbleY = intPreferencesKey("floating_assistant_bubble_y")
        val floatingAssistantLastPreset = stringPreferencesKey("floating_assistant_last_preset")
        val floatingPromptTemplates = stringPreferencesKey("floating_prompt_templates")
    }

    val backendMode: Flow<BackendMode> = context.settingsDataStore.data.map {
        BackendMode.CPU
    }

    val chosenAutoBackend: Flow<BackendMode?> = context.settingsDataStore.data.map {
        it[Keys.chosenAutoBackend]?.let { value -> runCatching { BackendMode.valueOf(value) }.getOrNull() }
    }

    val generationSettings: Flow<GenerationSettings> = context.settingsDataStore.data.map {
        GenerationSettings(
            maxNewTokens = it[Keys.maxNewTokens] ?: 1024,
            maxAnswerTokens = it[Keys.maxAnswerTokens] ?: 8192,
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

    val memoryEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.memoryEnabled] ?: true
    }

    val collectionPaused: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.collectionPaused] ?: true
    }

    val actionAllowlist: Flow<Set<String>> = context.settingsDataStore.data.map {
        it[Keys.actionAllowlist]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()
    }

    val floatingAssistantEnabled: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.floatingAssistantEnabled] ?: false
    }

    val floatingAssistantBubblePosition: Flow<Pair<Int, Int>> = context.settingsDataStore.data.map {
        (it[Keys.floatingAssistantBubbleX] ?: -1) to (it[Keys.floatingAssistantBubbleY] ?: -1)
    }

    val floatingAssistantLastPreset: Flow<ScreenAssistantPreset> = context.settingsDataStore.data.map {
        it[Keys.floatingAssistantLastPreset]
            ?.let { value -> runCatching { ScreenAssistantPreset.valueOf(value) }.getOrNull() }
            ?: ScreenAssistantPreset.SUMMARIZE
    }

    val floatingPromptTemplates: Flow<List<FloatingPromptTemplate>> = context.settingsDataStore.data.map {
        decodeFloatingPromptTemplates(it[Keys.floatingPromptTemplates])
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
            it[Keys.maxNewTokens] = value.maxNewTokens
            it[Keys.maxAnswerTokens] = value.maxAnswerTokens
            it[Keys.temperature] = value.temperature
            it[Keys.thinking] = value.thinkingEnabled
            it[Keys.systemPrompt] = value.systemPrompt
        }
    }

    suspend fun setLastQualityMode(mode: ChatQualityMode) {
        context.settingsDataStore.edit { it[Keys.lastQualityMode] = mode.name }
    }

    suspend fun setMemoryEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.memoryEnabled] = enabled }
    }

    suspend fun setCollectionPaused(paused: Boolean) {
        context.settingsDataStore.edit { it[Keys.collectionPaused] = paused }
    }

    suspend fun setActionAllowlist(packages: Set<String>) {
        context.settingsDataStore.edit {
            it[Keys.actionAllowlist] = packages.sorted().joinToString(",")
        }
    }

    suspend fun addActionAllowedPackage(packageName: String) {
        val normalized = packageName.trim().takeIf(String::isNotBlank) ?: return
        context.settingsDataStore.edit {
            val existing = it[Keys.actionAllowlist]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toMutableSet()
                ?: mutableSetOf()
            existing += normalized
            it[Keys.actionAllowlist] = existing.sorted().joinToString(",")
        }
    }

    suspend fun setFloatingAssistantEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.floatingAssistantEnabled] = enabled }
    }

    suspend fun setFloatingAssistantBubblePosition(x: Int, y: Int) {
        context.settingsDataStore.edit {
            it[Keys.floatingAssistantBubbleX] = x
            it[Keys.floatingAssistantBubbleY] = y
        }
    }

    suspend fun setFloatingAssistantLastPreset(preset: ScreenAssistantPreset) {
        context.settingsDataStore.edit { it[Keys.floatingAssistantLastPreset] = preset.name }
    }

    suspend fun createFloatingPromptTemplate(label: String, prompt: String, enabled: Boolean = true) {
        val trimmedLabel = label.trim()
        val trimmedPrompt = prompt.trim()
        validateFloatingPrompt(trimmedLabel, trimmedPrompt)
        context.settingsDataStore.edit { preferences ->
            val now = System.currentTimeMillis()
            val current = decodeFloatingPromptTemplates(preferences[Keys.floatingPromptTemplates])
            val template = FloatingPromptTemplate(
                id = UUID.randomUUID().toString(),
                label = trimmedLabel,
                prompt = trimmedPrompt,
                enabled = enabled,
                createdAt = now,
                updatedAt = now,
            )
            preferences[Keys.floatingPromptTemplates] = encodeFloatingPromptTemplates(current + template)
        }
    }

    suspend fun updateFloatingPromptTemplate(
        id: String,
        label: String,
        prompt: String,
        enabled: Boolean,
    ) {
        val trimmedLabel = label.trim()
        val trimmedPrompt = prompt.trim()
        validateFloatingPrompt(trimmedLabel, trimmedPrompt)
        context.settingsDataStore.edit { preferences ->
            val current = decodeFloatingPromptTemplates(preferences[Keys.floatingPromptTemplates])
            val updated = current.map { template ->
                if (template.id == id) {
                    template.copy(
                        label = trimmedLabel,
                        prompt = trimmedPrompt,
                        enabled = enabled,
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    template
                }
            }
            preferences[Keys.floatingPromptTemplates] = encodeFloatingPromptTemplates(updated)
        }
    }

    suspend fun setFloatingPromptTemplateEnabled(id: String, enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            val current = decodeFloatingPromptTemplates(preferences[Keys.floatingPromptTemplates])
            preferences[Keys.floatingPromptTemplates] = encodeFloatingPromptTemplates(
                current.map { template ->
                    if (template.id == id) {
                        template.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
                    } else {
                        template
                    }
                },
            )
        }
    }

    suspend fun deleteFloatingPromptTemplate(id: String) {
        context.settingsDataStore.edit { preferences ->
            val current = decodeFloatingPromptTemplates(preferences[Keys.floatingPromptTemplates])
            preferences[Keys.floatingPromptTemplates] = encodeFloatingPromptTemplates(
                current.filterNot { it.id == id },
            )
        }
    }

    suspend fun setFloatingPromptTemplates(templates: List<FloatingPromptTemplate>) {
        context.settingsDataStore.edit {
            it[Keys.floatingPromptTemplates] = encodeFloatingPromptTemplates(
                templates.map { template ->
                    template.copy(
                        label = template.label.trim().take(MAX_FLOATING_PROMPT_LABEL_CHARS),
                        prompt = template.prompt.trim().take(MAX_FLOATING_PROMPT_TEXT_CHARS),
                    )
                }.filter { template ->
                    template.id.isNotBlank() &&
                        template.label.isNotBlank() &&
                        template.prompt.isNotBlank()
                }.ifEmpty { DEFAULT_FLOATING_PROMPT_TEMPLATES },
            )
        }
    }

    suspend fun resetFloatingPromptTemplates() {
        setFloatingPromptTemplates(DEFAULT_FLOATING_PROMPT_TEMPLATES)
    }

    fun hasToken(): Boolean = tokenCipher.hasToken()
    fun maskedToken(): String? = tokenCipher.maskedToken()
    fun token(): String? = tokenCipher.readToken()
    fun saveToken(token: String) = tokenCipher.saveToken(token)
    fun clearToken() = tokenCipher.clearToken()

    private fun validateFloatingPrompt(label: String, prompt: String) {
        require(label.isNotBlank()) { "Template name is required." }
        require(label.length <= MAX_FLOATING_PROMPT_LABEL_CHARS) {
            "Template name must be $MAX_FLOATING_PROMPT_LABEL_CHARS characters or less."
        }
        require(prompt.isNotBlank()) { "Template prompt is required." }
        require(prompt.length <= MAX_FLOATING_PROMPT_TEXT_CHARS) {
            "Template prompt must be $MAX_FLOATING_PROMPT_TEXT_CHARS characters or less."
        }
    }

    fun decodeFloatingPromptTemplates(raw: String?): List<FloatingPromptTemplate> {
        if (raw.isNullOrBlank()) return DEFAULT_FLOATING_PROMPT_TEMPLATES
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val label = item.optString("label").trim()
                    val prompt = item.optString("prompt").trim()
                    if (id.isBlank() || label.isBlank() || prompt.isBlank()) continue
                    add(
                        FloatingPromptTemplate(
                            id = id,
                            label = label.take(MAX_FLOATING_PROMPT_LABEL_CHARS),
                            prompt = prompt.take(MAX_FLOATING_PROMPT_TEXT_CHARS),
                            enabled = item.optBoolean("enabled", true),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                        ),
                    )
                }
            }.ifEmpty { DEFAULT_FLOATING_PROMPT_TEMPLATES }
        }.getOrElse { DEFAULT_FLOATING_PROMPT_TEMPLATES }
    }

    fun encodeFloatingPromptTemplates(templates: List<FloatingPromptTemplate>): String =
        JSONArray().apply {
            templates.forEach { template ->
                put(
                    JSONObject()
                        .put("id", template.id)
                        .put("label", template.label)
                        .put("prompt", template.prompt)
                        .put("enabled", template.enabled)
                        .put("createdAt", template.createdAt)
                        .put("updatedAt", template.updatedAt),
                )
            }
        }.toString()
}
