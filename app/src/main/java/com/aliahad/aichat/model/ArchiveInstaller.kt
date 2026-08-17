package com.aliahad.aichat.model

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File

/**
 * Extracts a verified `.tar.bz2` model bundle into the models directory.
 *
 * Exists for exactly one artifact: the Piper voice. Piper phonemises through
 * `espeak-ng-data`, which is 355 files, so it ships as a tarball where every other
 * model in this app is a single file (see plan 036).
 *
 * The archive is only ever opened *after* its sha256 has been checked against the
 * pinned hash, so this is not parsing untrusted input. The traversal guard below
 * is defence in depth all the same: an archive entry naming `../` would otherwise
 * write anywhere the app can reach, and the same class of check already guards
 * backup imports in `BackupPathSafety`.
 */
internal object ArchiveInstaller {

    /**
     * Extracts [archive] into [modelsDirectory], replacing any previous copy of
     * [rootDirectoryName] atomically enough that a crash mid-extract cannot leave a
     * half-written directory looking complete: it stages into a sibling `.partial`
     * directory and renames only once every entry is written.
     */
    fun extract(archive: File, modelsDirectory: File, rootDirectoryName: String): Result<File> =
        runCatching {
            require(archive.isFile) { "Archive is missing" }
            val destination = File(modelsDirectory, rootDirectoryName)
            val staging = File(modelsDirectory, "$rootDirectoryName.partial")
            staging.deleteRecursively()
            require(staging.mkdirs()) { "Unable to create the extraction directory" }
            val stagingRoot = staging.canonicalFile

            BufferedInputStream(archive.inputStream()).use { raw ->
                BZip2CompressorInputStream(raw).use { decompressed ->
                    TarArchiveInputStream(decompressed).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            // Entries carry the archive's own root directory as a prefix; drop
                            // it so the result is <models>/<rootDirectoryName>/... rather than
                            // <models>/<rootDirectoryName>/<rootDirectoryName>/...
                            val relative = entry.name.removePrefix("$rootDirectoryName/")
                            if (relative.isBlank()) {
                                entry = tar.nextEntry
                                continue
                            }
                            val target = File(staging, relative).canonicalFile
                            require(target.path.startsWith(stagingRoot.path + File.separator)) {
                                "Archive entry escapes the extraction directory"
                            }
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { output -> tar.copyTo(output) }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }

            destination.deleteRecursively()
            require(staging.renameTo(destination)) { "Unable to finalize the extracted model" }
            destination
        }.onFailure { File(modelsDirectory, "$rootDirectoryName.partial").deleteRecursively() }

    /** True when [rootDirectoryName] has been extracted and carries the file sherpa-onnx loads. */
    fun isExtracted(modelsDirectory: File, rootDirectoryName: String, requiredEntry: String): Boolean =
        File(File(modelsDirectory, rootDirectoryName), requiredEntry).isFile
}
