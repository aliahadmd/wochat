package com.aliahad.aichat.memory

import com.aliahad.aichat.core.GenerationEvent
import com.aliahad.aichat.core.GenerationSettings
import com.aliahad.aichat.core.GenerationStopReason
import com.aliahad.aichat.core.InferenceExecutionProfile
import com.aliahad.aichat.core.InferenceState
import com.aliahad.aichat.core.UserTurn
import com.aliahad.aichat.inference.InferenceEngine
import com.aliahad.aichat.residency.ModelResidencyState
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive

/**
 * Best-effort background generation for rolling summarization.
 *
 * Gating and non-blocking guarantees:
 * - Runs only while a model is resident ([ModelResidencyState.Ready]) and the engine
 *   is idle ([InferenceState.Ready]).
 * - Every engine call goes through the engine's existing RuntimeOperationGate; no
 *   second lock is introduced.
 * - Only one background generation runs at a time; interactive chat turns cancel it
 *   via [cancel]/[cancelAndJoin] (wired through
 *   ModelResidencyController.beginInferenceUse) so a summarization never delays a
 *   user turn. Cancellation is a real barrier, not a one-shot signal:
 *   [generateText] aborts whenever [interactiveUseActive] reports an interactive
 *   turn, both before touching the engine and again after restoreSession, and the
 *   engine additionally rejects UTILITY-profile generation while interactive use
 *   is active. Generation runs under a summarizer-owned child job, so cancelling
 *   it aborts only the generation, never the caller's coroutine (e.g. the
 *   retention worker).
 * - Any failure, cancellation, or blank output simply yields null so callers fall
 *   back to deterministic behavior.
 */
class BackgroundConversationSummarizer(
    private val inferenceEngine: InferenceEngine,
    private val residencyState: StateFlow<ModelResidencyState>,
    private val interactiveUseActive: () -> Boolean = { false },
) {
    private val activeJob = AtomicReference<Job?>(null)

    val isActive: Boolean
        get() = activeJob.get()?.isActive == true

    /** Aborts the in-flight background generation, if any. Safe to call from any thread. */
    fun cancel() {
        activeJob.getAndSet(null)?.cancel()
    }

    /**
     * Aborts the in-flight background generation and suspends until it has fully
     * stopped. Interactive turns use this so an in-flight utility generation is
     * guaranteed to have released the engine gate before the turn proceeds.
     */
    suspend fun cancelAndJoin() {
        activeJob.getAndSet(null)?.cancelAndJoin()
    }

    fun isAvailable(): Boolean =
        !isActive &&
            inferenceEngine.loadedModelPath != null &&
            inferenceEngine.state.value is InferenceState.Ready &&
            residencyState.value is ModelResidencyState.Ready

    /**
     * Generates a plain direct-answer completion for [prompt] (thinking is always
     * disabled regardless of the user's chat toggle). Returns the answer text, or
     * null when the engine is unavailable, the generation fails, is cancelled, or
     * produces no usable output.
     */
    suspend fun generateText(prompt: String): String? {
        if (prompt.isBlank() || !isAvailable()) return null
        // Barrier check before touching the engine: once an interactive turn has
        // begun, utility generation must not start at all.
        if (interactiveUseActive()) return null
        return try {
            coroutineScope {
                // coroutineScope creates a summarizer-owned CHILD job of the
                // caller's job; registering the child (not the caller's job) means
                // cancel()/cancelAndJoin() abort only this generation and never the
                // enclosing worker coroutine.
                val job = coroutineContext[Job]
                if (!activeJob.compareAndSet(null, job)) {
                    null
                } else {
                    try {
                        generateUtilityText(prompt)
                    } finally {
                        activeJob.compareAndSet(job, null)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Our own cancel() lands here while the caller is still active: treat
            // it as a normal no-result yield. Parent cancellation must propagate.
            if (coroutineContext.isActive) null else throw cancelled
        } catch (_: Throwable) {
            // Best-effort by design: callers fall back to deterministic behavior.
            null
        }
    }

    private suspend fun CoroutineScope.generateUtilityText(prompt: String): String? {
        inferenceEngine.restoreSession(
            UTILITY_CONVERSATION_ID,
            emptyList(),
            SUMMARIZATION_SETTINGS,
        )
        // Re-check after the session restore and immediately before generate
        // acquires the engine gate: an interactive turn that started in between
        // must preempt this utility generation rather than race it behind the gate.
        if (interactiveUseActive()) return null
        val answer = StringBuilder()
        var completion: GenerationEvent.Completed? = null
        inferenceEngine.generate(
            UserTurn(UTILITY_CONVERSATION_ID, prompt),
            SUMMARIZATION_SETTINGS,
            InferenceExecutionProfile.UTILITY,
        ).collect { event ->
            when (event) {
                is GenerationEvent.AnswerDelta -> answer.append(event.text)
                is GenerationEvent.Completed -> completion = event
                else -> Unit
            }
        }
        val reason = completion?.reason
        return if (reason == GenerationStopReason.EOG || reason == GenerationStopReason.TOKEN_LIMIT) {
            answer.toString().trim().takeIf(String::isNotBlank)
        } else {
            null
        }
    }

    companion object {
        /**
         * Pseudo conversation for utility generations. It never matches a real chat
         * session, so the next interactive turn always performs a full restore and
         * the chat KV cache is never polluted by background work.
         */
        const val UTILITY_CONVERSATION_ID = "utility:summarization"

        /**
         * Fixed extraction configuration: bounded input/output, low temperature, and
         * thinking disabled so summarization stays a plain direct-answer task no
         * matter the user's thinking toggle. Chat generation settings are untouched.
         */
        val SUMMARIZATION_SETTINGS = GenerationSettings(
            maxNewTokens = 512,
            maxAnswerTokens = 1_024,
            temperature = 0.2f,
            thinkingEnabled = false,
            systemPrompt =
                "You distill raw records into dense factual summaries about the user: " +
                    "preferences, facts, decisions, plans. Preserve specifics such as " +
                    "names, numbers, and dates. No commentary, no preamble.",
        ).normalized()
    }
}
