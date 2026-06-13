package com.aliahad.aichat.speech

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.MainActivity
import com.aliahad.aichat.R
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.model.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class SpeechAssetDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val app: AiChatApplication
        get() = applicationContext as AiChatApplication
    private val dao
        get() = app.container.database.speechAssetDao()
    private val repository
        get() = app.container.speechAssetRepository

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val assetId = inputData.getString(SpeechAssetConstants.WORK_INPUT_ASSET_ID)
            ?: return@withContext Result.failure()
        val spec = SpeechAssetConstants.officialAsset(assetId)
            ?: return@withContext Result.failure()
        val record = dao.get(assetId) ?: return@withContext Result.failure()
        val speechDirectory = repository.speechDirectory()
        val partial = File(speechDirectory, "${spec.archiveFileName}.part")
        val staging = File(speechDirectory, ".install-${spec.id}")
        val destination = File(speechDirectory, spec.installDirectory)
        try {
            setForeground(foregroundInfo(spec, partial.length(), "Preparing ${spec.displayName}"))
            dao.upsert(record.copy(status = DownloadStatus.DOWNLOADING, error = null))
            if (partial.length() != spec.sizeBytes) download(spec, partial)
            dao.get(assetId)?.let {
                dao.upsert(
                    it.copy(
                        downloadedBytes = partial.length(),
                        status = DownloadStatus.VERIFYING,
                        error = null,
                    ),
                )
            }
            setForeground(foregroundInfo(spec, partial.length(), "Installing ${spec.displayName}"))
            require(partial.length() == spec.sizeBytes) {
                "Downloaded size ${partial.length()} does not match ${spec.sizeBytes}"
            }
            if (!sha256(partial).equals(spec.sha256, ignoreCase = true)) {
                partial.delete()
                error("Speech model checksum failed. The corrupt download was discarded.")
            }
            SafeSpeechArchiveExtractor.extract(partial, staging, spec)
            atomicInstall(staging, destination)
            partial.delete()
            val current = requireNotNull(dao.get(assetId))
            dao.upsert(
                current.copy(
                    localPath = destination.absolutePath,
                    downloadedBytes = spec.sizeBytes,
                    status = DownloadStatus.READY,
                    error = null,
                ),
            )
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            staging.deleteRecursively()
            dao.get(assetId)?.let {
                dao.upsert(
                    it.copy(
                        downloadedBytes = partial.length(),
                        status = DownloadStatus.PAUSED,
                        error = null,
                    ),
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            staging.deleteRecursively()
            dao.get(assetId)?.let {
                dao.upsert(
                    it.copy(
                        downloadedBytes = partial.length(),
                        status = DownloadStatus.FAILED,
                        error = error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
            Result.failure()
        }
    }

    private suspend fun download(spec: OfficialSpeechAssetSpec, partial: File) {
        var existing = partial.length()
        if (existing > spec.sizeBytes) {
            partial.delete()
            existing = 0
        }
        if (existing == spec.sizeBytes) return
        val connection = URL(spec.downloadUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "AIchat/1.0 Android")
            if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
            connection.connect()
            require(
                connection.responseCode == HttpURLConnection.HTTP_OK ||
                    connection.responseCode == HttpURLConnection.HTTP_PARTIAL,
            ) { "Download failed with HTTP ${connection.responseCode}" }
            if (existing > 0 && connection.responseCode == HttpURLConnection.HTTP_OK) {
                partial.delete()
                existing = 0
            }
            if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                require(validContentRange(connection.getHeaderField("Content-Range"), existing, spec.sizeBytes)) {
                    "Download server returned an invalid byte range"
                }
            }
            RandomAccessFile(partial, "rw").use { output ->
                output.seek(existing)
                connection.inputStream.buffered(DEFAULT_BUFFER_SIZE * 16).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var downloaded = existing
                    var lastPublished = existing
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        require(downloaded <= spec.sizeBytes) {
                            "Download exceeded the expected speech archive size"
                        }
                        if (downloaded - lastPublished >= PROGRESS_STEP) {
                            publishProgress(spec, downloaded)
                            lastPublished = downloaded
                        }
                    }
                    publishProgress(spec, downloaded)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun publishProgress(spec: OfficialSpeechAssetSpec, downloaded: Long) {
        dao.get(spec.id)?.let {
            dao.upsert(
                it.copy(
                    downloadedBytes = downloaded,
                    status = DownloadStatus.DOWNLOADING,
                    error = null,
                ),
            )
        }
        setProgress(workDataOf("downloaded" to downloaded, "total" to spec.sizeBytes))
        setForeground(foregroundInfo(spec, downloaded, "Downloading ${spec.displayName}"))
    }

    private fun atomicInstall(staging: File, destination: File) {
        val backup = File(destination.parentFile, "${destination.name}.old")
        backup.deleteRecursively()
        if (destination.exists()) {
            require(destination.renameTo(backup)) { "Unable to replace the existing speech model" }
        }
        if (!staging.renameTo(destination)) {
            destination.deleteRecursively()
            if (backup.exists()) {
                check(backup.renameTo(destination)) {
                    "Unable to restore the previous speech model after installation failed"
                }
            }
            error("Unable to finalize the speech model")
        }
        backup.deleteRecursively()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(DEFAULT_BUFFER_SIZE * 16).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun foregroundInfo(
        spec: OfficialSpeechAssetSpec,
        downloaded: Long,
        label: String,
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SpeechAssetConstants.DOWNLOAD_CHANNEL_ID,
                applicationContext.getString(R.string.model_download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val progress = ((downloaded * 100) / spec.sizeBytes).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(
            applicationContext,
            SpeechAssetConstants.DOWNLOAD_CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(label)
            .setContentText("${formatBytes(downloaded)} of ${formatBytes(spec.sizeBytes)}")
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + (spec.id.hashCode() and 0x0fff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private companion object {
        const val NOTIFICATION_ID_BASE = 46000
        const val PROGRESS_STEP = 2L * 1024 * 1024
        val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")

        fun validContentRange(header: String?, start: Long, total: Long): Boolean {
            val match = CONTENT_RANGE.matchEntire(header.orEmpty()) ?: return false
            return match.groupValues[1].toLongOrNull() == start &&
                match.groupValues[3].toLongOrNull() == total &&
                (match.groupValues[2].toLongOrNull() ?: -1L) >= start
        }
    }
}
