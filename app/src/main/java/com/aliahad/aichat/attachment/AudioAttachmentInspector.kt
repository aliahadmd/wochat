package com.aliahad.aichat.attachment

import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.ceil

data class AudioAttachmentMetadata(
    val durationMillis: Long,
    val tokenEstimate: Int,
    val format: SupportedAudioFormat,
)

enum class SupportedAudioFormat(val mimeType: String, val extensions: Set<String>) {
    WAV("audio/wav", setOf("wav")),
    MP3("audio/mpeg", setOf("mp3")),
    FLAC("audio/flac", setOf("flac")),
}

class AudioAttachmentInspector(
    private val durationReader: (File) -> Long = ::readDuration,
) {
    fun inspect(file: File, displayName: String = file.name): AudioAttachmentMetadata {
        require(file.isFile && file.length() > 0) { "Audio file is empty or missing." }
        require(file.length() <= MAX_AUDIO_BYTES) { "Audio files are limited to 25 MB." }
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val format = SupportedAudioFormat.entries.firstOrNull { extension in it.extensions }
            ?: error("Use WAV, MP3, or FLAC audio.")
        require(headerMatches(file, format)) { "The audio file header does not match its format." }
        val duration = durationReader(file)
        require(duration >= MIN_AUDIO_DURATION_MILLIS) { "Audio must be at least one second long." }
        require(duration <= MAX_AUDIO_DURATION_MILLIS) { "Audio is limited to 90 seconds per turn." }
        return AudioAttachmentMetadata(
            durationMillis = duration,
            tokenEstimate = estimateAudioTokens(duration),
            format = format,
        )
    }

    companion object {
        const val MAX_AUDIO_BYTES = 25L * 1024 * 1024
        const val MIN_AUDIO_DURATION_MILLIS = 1_000L
        const val MAX_AUDIO_DURATION_MILLIS = 90_000L
        const val AUDIO_TOKENS_PER_SECOND = 25

        fun estimateAudioTokens(durationMillis: Long): Int =
            ceil(durationMillis.coerceAtLeast(0L) / 1_000.0 * AUDIO_TOKENS_PER_SECOND)
                .toInt()

        internal fun headerMatches(file: File, format: SupportedAudioFormat): Boolean {
            val header = ByteArray(12)
            val count = file.inputStream().buffered().use { it.read(header) }
            if (count < 4) return false
            return when (format) {
                SupportedAudioFormat.WAV ->
                    count >= 12 && header.asAscii(0, 4) == "RIFF" && header.asAscii(8, 4) == "WAVE"
                SupportedAudioFormat.FLAC -> header.asAscii(0, 4) == "fLaC"
                SupportedAudioFormat.MP3 ->
                    header.asAscii(0, 3) == "ID3" ||
                        ((header[0].toInt() and 0xff) == 0xff &&
                            (header[1].toInt() and 0xe0) == 0xe0)
            }
        }

        private fun readDuration(file: File): Long {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: error("Unable to read audio duration.")
            } catch (error: Throwable) {
                throw IllegalArgumentException("Unable to decode this audio file.", error)
            } finally {
                retriever.release()
            }
        }

        private fun ByteArray.asAscii(offset: Int, length: Int): String =
            String(this, offset, length, Charsets.US_ASCII)
    }
}

