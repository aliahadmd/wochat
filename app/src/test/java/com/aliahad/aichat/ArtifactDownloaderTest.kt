package com.aliahad.aichat

import com.aliahad.aichat.model.ArtifactDownloadOutcome
import com.aliahad.aichat.model.ArtifactDownloader
import com.aliahad.aichat.model.ArtifactSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import kotlin.io.path.createTempDirectory

class ArtifactDownloaderTest {
    @Test
    fun cleanDownloadCompletesAndPublishesProgress() = runTest {
        val directory = createTempDirectory("artifact-download-").toFile()
        try {
            val bytes = "0123456789".toByteArray()
            val connection = FakeConnection(200, bytes, contentLength = bytes.size.toLong())
            val partial = File(directory, "model.part")
            val progress = mutableListOf<Long>()

            val outcome = ArtifactDownloader(
                connectionFactory = { connection },
                inlineRetryCount = 0,
                retryDelay = {},
            ).download(spec(bytes.size.toLong()), partial) { progress += it.downloadedBytes }

            assertTrue(outcome is ArtifactDownloadOutcome.Complete)
            assertEquals("0123456789", partial.readText())
            assertEquals(bytes.size.toLong(), progress.last())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun interruptedDownloadResumesFromPreservedByteRange() = runTest {
        val directory = createTempDirectory("artifact-resume-").toFile()
        try {
            val partial = File(directory, "model.part")
            val first = FakeConnection(
                statusCode = 200,
                bytes = "0123".toByteArray(),
                contentLength = 10,
                terminalError = SocketException("connection reset"),
            )
            val firstOutcome = ArtifactDownloader(
                connectionFactory = { first },
                inlineRetryCount = 0,
                retryDelay = {},
            ).download(spec(10), partial) {}

            assertTrue(firstOutcome is ArtifactDownloadOutcome.Retryable)
            assertEquals(4, partial.length())

            val resumed = FakeConnection(
                statusCode = 206,
                bytes = "456789".toByteArray(),
                contentLength = 6,
                headers = mapOf("Content-Range" to "bytes 4-9/10"),
            )
            val resumedOutcome = ArtifactDownloader(
                connectionFactory = { resumed },
                inlineRetryCount = 0,
                retryDelay = {},
            ).download(spec(10), partial) {}

            assertTrue(resumedOutcome is ArtifactDownloadOutcome.Complete)
            assertEquals("bytes=4-", resumed.requestHeaders["Range"])
            assertEquals("identity", resumed.requestHeaders["Accept-Encoding"])
            assertEquals("0123456789", partial.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun staleRangeRestartsFromZeroAndCompletes() = runTest {
        val directory = createTempDirectory("artifact-stale-range-").toFile()
        try {
            val partial = File(directory, "model.part").apply { writeText("old!") }
            val staleRange = FakeConnection(416, byteArrayOf(), contentLength = 0)
            val cleanDownload = FakeConnection(
                statusCode = 200,
                bytes = "0123456789".toByteArray(),
                contentLength = 10,
            )
            val connections = ArrayDeque(listOf(staleRange, cleanDownload))

            val outcome = ArtifactDownloader(
                connectionFactory = { connections.removeFirst() },
                inlineRetryCount = 1,
                retryDelay = {},
            ).download(spec(10), partial) {}

            assertTrue(outcome is ArtifactDownloadOutcome.Complete)
            assertEquals("bytes=4-", staleRange.requestHeaders["Range"])
            assertEquals(null, cleanDownload.requestHeaders["Range"])
            assertEquals("0123456789", partial.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun serverLengthMismatchIsFatalAndActionable() = runTest {
        val directory = createTempDirectory("artifact-metadata-").toFile()
        try {
            val connection = FakeConnection(
                statusCode = 200,
                bytes = byteArrayOf(),
                contentLength = 12,
            )
            val outcome = ArtifactDownloader(
                connectionFactory = { connection },
                inlineRetryCount = 2,
                retryDelay = {},
            ).download(spec(10), File(directory, "model.part")) {}

            assertTrue(outcome is ArtifactDownloadOutcome.Fatal)
            assertTrue((outcome as ArtifactDownloadOutcome.Fatal).message.contains("metadata mismatch"))
            assertTrue(outcome.message.contains("12 bytes"))
            assertTrue(outcome.message.contains("10 bytes"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidResumeRangeIsFatalWithoutDiscardingPartial() = runTest {
        val directory = createTempDirectory("artifact-range-").toFile()
        try {
            val partial = File(directory, "model.part").apply { writeText("0123") }
            val connection = FakeConnection(
                statusCode = 206,
                bytes = "456789".toByteArray(),
                contentLength = 6,
                headers = mapOf("Content-Range" to "bytes 0-5/10"),
            )
            val outcome = ArtifactDownloader(
                connectionFactory = { connection },
                inlineRetryCount = 0,
                retryDelay = {},
            ).download(spec(10), partial) {}

            assertTrue(outcome is ArtifactDownloadOutcome.Fatal)
            assertEquals("0123", partial.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun spec(bytes: Long) = ArtifactSpec(
        id = "test",
        displayName = "Test artifact",
        url = "https://example.invalid/model.gguf",
        expectedBytes = bytes,
        sha256 = "unused",
    )
}

private class FakeConnection(
    private val statusCode: Int,
    private val bytes: ByteArray,
    private val contentLength: Long,
    private val headers: Map<String, String> = emptyMap(),
    private val terminalError: Throwable? = null,
) : HttpURLConnection(URL("https://example.invalid")) {
    val requestHeaders = mutableMapOf<String, String>()

    override fun getResponseCode(): Int = statusCode
    override fun getContentLengthLong(): Long = contentLength
    override fun getHeaderField(name: String?): String? = headers[name]
    override fun setRequestProperty(key: String?, value: String?) {
        if (key != null && value != null) requestHeaders[key] = value
    }

    override fun getInputStream(): InputStream {
        val delegate = ByteArrayInputStream(bytes)
        return object : InputStream() {
            override fun read(): Int {
                val value = delegate.read()
                if (value < 0 && terminalError != null) throw terminalError
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = delegate.read(buffer, offset, length)
                if (count < 0 && terminalError != null) throw terminalError
                return count
            }
        }
    }

    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
}
