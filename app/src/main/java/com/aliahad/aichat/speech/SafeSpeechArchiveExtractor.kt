package com.aliahad.aichat.speech

import com.aliahad.aichat.core.SpeechAssetKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

object SafeSpeechArchiveExtractor {
    private const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024

    suspend fun extract(
        archive: File,
        stagingDirectory: File,
        spec: OfficialSpeechAssetSpec,
    ) {
        require(!stagingDirectory.exists() || stagingDirectory.deleteRecursively()) {
            "Unable to clear speech installation staging directory"
        }
        require(stagingDirectory.mkdirs()) { "Unable to create speech installation staging directory" }
        var expandedBytes = 0L
        archive.inputStream().buffered(DEFAULT_BUFFER_SIZE * 16).use { fileInput ->
            BZip2CompressorInputStream(fileInput).use { bzipInput ->
                TarArchiveInputStream(bzipInput).use { tarInput ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = tarInput.nextEntry ?: break
                        require(!entry.isSymbolicLink && !entry.isLink) {
                            "Speech archive contains a link"
                        }
                        val relative = safeRelativePath(entry.name, spec.archiveRoot) ?: continue
                        if (!isAllowed(spec.kind, relative)) continue
                        val destination = safeDestination(stagingDirectory, relative)
                        if (entry.isDirectory) {
                            require(destination.mkdirs() || destination.isDirectory) {
                                "Unable to create speech model directory"
                            }
                            continue
                        }
                        require(entry.isFile) { "Speech archive contains an unsupported entry" }
                        destination.parentFile?.let {
                            require(it.mkdirs() || it.isDirectory) {
                                "Unable to create speech model directory"
                            }
                        }
                        destination.outputStream().buffered(DEFAULT_BUFFER_SIZE * 16).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                            var entryBytes = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = tarInput.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                expandedBytes += read
                                require(
                                    entryBytes <= MAX_EXPANDED_BYTES &&
                                        expandedBytes <= MAX_EXPANDED_BYTES,
                                ) { "Speech archive expands beyond the safety limit" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
        }
        require(validateInstallation(stagingDirectory, spec)) {
            "Speech model installation is incomplete or incompatible"
        }
    }

    fun validateInstallation(directory: File, kind: SpeechAssetKind): Boolean =
        runCatching {
            requiredFiles(kind).forEach { relative ->
                require(File(directory, relative).isFile) { "Speech model is missing $relative" }
            }
            if (kind == SpeechAssetKind.TTS) {
                require(File(directory, "espeak-ng-data").isDirectory) {
                    "Speech model is missing espeak-ng-data"
                }
            }
        }.isSuccess

    fun validateInstallation(directory: File, spec: OfficialSpeechAssetSpec): Boolean =
        validateInstallation(directory, spec.kind) &&
            spec.installedFileSizes.all { (relative, expectedBytes) ->
                File(directory, relative).length() == expectedBytes
            }

    private fun safeRelativePath(entryName: String, archiveRoot: String): String? {
        require(entryName.isNotBlank() && !entryName.startsWith('/') && !entryName.contains('\\')) {
            "Unsafe speech archive entry"
        }
        val parts = entryName.split('/').filter(String::isNotEmpty)
        require(parts.none { it == "." || it == ".." }) { "Unsafe speech archive entry" }
        val stripped = if (parts.firstOrNull() == archiveRoot) parts.drop(1) else parts
        if (stripped.isEmpty()) return null
        return stripped.joinToString("/")
    }

    private fun safeDestination(root: File, relative: String): File {
        val destination = File(root, relative)
        val rootPath = root.canonicalPath + File.separator
        require(destination.canonicalPath.startsWith(rootPath)) { "Unsafe speech archive entry" }
        return destination
    }

    private fun isAllowed(kind: SpeechAssetKind, relative: String): Boolean = when (kind) {
        SpeechAssetKind.ASR -> relative in requiredFiles(kind) || relative == "README.md"
        SpeechAssetKind.TTS ->
            relative in requiredFiles(kind) ||
                relative == "README.md" ||
                relative == "LICENSE" ||
                relative.startsWith("espeak-ng-data/")
    }

    private fun requiredFiles(kind: SpeechAssetKind): Set<String> = when (kind) {
        SpeechAssetKind.ASR -> setOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        )
        SpeechAssetKind.TTS -> setOf(
            "model.int8.onnx",
            "voices.bin",
            "tokens.txt",
        )
    }
}
