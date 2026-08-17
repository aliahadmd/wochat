package com.aliahad.aichat.ui.viewmodel

import com.aliahad.aichat.core.MessageRole
import com.aliahad.aichat.core.MessageStatus
import com.aliahad.aichat.core.TurnOrigin
import com.aliahad.aichat.data.ChatRepository
import com.aliahad.aichat.voice.AnswerProgress
import com.aliahad.aichat.voice.VoiceCallSession
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
            session.start()
        } else {
            // Never fail silently: an unavailable pack used to look identical to a
            // call that was simply slow to connect.
            session.reportUnavailable(
                "The English speech pack is not installed yet. Download it in Settings > Models.",
            )
        }
    }

    fun hangUp() = session.hangUp()

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
            val streaming = run.isSending && assistant?.status == MessageStatus.STREAMING
            StreamingAnswer(text = assistant?.content.orEmpty(), streaming = streaming)
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
