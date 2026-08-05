package com.aliahad.aichat.model

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.math.max

data class ArtifactSpec(
    val id: String,
    val displayName: String,
    val url: String,
    val expectedBytes: Long,
    val sha256: String,
    val authorization: String? = null,
    val userAgent: String = "AIchat/1.0 Android",
)

data class ArtifactProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
    val retryAttempt: Int,
)

sealed interface ArtifactDownloadOutcome {
    data class Complete(val downloadedBytes: Long) : ArtifactDownloadOutcome
    data class Retryable(
        val downloadedBytes: Long,
        val message: String,
        val retryAfterMillis: Long? = null,
    ) : ArtifactDownloadOutcome
    data class Fatal(val downloadedBytes: Long, val message: String) : ArtifactDownloadOutcome
}

class ArtifactDownloader(
    private val connectionFactory: (String) -> HttpURLConnection = {
        URL(it).openConnection() as HttpURLConnection
    },
    private val inlineRetryCount: Int = 2,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun download(
        spec: ArtifactSpec,
        partial: File,
        onProgress: suspend (ArtifactProgress) -> Unit,
    ): ArtifactDownloadOutcome {
        partial.parentFile?.mkdirs()
        if (partial.length() > spec.expectedBytes) partial.truncate()
        if (partial.length() == spec.expectedBytes) {
            return ArtifactDownloadOutcome.Complete(partial.length())
        }

        var lastRetry: ArtifactDownloadOutcome.Retryable? = null
        repeat(inlineRetryCount + 1) { attempt ->
            currentCoroutineContext().ensureActive()
            when (val outcome = downloadOnce(spec, partial, attempt, onProgress)) {
                is ArtifactDownloadOutcome.Complete -> return outcome
                is ArtifactDownloadOutcome.Fatal -> return outcome
                is ArtifactDownloadOutcome.Retryable -> {
                    lastRetry = outcome
                    if (attempt < inlineRetryCount) {
                        retryDelay(outcome.retryAfterMillis ?: (1_000L shl attempt))
                    }
                }
            }
        }
        return requireNotNull(lastRetry)
    }

    private suspend fun downloadOnce(
        spec: ArtifactSpec,
        partial: File,
        retryAttempt: Int,
        onProgress: suspend (ArtifactProgress) -> Unit,
    ): ArtifactDownloadOutcome {
        var existing = partial.length()
        val connection = connectionFactory(spec.url)
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", spec.userAgent)
            connection.setRequestProperty("Accept-Encoding", "identity")
            spec.authorization?.let { connection.setRequestProperty("Authorization", it) }
            if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
            connection.connect()

            val response = connection.responseCode
            when {
                response == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    response == HttpURLConnection.HTTP_FORBIDDEN -> {
                    return ArtifactDownloadOutcome.Fatal(
                        partial.length(),
                        "Download authorization was rejected (HTTP $response).",
                    )
                }
                response == HTTP_RANGE_NOT_SATISFIABLE && existing > 0 -> {
                    partial.truncate()
                    return ArtifactDownloadOutcome.Retryable(
                        downloadedBytes = 0,
                        message = "The saved partial file no longer matches the server; restarting safely.",
                        retryAfterMillis = 0,
                    )
                }
                response == HttpURLConnection.HTTP_CLIENT_TIMEOUT || response == 429 ||
                    response in 500..599 -> {
                    return ArtifactDownloadOutcome.Retryable(
                        partial.length(),
                        "Download server is temporarily unavailable (HTTP $response).",
                        retryAfterMillis(connection.getHeaderField("Retry-After")),
                    )
                }
                response != HttpURLConnection.HTTP_OK &&
                    response != HttpURLConnection.HTTP_PARTIAL -> {
                    return ArtifactDownloadOutcome.Fatal(
                        partial.length(),
                        "Download failed with HTTP $response.",
                    )
                }
            }

            if (response == HttpURLConnection.HTTP_OK && existing > 0) {
                partial.truncate()
                existing = 0
            }
            if (response == HttpURLConnection.HTTP_PARTIAL &&
                !validContentRange(
                    connection.getHeaderField("Content-Range"),
                    existing,
                    spec.expectedBytes,
                )
            ) {
                return ArtifactDownloadOutcome.Fatal(
                    partial.length(),
                    "Download server returned an invalid byte range.",
                )
            }

            val expectedResponseBytes = spec.expectedBytes - existing
            val contentLength = connection.contentLengthLong
            if (contentLength > 0 && contentLength != expectedResponseBytes) {
                return ArtifactDownloadOutcome.Fatal(
                    partial.length(),
                    "Artifact metadata mismatch: the server offered " +
                        "${formatByteCount(contentLength)}, but AIchat expected " +
                        "${formatByteCount(expectedResponseBytes)}. Update the app before retrying.",
                )
            }

            val startedAt = System.nanoTime()
            RandomAccessFile(partial, "rw").use { output ->
                output.seek(existing)
                connection.inputStream.buffered(BUFFER_BYTES).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var downloaded = existing
                    var lastPublished = existing
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded > spec.expectedBytes) {
                            return ArtifactDownloadOutcome.Fatal(
                                downloaded,
                                "Download exceeded the expected artifact size.",
                            )
                        }
                        if (downloaded - lastPublished >= PROGRESS_STEP_BYTES) {
                            onProgress(progress(existing, downloaded, spec.expectedBytes, startedAt, retryAttempt))
                            lastPublished = downloaded
                        }
                    }
                    onProgress(progress(existing, downloaded, spec.expectedBytes, startedAt, retryAttempt))
                }
            }
            if (partial.length() != spec.expectedBytes) {
                ArtifactDownloadOutcome.Retryable(
                    partial.length(),
                    "Connection ended at ${formatByteCount(partial.length())} of " +
                        "${formatByteCount(spec.expectedBytes)}. AIchat will resume automatically.",
                )
            } else {
                ArtifactDownloadOutcome.Complete(partial.length())
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error.isRetryableTransportFailure()) {
                ArtifactDownloadOutcome.Retryable(
                    partial.length(),
                    error.toActionableDownloadMessage(),
                )
            } else {
                ArtifactDownloadOutcome.Fatal(
                    partial.length(),
                    error.message ?: "Download failed.",
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun progress(
        startingBytes: Long,
        downloadedBytes: Long,
        totalBytes: Long,
        startedAtNanos: Long,
        retryAttempt: Int,
    ): ArtifactProgress {
        val elapsedSeconds = max((System.nanoTime() - startedAtNanos) / 1_000_000_000.0, 0.001)
        val bytesPerSecond = ((downloadedBytes - startingBytes) / elapsedSeconds).toLong().coerceAtLeast(0)
        val remaining = (totalBytes - downloadedBytes).coerceAtLeast(0)
        return ArtifactProgress(
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            bytesPerSecond = bytesPerSecond,
            etaSeconds = bytesPerSecond.takeIf { it > 0 }?.let { remaining / it },
            retryAttempt = retryAttempt,
        )
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 30_000
        private const val READ_TIMEOUT_MILLIS = 90_000
        private const val BUFFER_BYTES = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 8L * 1024 * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")

        internal fun validContentRange(header: String?, start: Long, total: Long): Boolean {
            val match = CONTENT_RANGE.matchEntire(header.orEmpty()) ?: return false
            val actualStart = match.groupValues[1].toLongOrNull() ?: return false
            val actualEnd = match.groupValues[2].toLongOrNull() ?: return false
            val actualTotal = match.groupValues[3].toLongOrNull() ?: return false
            return actualStart == start && actualEnd >= start && actualEnd < total && actualTotal == total
        }
    }
}

private fun File.truncate() {
    RandomAccessFile(this, "rw").use { it.setLength(0) }
}

private fun formatByteCount(bytes: Long): String = "%,d bytes".format(bytes)

private fun retryAfterMillis(value: String?): Long? =
    value?.trim()?.toLongOrNull()?.coerceIn(1, 300)?.times(1_000)

private fun Throwable.isRetryableTransportFailure(): Boolean =
    this is SocketTimeoutException || this is SocketException || this is IOException

private fun Throwable.toActionableDownloadMessage(): String = when (this) {
    is SocketTimeoutException -> "The download timed out. AIchat will resume automatically."
    else -> "The connection was interrupted. AIchat will resume automatically."
}
