package com.aliahad.aichat

import com.aliahad.aichat.attachment.AudioAttachmentInspector
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioAttachmentInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsSupportedHeadersAndEstimatesContext() {
        val wav = temporaryFolder.newFile("sample.wav").apply {
            writeBytes("RIFF0000WAVEdata".toByteArray())
        }
        val metadata = AudioAttachmentInspector { 5_500L }.inspect(wav)
        assertEquals(5_500L, metadata.durationMillis)
        assertEquals(138, metadata.tokenEstimate)
    }

    @Test
    fun rejectsMislabeledAndOverlongAudio() {
        val fake = temporaryFolder.newFile("sample.mp3").apply {
            writeBytes("not an mp3 file".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioAttachmentInspector { 5_000L }.inspect(fake)
        }

        val wav = temporaryFolder.newFile("long.wav").apply {
            writeBytes("RIFF0000WAVEdata".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioAttachmentInspector { 90_001L }.inspect(wav)
        }
    }

    @Test
    fun detectsFlacAndMp3Magic() {
        assertAccepted("sample.flac", "fLaCmetadata".toByteArray())
        assertAccepted("sample.mp3", "ID3metadata".toByteArray())
        assertAccepted("frame.mp3", byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x10, 0x00))
    }

    private fun assertAccepted(name: String, bytes: ByteArray) {
        val file: File = temporaryFolder.newFile(name).apply { writeBytes(bytes) }
        AudioAttachmentInspector { 1_000L }.inspect(file)
    }
}

