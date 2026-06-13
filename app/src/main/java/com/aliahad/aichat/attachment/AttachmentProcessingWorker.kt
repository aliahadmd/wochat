package com.aliahad.aichat.attachment

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.aliahad.aichat.AiChatApplication
import com.aliahad.aichat.core.AttachmentProcessingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class AttachmentProcessingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val app: AiChatApplication
        get() = applicationContext as AiChatApplication
    private val dao
        get() = app.container.database.attachmentDao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(ATTACHMENT_ID) ?: return@withContext Result.failure()
        val attachment = dao.get(id) ?: return@withContext Result.failure()
        try {
            setForeground(foregroundInfo(attachment.displayName))
            dao.upsert(
                attachment.copy(
                    state = AttachmentProcessingState.EXTRACTING,
                    progress = 0.25f,
                    error = null,
                ),
            )
            val result = AttachmentProcessor(applicationContext).process(attachment)
            app.container.database.withTransaction {
                dao.deleteChunks(id)
                dao.upsertChunks(result.chunks)
                dao.upsert(
                    attachment.copy(
                        previewPath = result.previewPath,
                        derivedImagePaths = result.imagePaths.joinToString("\n"),
                        pageCount = result.pageCount,
                        selectedPages = result.selectedPages.sorted().joinToString(","),
                        imageTokenBudget = result.imageTokenBudget,
                        state = AttachmentProcessingState.READY,
                        progress = 1f,
                        error = null,
                    ),
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            dao.get(id)?.let {
                dao.upsert(
                    it.copy(
                        state = AttachmentProcessingState.FAILED,
                        error = error.message ?: "Attachment processing failed.",
                    ),
                )
            }
            Result.failure()
        }
    }

    private fun foregroundInfo(name: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Attachment processing", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Preparing attachment")
            .setContentText(name)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_BASE + (name.hashCode() and 0x0fff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val ATTACHMENT_ID = "attachment_id"
        private const val CHANNEL = "attachment_processing"
        private const val NOTIFICATION_BASE = 46000
    }
}
