package com.aliahad.aichat.speech

import com.aliahad.aichat.core.SpeechAssetKind
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.VoiceSessionState
import com.aliahad.aichat.core.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class VoiceConversationController(
    private val scope: CoroutineScope,
    private val assets: SpeechAssetRepository,
    private val recognizer: StreamingSpeechRecognizer,
    private val synthesizer: IncrementalSpeechSynthesizer,
) {
    private val _state = MutableStateFlow<VoiceSessionState>(
        VoiceSessionState.Unavailable("Download both speech models in Settings"),
    )
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private val _finalTranscripts = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val finalTranscripts: SharedFlow<String> = _finalTranscripts.asSharedFlow()

    private var recognitionJob: Job? = null
    private var speechAssetsReady = false

    init {
        scope.launch {
            assets.assets.collectLatest { records ->
                speechAssetsReady = SpeechAssetKind.entries.all { kind ->
                    records.any {
                        it.kind == kind &&
                            it.status == DownloadStatus.READY &&
                            it.localPath != null
                    }
                }
                if (!speechAssetsReady) {
                    cancelAll()
                    _state.value = VoiceSessionState.Unavailable(
                        "Download both speech models in Settings",
                    )
                } else if (_state.value is VoiceSessionState.Unavailable) {
                    _state.value = VoiceSessionState.Idle
                }
            }
        }
        scope.launch {
            synthesizer.state.collectLatest { output ->
                when (output) {
                    SpeechOutputState.Idle -> {
                        if (speechAssetsReady && recognitionJob?.isActive != true) {
                            val current = _state.value
                            if (
                                current == VoiceSessionState.Waiting ||
                                current == VoiceSessionState.Speaking ||
                                current is VoiceSessionState.Loading &&
                                current.label == "Loading voice"
                            ) {
                                _state.value = VoiceSessionState.Idle
                            }
                        }
                    }
                    SpeechOutputState.Loading -> _state.value = VoiceSessionState.Loading("Loading voice")
                    SpeechOutputState.Waiting -> _state.value = VoiceSessionState.Waiting
                    SpeechOutputState.Speaking -> _state.value = VoiceSessionState.Speaking
                    is SpeechOutputState.Error -> _state.value = VoiceSessionState.Error(output.message)
                }
            }
        }
    }

    fun startListening() {
        val previousRecognition = recognitionJob
        cancelAll()
        recognitionJob = scope.launch {
            previousRecognition?.cancelAndJoin()
            val directory = assets.readyDirectory(SpeechAssetKind.ASR)
            if (directory == null) {
                _state.value = VoiceSessionState.Unavailable("Download the speech recognition model")
                return@launch
            }
            speechAssetsReady = true
            _state.value = VoiceSessionState.Loading("Loading speech recognition")
            runCatching {
                recognizer.recognize(directory).collect { event ->
                    when (event) {
                        TranscriptionEvent.Ready ->
                            _state.value = VoiceSessionState.Listening()
                        is TranscriptionEvent.Partial ->
                            _state.value = VoiceSessionState.Listening(event.text)
                        is TranscriptionEvent.Final -> {
                            val transcript = event.text.trim()
                            if (transcript.isEmpty()) {
                                _state.value = VoiceSessionState.Error(
                                    if (event.audioDetected) {
                                        "Speech was heard but not recognized. Speak English clearly and try again."
                                    } else {
                                        "Microphone audio is too quiet. Move closer and try again."
                                    },
                                )
                            } else {
                                _state.value = VoiceSessionState.Finalizing(transcript)
                                _finalTranscripts.emit(transcript)
                            }
                        }
                    }
                }
            }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) {
                    _state.value = VoiceSessionState.Error(
                        error.message ?: "Speech recognition failed",
                    )
                }
            }
        }
    }

    fun stopListening() {
        recognizer.stop()
    }

    fun reportError(message: String) {
        cancelAll()
        _state.value = VoiceSessionState.Error(message)
    }

    suspend fun beginSpokenReply(settings: VoiceSettings): Boolean {
        val directory = assets.readyDirectory(SpeechAssetKind.TTS)
        if (directory == null) {
            _state.value = VoiceSessionState.Unavailable("Download the text-to-speech model")
            return false
        }
        speechAssetsReady = true
        return runCatching {
            synthesizer.begin(directory, settings)
            true
        }.getOrElse {
            _state.value = VoiceSessionState.Error(it.message ?: "Text-to-speech failed")
            false
        }
    }

    suspend fun acceptAnswerDelta(delta: String) {
        runCatching { synthesizer.accept(delta) }
            .onFailure { _state.value = VoiceSessionState.Error(it.message ?: "Text-to-speech failed") }
    }

    suspend fun finishSpokenReply() {
        runCatching { synthesizer.complete() }
            .onFailure { _state.value = VoiceSessionState.Error(it.message ?: "Text-to-speech failed") }
    }

    suspend fun replay(text: String, settings: VoiceSettings) {
        val directory = assets.readyDirectory(SpeechAssetKind.TTS)
        if (directory == null) {
            _state.value = VoiceSessionState.Unavailable("Download the text-to-speech model")
            return
        }
        runCatching { synthesizer.replay(text, directory, settings) }
            .onFailure { _state.value = VoiceSessionState.Error(it.message ?: "Text-to-speech failed") }
    }

    fun cancelAll() {
        recognitionJob?.cancel()
        recognitionJob = null
        recognizer.cancel()
        synthesizer.cancel()
        _state.value = if (speechAssetsReady) {
            VoiceSessionState.Idle
        } else {
            VoiceSessionState.Unavailable("Download both speech models in Settings")
        }
    }

    suspend fun releaseAsset(kind: SpeechAssetKind) {
        val activeRecognition = recognitionJob
        cancelAll()
        activeRecognition?.cancelAndJoin()
        when (kind) {
            SpeechAssetKind.ASR -> recognizer.release()
            SpeechAssetKind.TTS -> synthesizer.release()
        }
    }

    suspend fun release() {
        val activeRecognition = recognitionJob
        cancelAll()
        activeRecognition?.cancelAndJoin()
        recognizer.release()
        synthesizer.release()
    }
}
