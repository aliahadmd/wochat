package com.aliahad.aichat.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

sealed interface TranscriptionEvent {
    data object Ready : TranscriptionEvent
    data class Partial(val text: String) : TranscriptionEvent
    data class Final(
        val text: String,
        val audioDetected: Boolean = text.isNotBlank(),
    ) : TranscriptionEvent
}

interface StreamingSpeechRecognizer {
    fun recognize(modelDirectory: File): Flow<TranscriptionEvent>
    fun stop()
    fun cancel()
    suspend fun release()
}

class SherpaStreamingSpeechRecognizer : StreamingSpeechRecognizer {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    @Volatile private var audioRecord: AudioRecord? = null
    private var recognizer: OnlineRecognizer? = null
    private var loadedPath: String? = null

    @SuppressLint("MissingPermission")
    override fun recognize(modelDirectory: File): Flow<TranscriptionEvent> = flow {
        stopRequested = false
        cancelled = false
        val engine = ensureRecognizer(modelDirectory)
        val stream = engine.createStream()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "This device cannot open a 16 kHz microphone stream" }
        val preferred = buildAudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            minBuffer,
        )
        val recorder = if (preferred.state == AudioRecord.STATE_INITIALIZED) {
            preferred
        } else {
            preferred.release()
            buildAudioRecord(MediaRecorder.AudioSource.MIC, minBuffer)
        }
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize the microphone" }
        audioRecord = recorder
        Log.i(
            TAG,
            "ASR microphone source=${recorder.audioSource} session=${recorder.audioSessionId}",
        )
        var latest = ""
        val signalTracker = PcmSignalTracker()
        try {
            recorder.startRecording()
            require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "The microphone did not start recording"
            }
            emit(TranscriptionEvent.Ready)
            val pcm = ShortArray(READ_SAMPLES)
            while (!cancelled && !stopRequested) {
                currentCoroutineContext().ensureActive()
                val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read < 0 && (stopRequested || cancelled)) break
                if (read < 0) error("Microphone read failed: $read")
                if (read == 0) continue
                signalTracker.add(pcm, read)
                val samples = FloatArray(read) { index -> pcm[index] / 32768.0f }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (engine.isReady(stream)) engine.decode(stream)
                val text = engine.getResult(stream).text.trim()
                if (text != latest) {
                    latest = text
                    emit(TranscriptionEvent.Partial(text))
                }
                if (engine.isEndpoint(stream)) break
            }
            if (!cancelled) {
                stream.inputFinished()
                while (engine.isReady(stream)) engine.decode(stream)
                latest = engine.getResult(stream).text.trim()
                val signal = signalTracker.summary()
                Log.i(
                    TAG,
                    "ASR finished: source=${recorder.audioSource} peak=${signal.peakAmplitude} " +
                        "rms=${"%.5f".format(signal.rmsNormalized)} textLength=${latest.length}",
                )
                emit(TranscriptionEvent.Final(latest, signal.hasAudibleSignal))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            if (audioRecord === recorder) audioRecord = null
            runCatching { recorder.stop() }
            recorder.release()
            stream.release()
        }
    }.flowOn(dispatcher)

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(audioSource: Int, minBuffer: Int): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer * 2, READ_SAMPLES * Short.SIZE_BYTES * 4))
            .build()

    override fun stop() {
        stopRequested = true
        runCatching { audioRecord?.stop() }
    }

    override fun cancel() {
        cancelled = true
        runCatching { audioRecord?.stop() }
    }

    override suspend fun release() {
        cancel()
        withContext(dispatcher) {
            recognizer?.release()
            recognizer = null
            loadedPath = null
        }
    }

    private fun ensureRecognizer(directory: File): OnlineRecognizer {
        if (loadedPath == directory.absolutePath) return requireNotNull(recognizer)
        recognizer?.release()
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(directory, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                    decoder = File(directory, "decoder-epoch-99-avg-1.onnx").absolutePath,
                    joiner = File(directory, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
                ),
                tokens = File(directory, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 8.0f, 0f),
                rule2 = EndpointRule(true, 1.0f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
        )
        return OnlineRecognizer(null, config).also {
            recognizer = it
            loadedPath = directory.absolutePath
        }
    }

    private companion object {
        const val TAG = "AichatSpeech"
        const val SAMPLE_RATE = 16_000
        const val READ_SAMPLES = 1_600
    }
}
