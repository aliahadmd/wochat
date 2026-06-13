package com.aliahad.aichat

import com.aliahad.aichat.speech.SafeSpeechArchiveExtractor
import com.aliahad.aichat.speech.SpeechAssetConstants
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class SafeSpeechArchiveExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun extractsOnlyAllowlistedAsrFiles() = runTest {
        val spec = SpeechAssetConstants.ZIPFORMER_ASR.copy(installedFileSizes = emptyMap())
        val archive = temporaryFolder.newFile("asr.tar.bz2")
        writeArchive(
            archive,
            mapOf(
                "${spec.archiveRoot}/encoder-epoch-99-avg-1.int8.onnx" to "encoder",
                "${spec.archiveRoot}/decoder-epoch-99-avg-1.onnx" to "decoder",
                "${spec.archiveRoot}/joiner-epoch-99-avg-1.int8.onnx" to "joiner",
                "${spec.archiveRoot}/tokens.txt" to "tokens",
                "${spec.archiveRoot}/unneeded-fp32.onnx" to "large",
            ),
        )
        val destination = temporaryFolder.newFolder("installed")

        SafeSpeechArchiveExtractor.extract(archive, destination, spec)

        assertTrue(SafeSpeechArchiveExtractor.validateInstallation(destination, spec))
        assertFalse(File(destination, "unneeded-fp32.onnx").exists())
    }

    @Test
    fun rejectsAsrFilesFromIncompatibleArchive() {
        val spec = SpeechAssetConstants.ZIPFORMER_ASR
        val destination = temporaryFolder.newFolder("incompatible-asr")
        spec.installedFileSizes.forEach { (relative, expectedBytes) ->
            RandomAccessFile(File(destination, relative), "rw").use {
                it.setLength(
                    if (relative.startsWith("encoder")) expectedBytes - 1 else expectedBytes,
                )
            }
        }

        assertFalse(SafeSpeechArchiveExtractor.validateInstallation(destination, spec))

        RandomAccessFile(
            File(destination, "encoder-epoch-99-avg-1.int8.onnx"),
            "rw",
        ).use {
            it.setLength(spec.installedFileSizes.getValue("encoder-epoch-99-avg-1.int8.onnx"))
        }
        assertTrue(SafeSpeechArchiveExtractor.validateInstallation(destination, spec))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsArchiveTraversal() = runTest {
        val spec = SpeechAssetConstants.ZIPFORMER_ASR
        val archive = temporaryFolder.newFile("traversal.tar.bz2")
        writeArchive(
            archive,
            mapOf("${spec.archiveRoot}/../tokens.txt" to "unsafe"),
        )

        SafeSpeechArchiveExtractor.extract(
            archive,
            temporaryFolder.newFolder("traversal-install"),
            spec,
        )
    }

    private fun writeArchive(file: File, entries: Map<String, String>) {
        BZip2CompressorOutputStream(file.outputStream().buffered()).use { bzip ->
            TarArchiveOutputStream(bzip).use { tar ->
                entries.forEach { (name, content) ->
                    val bytes = content.toByteArray()
                    val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
    }
}
