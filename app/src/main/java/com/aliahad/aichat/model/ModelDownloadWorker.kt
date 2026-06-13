package com.aliahad.aichat.model

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
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.MainActivity
import com.aliahad.aichat.R
import com.aliahad.aichat.core.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val app: AiChatApplication
        get() = applicationContext as AiChatApplication
    private val dao
        get() = app.container.database.modelDao()
    private val modelsDirectory: File
        get() = app.container.modelRepository.modelsDirectory()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(ModelConstants.WORK_INPUT_MODEL_ID)
            ?: return@withContext Result.failure()
        val spec = ModelConstants.officialModel(modelId)
            ?: return@withContext Result.failure()
        val record = dao.get(modelId) ?: return@withContext Result.failure()
        val destination = File(modelsDirectory, record.fileName)
        val partial = File(modelsDirectory, "${record.fileName}.part")
        try {
            setForeground(foregroundInfo(spec, partial.length(), "Preparing ${spec.displayName}"))
            dao.upsert(record.copy(status = DownloadStatus.DOWNLOADING, error = null))
            if (partial.length() != spec.sizeBytes) download(spec, partial)
            dao.upsert(
                requireNotNull(dao.get(record.id)).copy(
                    downloadedBytes = partial.length(),
                    status = DownloadStatus.VERIFYING,
                    error = null,
                ),
            )
            setForeground(foregroundInfo(spec, partial.length(), "Verifying ${spec.displayName}"))
            require(partial.length() == spec.sizeBytes) {
                "Downloaded size ${partial.length()} does not match ${spec.sizeBytes}"
            }
            if (!sha256(partial).equals(spec.sha256, ignoreCase = true)) {
                partial.delete()
                error("Model checksum failed. The corrupt download was discarded.")
            }
            GgufValidator.validate(partial).getOrThrow()
            AtomicFileInstaller.replace(partial, destination)
            val current = requireNotNull(dao.get(record.id))
            dao.upsert(
                current.copy(
                    localPath = destination.absolutePath,
                    downloadedBytes = destination.length(),
                    status = DownloadStatus.READY,
                    error = null,
                    selected = current.selected,
                ),
            )
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            dao.get(record.id)?.let {
                dao.upsert(it.copy(downloadedBytes = partial.length(), status = DownloadStatus.PAUSED))
            }
            throw cancelled
        } catch (error: Throwable) {
            dao.get(record.id)?.let {
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

    private suspend fun download(spec: OfficialModelSpec, partial: File) {
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
            app.container.settings.token()?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")
            connection.connect()
            require(connection.responseCode == HttpURLConnection.HTTP_OK ||
                connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "Download failed with HTTP ${connection.responseCode}"
            }
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
                    var lastPublished = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        require(downloaded <= spec.sizeBytes) {
                            "Download exceeded the expected model size"
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

    private suspend fun publishProgress(spec: OfficialModelSpec, downloaded: Long) {
        dao.get(spec.id)?.let {
            dao.upsert(it.copy(downloadedBytes = downloaded, status = DownloadStatus.DOWNLOADING, error = null))
        }
        setProgress(androidx.work.workDataOf("downloaded" to downloaded, "total" to spec.sizeBytes))
        setForeground(foregroundInfo(spec, downloaded, "Downloading ${spec.displayName}"))
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
        spec: OfficialModelSpec,
        downloaded: Long,
        label: String,
    ): ForegroundInfo {
        val total = spec.sizeBytes
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ModelConstants.DOWNLOAD_CHANNEL_ID,
                applicationContext.getString(R.string.model_download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.model_download_channel_description)
            },
        )
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, ModelConstants.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(label)
            .setContentText("${formatBytes(downloaded)} of ${formatBytes(total)}")
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, total <= 0)
            .build()
        return ForegroundInfo(
            notificationId(spec.id),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun notificationId(modelId: String): Int =
        NOTIFICATION_ID_BASE + (modelId.hashCode() and 0x0fff)

    private companion object {
        const val NOTIFICATION_ID_BASE = 41000
        const val PROGRESS_STEP = 16L * 1024 * 1024

        fun validContentRange(header: String?, start: Long, total: Long): Boolean {
            val match = CONTENT_RANGE.matchEntire(header.orEmpty()) ?: return false
            return match.groupValues[1].toLongOrNull() == start &&
                match.groupValues[3].toLongOrNull() == total &&
                (match.groupValues[2].toLongOrNull() ?: -1L) >= start
        }

        val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")
    }
}
