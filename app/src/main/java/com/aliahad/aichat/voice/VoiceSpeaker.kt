package com.aliahad.aichat.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.aliahad.aichat.model.ModelConstants
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.TimeSource

/**
 * Speaks text with Piper (VITS) through sherpa-onnx.
 *
 * Deliberately **not** on the inference engine's dispatcher. That one is
 * single-threaded so the chat model never re-enters itself, and queuing speech
 * behind a decode would make the voice stutter exactly when the model is busy
 * answering — which is the whole time, during a call.
 *
 * The engine is created lazily and kept: constructing it parses an 78 MB model, so
 * it must not happen once per sentence.
 */
class VoiceSpeaker(
    private val modelsDirectory: () -> File,
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private var engine: OfflineTts? = null
    private var track: AudioTrack? = null

    /**
     * Frames handed to the track since it was created or flushed.
     *
     * `playbackHeadPosition` counts cumulatively for the life of the track, so it
     * has to be compared against a cumulative total. Comparing it against a single
     * sentence's frame count — the first version of this — meant the head was
     * always already past it and the drain returned instantly, which let the
     * microphone reopen mid-sentence and transcribe the app's own voice.
     */
    private var framesWritten = 0L

    /** True when the extracted voice is present, i.e. call mode can speak. */
    fun isInstalled(): Boolean = voiceFile("en_US-libritts_r-medium.onnx").isFile &&
        voiceFile("tokens.txt").isFile &&
        voiceFile("espeak-ng-data").isDirectory

    /**
     * Synthesises [text] and plays it, returning how long synthesis took.
     *
     * Plan 036 budgets ~400 ms to first audio and expects RTF ~0.15; the returned
     * duration is what lets that be checked rather than assumed.
     */
    suspend fun speak(text: String, speakerId: Int = ModelConstants.DEFAULT_VOICE_SPEAKER_ID): Result<Long> =
        withContext(dispatcher) {
            runCatching {
                val tts = engine ?: create().also { engine = it }
                val mark = TimeSource.Monotonic.markNow()
                val audio = tts.generate(text = text, sid = speakerId, speed = 1.0f)
                val synthesisMillis = mark.elapsedNow().inWholeMilliseconds
                Log.i(
                    TAG,
                    "Synthesised ${audio.samples.size} samples at ${tts.sampleRate()} Hz " +
                        "in $synthesisMillis ms",
                )
                play(audio.samples, tts.sampleRate())
                synthesisMillis
            }.onFailure { Log.e(TAG, "Speech synthesis failed", it) }
        }

    /** Stops playback immediately. Call mode's tap-to-interrupt depends on this. */
    fun stop() {
        runCatching {
            track?.pause()
            track?.flush()
            // flush() resets the playback head, so the written total must reset with
            // it or every later drain compares against a stale, unreachable target.
            framesWritten = 0L
        }
    }

    fun release() {
        stop()
        runCatching { track?.release() }
        track = null
        runCatching { engine?.release() }
        engine = null
    }

    private fun create(): OfflineTts {
        val directory = File(modelsDirectory(), VOICE_DIRECTORY)
        check(directory.isDirectory) { "The English speech pack is not installed" }
        return OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(directory, "en_US-libritts_r-medium.onnx").absolutePath,
                        tokens = File(directory, "tokens.txt").absolutePath,
                        // Piper phonemises through espeak-ng; without this it produces silence.
                        dataDir = File(directory, "espeak-ng-data").absolutePath,
                    ),
                    // Two threads: the chat model is already using the big cores to
                    // generate the very answer being spoken.
                    numThreads = 2,
                ),
            ),
        )
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val player = track ?: AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                maxOf(
                    AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT,
                    ),
                    samples.size * Float.SIZE_BYTES,
                ),
            )
            .build()
            .also { track = it }
        if (player.playState != AudioTrack.PLAYSTATE_PLAYING) player.play()
        player.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        framesWritten += samples.size
        awaitPlayback(player)
    }

    /**
     * Blocks until the speaker has actually finished, not merely until the samples
     * were handed to it.
     *
     * `write` returns once the data is buffered, so without this the call resumes
     * listening while the tail of the answer is still audible — and the recogniser
     * transcribes the phone's own voice. Measured before the fix: "The capital of
     * France is Paris." came back as a new user turn "Harris.", whose answer echoed
     * as "information.", whose answer echoed as "on to." — the call talking to
     * itself. That loop is what half-duplex exists to prevent.
     */
    private fun awaitPlayback(player: AudioTrack) {
        val played = player.playbackHeadPosition.toLong().coerceAtLeast(0L)
        val remaining = framesWritten - played
        if (remaining <= 0L) return
        val deadline = System.nanoTime() +
            remaining * NANOS_PER_SECOND / player.sampleRate + DRAIN_GRACE_NANOS
        while (System.nanoTime() < deadline) {
            if (player.playbackHeadPosition.toLong() >= framesWritten) break
            Thread.sleep(PLAYBACK_POLL_MILLIS)
        }
        // The speaker keeps sounding for a moment after the last frame, and a room
        // adds its own tail. Reopening the microphone into that is what starts the
        // loop, so pay a fixed settle before listening again.
        Thread.sleep(SETTLE_MILLIS)
    }

    private fun voiceFile(name: String) = File(File(modelsDirectory(), VOICE_DIRECTORY), name)

    private companion object {
        const val TAG = "AIchatVoice"
        val VOICE_DIRECTORY: String = requireNotNull(ModelConstants.PIPER_VOICE_EN_US.archiveRootDirectory)

        const val NANOS_PER_SECOND = 1_000_000_000L

        /** Covers the speaker's own latency after the last frame is consumed. */
        const val DRAIN_GRACE_NANOS = 150_000_000L

        const val PLAYBACK_POLL_MILLIS = 10L

        /** Room tail and speaker decay, before the microphone is trusted again. */
        const val SETTLE_MILLIS = 300L
    }
}
