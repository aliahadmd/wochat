package com.aliahad.aichat

import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.SpeechAssetKind
import com.aliahad.aichat.core.SpeechAssetRecord
import com.aliahad.aichat.core.VoiceSessionState
import com.aliahad.aichat.core.VoiceSettings
import com.aliahad.aichat.speech.IncrementalSpeechSynthesizer
import com.aliahad.aichat.speech.SpeechAssetRepository
import com.aliahad.aichat.speech.SpeechOutputState
import com.aliahad.aichat.speech.StreamingSpeechRecognizer
import com.aliahad.aichat.speech.TranscriptionEvent
import com.aliahad.aichat.speech.VoiceConversationController
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceConversationControllerTest {
    @Test
    fun partialAndFinalRecognitionArePublished() = runTest {
        val recognizer = FakeRecognizer(
            flow {
                emit(TranscriptionEvent.Ready)
                emit(TranscriptionEvent.Partial("hello"))
                delay(100)
                emit(TranscriptionEvent.Final("hello world"))
            },
        )
        val controller = controller(recognizer, FakeSynthesizer())
        val final = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            controller.finalTranscripts.first()
        }

        controller.startListening()
        runCurrent()
        assertEquals(VoiceSessionState.Listening("hello"), controller.state.value)
        advanceUntilIdle()

        assertEquals("hello world", final.await())
    }

    @Test
    fun recordingIsListeningBeforeTheFirstRecognizedWord() = runTest {
        val controller = controller(
            FakeRecognizer(
                flow {
                    emit(TranscriptionEvent.Ready)
                    delay(1_000)
                    emit(TranscriptionEvent.Final("hello"))
                },
            ),
            FakeSynthesizer(),
        )

        controller.startListening()
        runCurrent()

        assertEquals(VoiceSessionState.Listening(), controller.state.value)
    }

    @Test
    fun emptySpeechDoesNotSendATurnAndReportsTheProblem() = runTest {
        val controller = controller(
            FakeRecognizer(
                flow {
                    emit(TranscriptionEvent.Ready)
                    emit(TranscriptionEvent.Final("", audioDetected = false))
                },
            ),
            FakeSynthesizer(),
        )
        var sent = false
        backgroundScope.launch {
            controller.finalTranscripts.collect { sent = true }
        }

        controller.startListening()
        runCurrent()
        advanceUntilIdle()

        assertFalse(sent)
        assertEquals(
            VoiceSessionState.Error("Microphone audio is too quiet. Move closer and try again."),
            controller.state.value,
        )
    }

    @Test
    fun audibleUnrecognizedSpeechReportsDecoderFailure() = runTest {
        val controller = controller(
            FakeRecognizer(
                flow {
                    emit(TranscriptionEvent.Ready)
                    emit(TranscriptionEvent.Final("", audioDetected = true))
                },
            ),
            FakeSynthesizer(),
        )

        controller.startListening()
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            VoiceSessionState.Error(
                "Speech was heard but not recognized. Speak English clearly and try again.",
            ),
            controller.state.value,
        )
    }

    @Test
    fun interruptionClearsPendingSpeechBeforeNewRecording() = runTest {
        val recognizer = FakeRecognizer(flow { delay(10_000) })
        val synthesizer = FakeSynthesizer()
        val controller = controller(recognizer, synthesizer)

        assertTrue(controller.beginSpokenReply(VoiceSettings()))
        controller.acceptAnswerDelta("Old response.")
        controller.cancelAll()
        controller.startListening()
        advanceTimeBy(1)

        assertTrue(synthesizer.cancelCount > 0)
        assertTrue(synthesizer.accepted.isEmpty())
        assertTrue(recognizer.cancelCount > 0)
    }

    @Test
    fun answerDeltaReachesSpeechBeforeCompletion() = runTest {
        val synthesizer = FakeSynthesizer()
        val controller = controller(
            FakeRecognizer(flow { emit(TranscriptionEvent.Final("question")) }),
            synthesizer,
        )

        assertTrue(controller.beginSpokenReply(VoiceSettings()))
        controller.acceptAnswerDelta("First clause. ")

        assertEquals(listOf("First clause. "), synthesizer.accepted)
        assertEquals(0, synthesizer.completeCount)
        controller.finishSpokenReply()
        assertEquals(1, synthesizer.completeCount)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        recognizer: FakeRecognizer,
        synthesizer: FakeSynthesizer,
    ): VoiceConversationController = VoiceConversationController(
        scope = backgroundScope,
        assets = FakeSpeechAssets(),
        recognizer = recognizer,
        synthesizer = synthesizer,
    ).also { advanceUntilIdle() }
}

private class FakeSpeechAssets : SpeechAssetRepository {
    private val root = File(System.getProperty("java.io.tmpdir"), "aichat-speech-test").apply { mkdirs() }
    private val records = SpeechAssetKind.entries.map { kind ->
        SpeechAssetRecord(
            id = kind.name,
            kind = kind,
            displayName = kind.name,
            archiveFileName = kind.name,
            localPath = File(root, kind.name).apply { mkdirs() }.absolutePath,
            sourceUrl = "",
            expectedBytes = 1,
            sha256 = "",
            downloadedBytes = 1,
            status = DownloadStatus.READY,
            error = null,
        )
    }

    override val assets: Flow<List<SpeechAssetRecord>> = MutableStateFlow(records)
    override fun speechDirectory(): File = root
    override suspend fun ensureOfficialRecords() = Unit
    override suspend fun startDownload(id: String) = Unit
    override suspend fun pauseDownload(id: String) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun readyDirectory(kind: SpeechAssetKind): File =
        File(requireNotNull(records.first { it.kind == kind }.localPath))
}

private class FakeRecognizer(
    private val events: Flow<TranscriptionEvent>,
) : StreamingSpeechRecognizer {
    var cancelCount = 0

    override fun recognize(modelDirectory: File): Flow<TranscriptionEvent> = events
    override fun stop() = Unit
    override fun cancel() {
        cancelCount++
    }
    override suspend fun release() = Unit
}

private class FakeSynthesizer : IncrementalSpeechSynthesizer {
    override val state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Idle)
    val accepted = mutableListOf<String>()
    var cancelCount = 0
    var completeCount = 0

    override suspend fun begin(modelDirectory: File, settings: VoiceSettings) {
        state.value = SpeechOutputState.Waiting
    }

    override suspend fun accept(delta: String) {
        accepted += delta
    }

    override suspend fun complete() {
        completeCount++
        state.value = SpeechOutputState.Idle
    }

    override fun cancel() {
        cancelCount++
        accepted.clear()
        state.value = SpeechOutputState.Idle
    }

    override suspend fun replay(text: String, modelDirectory: File, settings: VoiceSettings) {
        accepted += text
    }

    override suspend fun release() = Unit
}
