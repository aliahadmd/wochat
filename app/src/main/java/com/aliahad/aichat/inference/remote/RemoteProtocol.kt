package com.aliahad.aichat.inference.remote

import android.os.Bundle
import com.aliahad.aichat.core.BackendFailureStage
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.BackendMode
import com.aliahad.aichat.inference.BackendInferenceException

internal object RemoteProtocol {
    const val KEY_SUCCESS = "success"
    const val KEY_MESSAGE = "message"
    const val KEY_STAGE = "stage"
    const val KEY_CONTEXT_LIMIT = "contextLimit"
    const val KEY_CONTEXT_SIZE = "contextSize"
    const val KEY_CAPABILITIES = "capabilities"
    const val KEY_MODEL_LOAD_MILLIS = "modelLoadMillis"
    const val KEY_HISTORY_RESTORE_MILLIS = "historyRestoreMillis"
    const val KEY_PROMPT_EVALUATION_MILLIS = "promptEvaluationMillis"
    const val KEY_FIRST_TOKEN_MILLIS = "firstTokenMillis"
    const val KEY_GENERATED = "generated"
    const val KEY_LOAD_MILLIS = "loadMillis"
    const val KEY_PROMPT_TPS = "promptTps"
    const val KEY_GENERATION_TPS = "generationTps"
    const val KEY_PEAK_PSS = "peakPss"

    const val PHASE_NONE = 0
    const val PHASE_PREPARING_HISTORY = 1
    const val PHASE_EVALUATING_PROMPT = 2
    const val PHASE_ENCODING_MEDIA = 3
    const val PHASE_GENERATING = 4

    fun success(block: Bundle.() -> Unit = {}): Bundle = Bundle().apply {
        putBoolean(KEY_SUCCESS, true)
        block()
    }

    fun failure(stage: BackendFailureStage, message: String): Bundle = Bundle().apply {
        putBoolean(KEY_SUCCESS, false)
        putInt(KEY_STAGE, stage.ordinal)
        putString(KEY_MESSAGE, message.take(1_000))
    }

    fun phaseCode(state: InferenceState): Int = when (state) {
        InferenceState.PreparingHistory -> PHASE_PREPARING_HISTORY
        InferenceState.EvaluatingPrompt -> PHASE_EVALUATING_PROMPT
        InferenceState.EncodingMedia -> PHASE_ENCODING_MEDIA
        InferenceState.Generating -> PHASE_GENERATING
        else -> PHASE_NONE
    }

    fun phase(code: Int): InferenceState? = when (code) {
        PHASE_PREPARING_HISTORY -> InferenceState.PreparingHistory
        PHASE_EVALUATING_PROMPT -> InferenceState.EvaluatingPrompt
        PHASE_ENCODING_MEDIA -> InferenceState.EncodingMedia
        PHASE_GENERATING -> InferenceState.Generating
        else -> null
    }

    fun stopReason(code: Int): GenerationStopReason =
        GenerationStopReason.entries.getOrElse(code) { GenerationStopReason.ERROR }
}

internal fun Bundle.requireRemoteSuccess(defaultStage: BackendFailureStage) {
    if (getBoolean(RemoteProtocol.KEY_SUCCESS, false)) return
    val stage = BackendFailureStage.entries.getOrElse(
        getInt(RemoteProtocol.KEY_STAGE, defaultStage.ordinal),
    ) { defaultStage }
    throw BackendInferenceException(
        BackendMode.VULKAN,
        stage,
        getString(RemoteProtocol.KEY_MESSAGE) ?: "Vulkan inference failed.",
    )
}
