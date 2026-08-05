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
import kotlinx.coroutines.withContext
import java.io.File
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
            if (partial.length() != spec.sizeBytes) {
                val outcome = ArtifactDownloader().download(
                    spec = ArtifactSpec(
                        id = spec.id,
                        displayName = spec.displayName,
                        url = spec.downloadUrl,
                        expectedBytes = spec.sizeBytes,
                        sha256 = spec.sha256,
                        authorization = app.container.settings.token()?.let { "Bearer $it" },
                    ),
                    partial = partial,
                    onProgress = { publish(spec, it) },
                )
                when (outcome) {
                    is ArtifactDownloadOutcome.Complete -> Unit
                    is ArtifactDownloadOutcome.Retryable -> {
                        val current = requireNotNull(dao.get(id))
                        val exhausted = runAttemptCount >= MAX_WORK_RETRIES
                        dao.upsert(
                            current.copy(
                                downloadedBytes = outcome.downloadedBytes,
                                bytesPerSecond = 0,
                                etaSeconds = null,
                                retryAttempt = runAttemptCount + 1,
                                status = if (exhausted) DownloadStatus.FAILED else DownloadStatus.QUEUED,
                                error = if (exhausted) {
                                    "Automatic retries were exhausted. Tap Resume to try again."
                                } else {
                                    outcome.message
                                },
                            ),
                        )
                        return@withContext if (exhausted) Result.failure() else Result.retry()
                    }
                    is ArtifactDownloadOutcome.Fatal -> error(outcome.message)
                }
            }
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
                    bytesPerSecond = 0,
                    etaSeconds = null,
                    retryAttempt = 0,
                ),
            )
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            dao.get(id)?.let {
                dao.upsert(
                    it.copy(
                        downloadedBytes = partial.length(),
                        status = DownloadStatus.PAUSED,
                        bytesPerSecond = 0,
                        etaSeconds = null,
                    ),
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            dao.get(id)?.let {
                dao.upsert(
                    it.copy(
                        downloadedBytes = partial.length(),
                        status = DownloadStatus.FAILED,
                        error = error.message,
                        bytesPerSecond = 0,
                        etaSeconds = null,
                    ),
                )
            }
            Result.failure()
        }
    }

    private suspend fun publish(spec: OfficialProjectorSpec, progress: ArtifactProgress) {
        val downloaded = progress.downloadedBytes
        dao.get(spec.id)?.let {
            dao.upsert(
                it.copy(
                    downloadedBytes = downloaded,
                    status = DownloadStatus.DOWNLOADING,
                    error = null,
                    bytesPerSecond = progress.bytesPerSecond,
                    etaSeconds = progress.etaSeconds,
                    retryAttempt = runAttemptCount,
                ),
            )
        }
        setProgress(
            workDataOf(
                "downloaded" to downloaded,
                "total" to spec.sizeBytes,
                "bytes_per_second" to progress.bytesPerSecond,
                "eta_seconds" to (progress.etaSeconds ?: -1L),
                "retry_attempt" to progress.retryAttempt,
            ),
        )
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

    private companion object {
        const val MAX_WORK_RETRIES = 12
    }
}
