package com.aliahad.aichat.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aliahad.aichat.AiChatApplication
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

class ProjectorDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val app: AiChatApplication
        get() = applicationContext as AiChatApplication
    private val dao
        get() = app.container.database.projectorDao()
    private val directory: File
        get() = app.container.modelRepository.modelsDirectory()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(ModelConstants.WORK_INPUT_PROJECTOR_ID)
            ?: return@withContext Result.failure()
        val spec = ModelConstants.officialProjector(id) ?: return@withContext Result.failure()
        val record = dao.get(id) ?: return@withContext Result.failure()
        val destination = File(directory, spec.fileName)
        val partial = File(directory, "${spec.fileName}.part")
        try {
            setForeground(notification(spec, partial.length(), "Preparing vision projector"))
            dao.upsert(record.copy(status = DownloadStatus.DOWNLOADING, error = null))
            if (partial.length() != spec.sizeBytes) download(spec, partial)
            dao.upsert(requireNotNull(dao.get(id)).copy(status = DownloadStatus.VERIFYING))
            setForeground(notification(spec, partial.length(), "Verifying vision projector"))
            require(partial.length() == spec.sizeBytes) { "Projector size verification failed." }
            if (!sha256(partial).equals(spec.sha256, true)) {
                partial.delete()
                error("Projector checksum failed. The corrupt download was discarded.")
            }
            GgufValidator.validate(partial).getOrThrow()
            AtomicFileInstaller.replace(partial, destination)
            dao.upsert(
                requireNotNull(dao.get(id)).copy(
                    localPath = destination.absolutePath,
                    downloadedBytes = destination.length(),
                    status = DownloadStatus.READY,
                    error = null,
                ),
            )
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            dao.get(id)?.let {
                dao.upsert(it.copy(downloadedBytes = partial.length(), status = DownloadStatus.PAUSED))
            }
            throw cancelled
        } catch (error: Throwable) {
            dao.get(id)?.let {
                dao.upsert(
                    it.copy(
                        downloadedBytes = partial.length(),
                        status = DownloadStatus.FAILED,
                        error = error.message,
                    ),
                )
            }
            Result.failure()
        }
    }

    private suspend fun download(spec: OfficialProjectorSpec, partial: File) {
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
                connection.inputStream.buffered(256 * 1024).use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = existing
                    var lastPublished = existing
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        require(downloaded <= spec.sizeBytes) {
                            "Download exceeded the expected projector size"
                        }
                        if (downloaded - lastPublished >= 8L * 1024 * 1024) {
                            publish(spec, downloaded)
                            lastPublished = downloaded
                        }
                    }
                    publish(spec, downloaded)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun publish(spec: OfficialProjectorSpec, downloaded: Long) {
        dao.get(spec.id)?.let {
            dao.upsert(it.copy(downloadedBytes = downloaded, status = DownloadStatus.DOWNLOADING))
        }
        setProgress(workDataOf("downloaded" to downloaded, "total" to spec.sizeBytes))
        setForeground(notification(spec, downloaded, "Downloading vision projector"))
    }

    private fun notification(spec: OfficialProjectorSpec, downloaded: Long, title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ModelConstants.DOWNLOAD_CHANNEL_ID,
                "Model downloads",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val progress = ((downloaded * 100) / spec.sizeBytes).toInt()
        val notification = NotificationCompat.Builder(applicationContext, ModelConstants.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("${formatBytes(downloaded)} of ${formatBytes(spec.sizeBytes)}")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()
        return ForegroundInfo(
            44000 + (spec.id.hashCode() and 0x0fff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validContentRange(header: String?, start: Long, total: Long): Boolean {
        val match = CONTENT_RANGE.matchEntire(header.orEmpty()) ?: return false
        return match.groupValues[1].toLongOrNull() == start &&
            match.groupValues[3].toLongOrNull() == total &&
            (match.groupValues[2].toLongOrNull() ?: -1L) >= start
    }

    private companion object {
        val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")
    }
}
