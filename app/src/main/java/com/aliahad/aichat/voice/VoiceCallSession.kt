package com.aliahad.aichat.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where a call currently is. The call screen animates on exactly this. */
enum class VoiceCallPhase { IDLE, LISTENING, THINKING, SPEAKING }

data class VoiceCallState(
    val phase: VoiceCallPhase = VoiceCallPhase.IDLE,
    /** What the user is saying right now, replaced on each turn. */
    val heard: String = "",
    /** What the assistant is saying right now. */
    val spoken: String = "",
    val error: String? = null,
)

/**
 * One call: microphone -> chat turn -> voice, looping until hung up.
 *
 * **Half-duplex on purpose** (plan 036 Step 6). The microphone is stopped while the
 * phone speaks, because with it open the recogniser transcribes the app's own
 * voice and answers itself; `AcousticEchoCanceler` is not dependable enough across
 * devices to bet the feature on. Interrupting is a tap, which cancels generation
 * and playback and returns to listening.
 *
 * Dependencies arrive as lambdas rather than repositories so the loop can be
 * reasoned about — and tested — without a database, a 4.8 GB model or a microphone.
 */
class VoiceCallSession(
    private val listener: VoiceListener,
    private val speaker: VoiceSpeaker,
    private val scope: CoroutineScope,
    /** Starts a turn. Returns false when one is already running. */
    private val sendTurn: (String) -> Boolean,
    /** The assistant's answer as it streams, completing when generation ends. */
    private val answers: () -> Flow<AnswerProgress>,
    private val cancelTurn: () -> Unit,
) {
    private val _state = MutableStateFlow(VoiceCallState())
    val state: StateFlow<VoiceCallState> = _state.asStateFlow()

    private var callJob: Job? = null

    val isActive: Boolean get() = callJob?.isActive == true

    fun start() {
        if (isActive) return
        callJob = scope.launch { run() }
    }

    /** Surfaces a reason the call could not start, rather than sitting on "Starting". */
    fun reportUnavailable(reason: String) {
        _state.value = VoiceCallState(phase = VoiceCallPhase.IDLE, error = reason)
    }

    fun hangUp() {
        cancelTurn()
        speaker.stop()
        callJob?.cancel()
        callJob = null
        _state.value = VoiceCallState()
    }

    /**
     * Tap-to-interrupt: stop talking, stop generating, listen again. Deliberately
     * does not end the call — interrupting is a normal part of a conversation.
     */
    fun interrupt() {
        if (!isActive) return
        cancelTurn()
        speaker.stop()
        _state.update { it.copy(phase = VoiceCallPhase.LISTENING, spoken = "") }
    }

    private suspend fun run() {
        while (scope.isActive && currentJobActive()) {
            val utterance = listenForUtterance() ?: continue
            if (!sendTurn(utterance)) {
                Log.i(TAG, "A turn was already running; dropping this utterance")
                continue
            }
            speakAnswer()
        }
    }

    /** Collects until the recogniser's endpoint produces a non-blank final. */
    private suspend fun listenForUtterance(): String? {
        _state.update { it.copy(phase = VoiceCallPhase.LISTENING, heard = "", spoken = "") }
        var finalText: String? = null
        listener.listen().collectUntil { heard ->
            when (heard) {
                is HeardSpeech.Partial -> {
                    _state.update { it.copy(heard = heard.text) }
                    false
                }
                is HeardSpeech.Final -> {
                    _state.update { it.copy(heard = heard.text) }
                    finalText = heard.text
                    true
                }
                is HeardSpeech.Activity -> false
            }
        }
        return finalText
    }

    /** Speaks each sentence as generation produces it, then returns to listening. */
    private suspend fun speakAnswer() {
        _state.update { it.copy(phase = VoiceCallPhase.THINKING) }
        val segmenter = SentenceSegmenter()
        var lastAnswer = ""
        answers().collectUntil { progress ->
            lastAnswer = progress.text
            segmenter.accept(progress.text).forEach { sentence -> say(sentence) }
            if (progress.complete) {
                segmenter.flush(lastAnswer)?.let { say(it) }
            }
            progress.complete
        }
    }

    private suspend fun say(sentence: String) {
        _state.update {
            it.copy(phase = VoiceCallPhase.SPEAKING, spoken = (it.spoken + " " + sentence).trim())
        }
        speaker.speak(sentence).onFailure { failure ->
            Log.e(TAG, "Could not speak a sentence", failure)
            _state.update { it.copy(error = failure.message) }
        }
    }

    private fun currentJobActive() = callJob?.isActive != false

    private companion object {
        const val TAG = "AIchatVoice"
    }
}

/** A snapshot of the streaming answer: the text so far, and whether it is finished. */
data class AnswerProgress(val text: String, val complete: Boolean)

/**
 * Collects until [predicate] returns true, then stops.
 *
 * Each value must be *processed* — a partial transcript updates the UI, a sentence
 * gets spoken — and only then does the last one decide whether to stop, which is
 * why this is not `first { }`.
 */
private suspend fun <T> Flow<T>.collectUntil(predicate: suspend (T) -> Boolean) {
    try {
        collect { value -> if (predicate(value)) throw StopCollecting }
    } catch (stop: Throwable) {
        if (stop !== StopCollecting) throw stop
    }
}

/** Stackless: this is control flow, not an error, and it is thrown once per turn. */
private object StopCollecting : Throwable(null, null, false, false)
