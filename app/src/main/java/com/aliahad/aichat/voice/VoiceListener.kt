package com.aliahad.aichat.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.time.TimeSource

/** What the microphone half of a call reports upwards. */
sealed interface HeardSpeech {
    /** The user is talking; [text] grows as they go and may still change. */
    data class Partial(val text: String) : HeardSpeech

    /**
     * The recogniser's endpoint fired: the user has stopped and this is the turn.
     * Blank finals are never emitted — a cough should not send an empty message.
     */
    data class Final(val text: String) : HeardSpeech

    /** Speech started or stopped, for the call screen's listening animation. */
    data class Activity(val speaking: Boolean) : HeardSpeech
}

/**
 * Microphone -> Silero VAD -> streaming Zipformer, as a cold [Flow].
 *
 * The VAD gates the recogniser rather than duplicating it: transcribing silence
 * would burn CPU on the same cores the 4.8 GB chat model is using to answer, which
 * is precisely when a call can least afford it. The recogniser's own endpoint
 * decides the turn is over, because it knows about trailing silence *after words*
 * in a way a raw energy gate does not.
 *
 * Collecting starts the microphone; cancelling the collection stops and releases
 * it. Nothing here retains audio: samples go to the recognisers and are dropped.
 */
class VoiceListener(
    private val modelsDirectory: () -> File,
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    /** True when every recogniser artifact is present. */
    fun isInstalled(): Boolean =
        modelFile(VAD_FILE).isFile &&
            File(File(modelsDirectory(), WHISPER_DIRECTORY), "base.en-tokens.txt").isFile

    /**
     * Requires `RECORD_AUDIO`; the caller is responsible for holding it, which is
     * why this is annotated rather than checking — a call screen that opened
     * without the permission is a bug, not a runtime condition to paper over.
     */
    @SuppressLint("MissingPermission")
    fun listen(): Flow<HeardSpeech> = channelFlow {
        val vad = createVad()
        val recognizer = createRecognizer()

        // Capture and recognition are deliberately separated.
        //
        // They used to share one loop: read 32 ms, run the VAD, run the recogniser,
        // repeat. Whenever a decode took longer than the window — which is most of
        // the time while the 4.8 GB chat model is answering on the same cores — the
        // microphone's buffer overflowed and audio was dropped mid-word. That does
        // not fail loudly, it just garbles the transcript: "Hello can you hear me"
        // came back as "HALLO GANUI YERE ME". A dedicated reader plus a two-second
        // queue means recognition can fall behind and catch up without losing audio.
        val audio = Channel<FloatArray>(capacity = QUEUE_WINDOWS)
        var dropped = 0L

        val capture = launch(Dispatchers.IO) {
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val recorder = AudioRecord(
                // Plain MIC. VOICE_RECOGNITION is the "right" source on paper, but on
                // this HyperOS device it opens successfully and then delivers a flat
                // ±5 LSB floor with someone speaking into it.
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // Two seconds, not the ~128 ms the minimum works out to here. This is
                // the slack that absorbs a slow decode instead of dropping audio.
                maxOf(minimumBuffer, SAMPLE_RATE * Short.SIZE_BYTES * 2),
            )
            val pcm = ShortArray(WINDOW_SAMPLES)
            try {
                recorder.startRecording()
                Log.i(TAG, "Listening at ${recorder.sampleRate} Hz, state=${recorder.state}")
                while (isActive) {
                    val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    val samples = FloatArray(read) { pcm[it] / Short.MAX_VALUE.toFloat() }
                    if (audio.trySend(samples).isFailure) {
                        dropped++
                        if (dropped % LOG_EVERY == 1L) {
                            Log.w(TAG, "Recognition is behind; dropped $dropped windows")
                        }
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                audio.close()
            }
        }

        var wasSpeaking = false
        var windows = 0L
        var loudestSoFar = 0f
        try {
            for (samples in audio) {
                vad.acceptWaveform(samples)
                val speaking = vad.isSpeechDetected()
                if (speaking != wasSpeaking) {
                    wasSpeaking = speaking
                    send(HeardSpeech.Activity(speaking))
                }

                var peak = 0f
                for (sample in samples) peak = maxOf(peak, kotlin.math.abs(sample))
                loudestSoFar = maxOf(loudestSoFar, peak)
                if (windows % LOG_EVERY == 0L) {
                    Log.i(
                        TAG,
                        "window $windows peak=$peak loudest=$loudestSoFar " +
                            "vadSpeech=$speaking dropped=$dropped",
                    )
                }
                if (windows == SILENCE_CHECK_WINDOWS && loudestSoFar < AUDIBLE_FLOOR) {
                    Log.w(TAG, "Microphone is open but silent after 5 s")
                }
                windows++

                // Whisper is not streaming, so the VAD now owns turn-taking: it hands
                // over a complete utterance once the user has been quiet for
                // `minSilenceDuration`, and that whole segment is transcribed at once.
                // The streaming recogniser this replaced gave live partials but was
                // measured producing "HALLO KA NU YERI" for "hello can you hear me".
                while (!vad.empty()) {
                    val segment = vad.front()
                    vad.pop()
                    val seconds = segment.samples.size.toFloat() / SAMPLE_RATE
                    if (seconds < MIN_UTTERANCE_SECONDS) {
                        Log.i(TAG, "Ignoring a ${seconds}s blip")
                        continue
                    }
                    val mark = TimeSource.Monotonic.markNow()
                    val stream = recognizer.createStream()
                    stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                    recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text.trim()
                    stream.release()
                    Log.i(
                        TAG,
                        "Transcribed ${seconds}s in ${mark.elapsedNow().inWholeMilliseconds}ms, " +
                            "${text.length} chars, dropped=$dropped",
                    )
                    if (text.isNotBlank()) send(HeardSpeech.Final(text))
                }
            }
        } finally {
            capture.cancel()
            runCatching { recognizer.release() }
            runCatching { vad.release() }
            Log.i(TAG, "Stopped listening, dropped=$dropped windows")
        }
    }.flowOn(dispatcher)

    private fun createVad(): Vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelFile(VAD_FILE).absolutePath,
                threshold = 0.5f,
                // The VAD decides when a turn is over now, so this is end-of-turn
                // silence, not a denoising parameter. 0.25 s cut people off mid-sentence.
                minSilenceDuration = 0.6f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW_SAMPLES,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private fun createRecognizer(): OfflineRecognizer {
        val directory = File(modelsDirectory(), WHISPER_DIRECTORY)
        check(directory.isDirectory) { "The English speech pack is not installed" }
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(directory, "base.en-encoder.int8.onnx").absolutePath,
                        decoder = File(directory, "base.en-decoder.int8.onnx").absolutePath,
                    ),
                    tokens = File(directory, "base.en-tokens.txt").absolutePath,
                    // Two threads: the chat model owns the big cores, and this runs in
                    // the gap between the user stopping and generation starting.
                    numThreads = 2,
                    modelType = "whisper",
                ),
            ),
        )
    }

    private fun modelFile(name: String) = File(modelsDirectory(), name)

    private companion object {
        const val TAG = "AIchatVoice"

        /** Both the recogniser's feature extractor and Silero expect 16 kHz. */
        const val SAMPLE_RATE = 16_000

        /** 512 samples = 32 ms, the window size Silero v5 is trained for. */
        const val WINDOW_SAMPLES = 512

        /** ~1 s at 32 ms a window; enough to see life without flooding logcat. */
        const val LOG_EVERY = 31L

        /** Roughly 5 s of audio before deciding the microphone is not delivering. */
        const val SILENCE_CHECK_WINDOWS = 155L

        /** Below this a signal is an ADC noise floor, not a room with a person in it. */
        const val AUDIBLE_FLOOR = 0.01f

        /** ~2 s of 32 ms windows: how far recognition may fall behind without loss. */
        const val QUEUE_WINDOWS = 64

        /** Shorter than this is a cough or a door, not a turn worth sending to the model. */
        const val MIN_UTTERANCE_SECONDS = 0.4f

        val VAD_FILE: String = com.aliahad.aichat.model.ModelConstants.SILERO_VAD.fileName
        val WHISPER_DIRECTORY: String =
            requireNotNull(com.aliahad.aichat.model.ModelConstants.WHISPER_BASE_EN.archiveRootDirectory)
    }
}
