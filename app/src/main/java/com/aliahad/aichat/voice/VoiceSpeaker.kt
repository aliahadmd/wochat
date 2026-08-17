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
    }

    private fun voiceFile(name: String) = File(File(modelsDirectory(), VOICE_DIRECTORY), name)

    private companion object {
        const val TAG = "AIchatVoice"
        val VOICE_DIRECTORY: String = requireNotNull(ModelConstants.PIPER_VOICE_EN_US.archiveRootDirectory)
    }
}
