package com.aliahad.aichat.inference

import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.core.ChatTurn
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.InferenceMetrics
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.ModelCapabilities
import com.aliahad.aichat.core.ModelLoadConfiguration
import com.aliahad.aichat.core.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InferenceEngine {
    val state: StateFlow<InferenceState>
    val metrics: StateFlow<InferenceMetrics>
    val loadedModelPath: String?
    val loadedProjectorPath: String?
    val modelContextLimit: Int
    val activeContextSize: Int
    suspend fun loadModel(
        path: String,
        displayName: String,
        configuration: ModelLoadConfiguration,
    )
    suspend fun loadProjector(path: String, imageTokenBudget: Int): ModelCapabilities
    suspend fun unloadProjector()
    suspend fun restoreSession(conversationId: String, history: List<ChatTurn>, settings: GenerationSettings)
    fun generate(
        turn: UserTurn,
        settings: GenerationSettings,
        profile: InferenceExecutionProfile = InferenceExecutionProfile.NORMAL,
    ): Flow<GenerationEvent>
    suspend fun countTokens(text: String): Int
    suspend fun verifyLoadedContext(): Int
    fun cancel()
    suspend fun unload()
    suspend fun benchmark(path: String, displayName: String, settings: GenerationSettings): Map<BackendMode, Double>
    fun systemInfo(): String
    fun destroy()
}
