package com.aliahad.aichat.ui.viewmodel

import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.TurnOrigin
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.voice.AnswerProgress
import com.aliahad.aichat.voice.VoiceCallSession
import com.aliahad.aichat.voice.VoiceCallPhase
import com.aliahad.aichat.voice.VoiceCallState
import com.aliahad.aichat.voice.VoiceListener
import com.aliahad.aichat.voice.VoiceSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Binds a [VoiceCallSession] to this app's chat plumbing.
 *
 * Kept out of `ChatViewModel` on purpose: the ViewModel is already the largest
 * class in the UI layer, and none of this is about screen state — it is about
 * turning a spoken utterance into the same `ChatTurnRunner` turn a typed message
 * produces, so a call and a conversation are the *same* conversation.
 */
class VoiceCallCoordinator(
    private val chatRepository: ChatRepository,
    private val runner: ChatTurnRunner,
    private val listener: VoiceListener,
    private val speaker: VoiceSpeaker,
    scope: CoroutineScope,
    private val conversationId: () -> String?,
    private val memoryEnabled: () -> Boolean,
    /**
     * Holds and releases the microphone foreground service around a call.
     *
     * Lambdas rather than a Context so this stays testable, and because the only
     * thing the coordinator needs to know is that a call has begun or ended.
     */
    private val holdMicrophone: (onHangUp: () -> Unit) -> Unit = {},
    private val releaseMicrophone: () -> Unit = {},
) {
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available

    private val session = VoiceCallSession(
        listener = listener,
        speaker = speaker,
        scope = scope,
        sendTurn = ::sendSpokenTurn,
        answers = ::answerProgress,
        cancelTurn = runner::cancelTurn,
    )

    val state: StateFlow<VoiceCallState> = session.state

    init {
        // Release on *any* return to idle, not just an explicit hang-up. A call can
        // also end by error or by the pack being unavailable, and a microphone
        // service left running after that would sit in the notification shade
        // claiming to listen.
        scope.launch {
            session.state
                .map { it.phase == VoiceCallPhase.IDLE }
                .distinctUntilChanged()
                .collect { idle -> if (idle) releaseMicrophone() }
        }
    }

    /**
     * True once every part of the speech pack is on disk.
     *
     * Checked afresh each time, deliberately. This was cached in a `val` at
     * construction, so a pack that finished downloading *after* the ViewModel was
     * created stayed invisible until the app restarted — and the call screen just
     * sat on "Starting" forever with nothing to explain it.
     */
    fun refreshAvailability() {
        _available.value = listener.isInstalled() && speaker.isInstalled()
    }


    fun start() {
        refreshAvailability()
        if (_available.value) {
            // Before the session, not after: Android hands a backgrounded app silence
            // instead of an error, so the microphone has to be held from the first
            // window rather than from whenever the screen happens to lock.
            holdMicrophone { hangUp() }
            session.start()
        } else {
            // Never fail silently: an unavailable pack used to look identical to a
            // call that was simply slow to connect.
            session.reportUnavailable(
                "The English speech pack is not installed yet. Download it in Settings > Models.",
            )
        }
    }

    fun hangUp() {
        session.hangUp()
        releaseMicrophone()
    }

    fun interrupt() = session.interrupt()

    private fun sendSpokenTurn(text: String): Boolean {
        val id = conversationId() ?: return false
        return runner.launchTurn(
            SendTurnRequest(
                conversationId = id,
                text = text,
                attachments = emptyList(),
                selectedSkillIds = emptyList(),
                memoryEnabled = memoryEnabled(),
                conversationTemporary = false,
                // Marks the row as spoken, which is what plan 037 needs before it can
                // give voice turns a cheaper prompt than typed ones.
                origin = TurnOrigin.VOICE,
                onDraftCommitted = {},
            ),
        )
    }

    /**
     * The assistant's reply as it streams.
     *
     * Read from the database rather than tapped off the generator: the runner
     * already persists every ~250 ms, a call turn takes seconds, and coupling the
     * speaker to the turn loop would mean two consumers of one stream. Completion
     * is the message leaving STREAMING, which also covers cancellation and errors —
     * so an interrupted turn ends the speaking phase instead of hanging the call.
     */
    private fun answerProgress(): Flow<AnswerProgress> {
        val id = conversationId() ?: return flowOf(AnswerProgress(text = "", complete = true))
        return combine(chatRepository.messages(id), runner.state) { messages, run ->
            val assistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            StreamingAnswer(
                text = assistant?.content.orEmpty(),
                streaming = isStreaming(assistant?.status, run.isSending),
            )
        }
            // `launchTurn` is asynchronous, so at this moment the database still holds
            // the *previous* turn's finished answer. Without this the speaking phase
            // would see complete=true immediately and end before a word was said.
            .dropWhile { !it.streaming }
            .map { AnswerProgress(text = it.text, complete = !it.streaming) }
            .distinctUntilChanged()
    }


    private data class StreamingAnswer(val text: String, val streaming: Boolean)
}

/**
 * Whether the answer is still being written.
 *
 * Keyed on the message row, not on the runner's `isSending`, and that is the whole
 * point. The runner writes the final content and the final status in one update, so
 * the row is the only place where "finished" and "the complete text" are atomic.
 * `isSending` flips in the runner's `finally` block and reaches the combine before
 * Room re-emits the row, which marked the turn complete while the text was still the
 * last ~250 ms snapshot. Measured: "Python is a high-level, versatile programming
 * language..." was stored with three sentences and spoken with two, the tail never
 * reaching the speaker because collection had already stopped.
 *
 * [running] is kept only as a stop for the case where no assistant row exists yet —
 * a turn that failed before creating one must not leave the call speaking forever.
 */
internal fun isStreaming(status: MessageStatus?, running: Boolean): Boolean =
    if (status == null) running else status == MessageStatus.STREAMING
