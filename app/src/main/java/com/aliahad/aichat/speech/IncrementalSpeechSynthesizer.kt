package com.aliahad.aichat.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.aliahad.aichat.core.VoiceSettings
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface SpeechOutputState {
    data object Idle : SpeechOutputState
    data object Loading : SpeechOutputState
    data object Waiting : SpeechOutputState
    data object Speaking : SpeechOutputState
    data class Error(val message: String) : SpeechOutputState
}

interface IncrementalSpeechSynthesizer {
    val state: StateFlow<SpeechOutputState>
    suspend fun begin(modelDirectory: File, settings: VoiceSettings)
    suspend fun accept(delta: String)
    suspend fun complete()
    fun cancel()
    suspend fun replay(text: String, modelDirectory: File, settings: VoiceSettings)
    suspend fun release()
}

class SherpaIncrementalSpeechSynthesizer(
    context: Context,
    private val scope: CoroutineScope,
) : IncrementalSpeechSynthesizer {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val synthesisDispatcher = Dispatchers.IO.limitedParallelism(1)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) cancel()
        }
        .build()

    private val _state = MutableStateFlow<SpeechOutputState>(SpeechOutputState.Idle)
    override val state: StateFlow<SpeechOutputState> = _state.asStateFlow()

    private var tts: OfflineTts? = null
    private var loadedPath: String? = null
    private var sessionJob: Job? = null
    private var textQueue: Channel<String>? = null
    private var chunker = SpeechChunker()
    private var voiceSettings = VoiceSettings()
    @Volatile private var audioTrack: AudioTrack? = null

    override suspend fun begin(modelDirectory: File, settings: VoiceSettings) {
        cancel()
        _state.value = SpeechOutputState.Loading
        ensureTts(modelDirectory)
        voiceSettings = settings.normalized()
        chunker = SpeechChunker()
        val clauses = Channel<String>(Channel.UNLIMITED)
        val pcm = Channel<GeneratedAudio>(capacity = 2)
        textQueue = clauses
        _state.value = SpeechOutputState.Waiting
        sessionJob = scope.launch {
            val synthesis = launch(synthesisDispatcher) {
                try {
                    for (clause in clauses) {
                        val audio = requireNotNull(tts).generate(
                            clause,
                            voiceSettings.speakerId,
                            voiceSettings.speed,
                        )
                        if (audio.samples.isNotEmpty()) pcm.send(audio)
                    }
                } finally {
                    pcm.close()
                }
            }
            val playback = launch(playbackDispatcher) {
                play(pcm)
            }
            synthesis.join()
            playback.join()
            if (_state.value !is SpeechOutputState.Error) {
                _state.value = SpeechOutputState.Idle
            }
        }.also { job ->
            job.invokeOnCompletion { error ->
                if (error != null && error !is CancellationException) {
                    _state.value = SpeechOutputState.Error(
                        error.message ?: "Speech playback failed",
                    )
                }
            }
        }
    }

    override suspend fun accept(delta: String) {
        val queue = textQueue ?: return
        chunker.accept(delta).forEach { queue.send(it) }
    }

    override suspend fun complete() {
        val queue = textQueue ?: return
        chunker.finish().forEach { queue.send(it) }
        queue.close()
        textQueue = null
    }

    override fun cancel() {
        textQueue?.cancel()
        textQueue = null
        sessionJob?.cancel()
        sessionJob = null
        chunker.reset()
        releaseAudioTrack()
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (_state.value !is SpeechOutputState.Error) _state.value = SpeechOutputState.Idle
    }

    override suspend fun replay(text: String, modelDirectory: File, settings: VoiceSettings) {
        begin(modelDirectory, settings)
        accept(text)
        complete()
    }

    override suspend fun release() {
        val activeSession = sessionJob
        cancel()
        activeSession?.cancelAndJoin()
        withContext(synthesisDispatcher) {
            tts?.release()
            tts = null
            loadedPath = null
        }
    }

    private suspend fun ensureTts(directory: File) = withContext(synthesisDispatcher) {
        if (loadedPath == directory.absolutePath && tts != null) return@withContext
        tts?.release()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = File(directory, "model.int8.onnx").absolutePath,
                    voices = File(directory, "voices.bin").absolutePath,
                    tokens = File(directory, "tokens.txt").absolutePath,
                    dataDir = File(directory, "espeak-ng-data").absolutePath,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        tts = OfflineTts(null, config)
        loadedPath = directory.absolutePath
    }

    private suspend fun play(queue: Channel<GeneratedAudio>) {
        var focusHeld = false
        try {
            for (audio in queue) {
                if (!focusHeld) {
                    require(
                        audioManager.requestAudioFocus(focusRequest) ==
                            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    ) { "Audio focus is unavailable" }
                    focusHeld = true
                }
                val track = audioTrack?.takeIf { it.sampleRate == audio.sampleRate }
                    ?: createAudioTrack(audio.sampleRate).also {
                        releaseAudioTrack()
                        audioTrack = it
                        it.play()
                    }
                _state.value = SpeechOutputState.Speaking
                var offset = 0
                var emptyWrites = 0
                while (offset < audio.samples.size) {
                    val written = track.write(
                        audio.samples,
                        offset,
                        audio.samples.size - offset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    require(written >= 0) { "AudioTrack write failed: $written" }
                    if (written == 0) {
                        emptyWrites++
                        require(emptyWrites <= MAX_EMPTY_WRITES) {
                            "AudioTrack stopped accepting speech audio"
                        }
                        delay(5)
                        continue
                    }
                    emptyWrites = 0
                    offset += written
                }
            }
        } finally {
            releaseAudioTrack()
            if (focusHeld) audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        require(minBuffer > 0) { "This device cannot open a float PCM speech stream" }
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * Float.SIZE_BYTES / 5))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun releaseAudioTrack() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        track.release()
    }

    private companion object {
        const val MAX_EMPTY_WRITES = 100
    }
}
