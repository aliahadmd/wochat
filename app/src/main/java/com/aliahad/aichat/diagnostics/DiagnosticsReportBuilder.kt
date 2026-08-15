package com.aliahad.aichat.diagnostics

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aliahad.aichat.AppContainer
import com.aliahad.aichat.BuildConfig
import com.aliahad.aichat.activity.OfficeWorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticsReportBuilder(
    private val context: Context,
    private val container: AppContainer,
) {
    suspend fun build(): String {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val storage = StatFs(context.noBackupFilesDir.absolutePath)
        val selected = container.modelRepository.selectedModel()
        val backend = container.settings.backendMode.first()
        val effectiveBackend = container.settings.effectiveBackend()
        val metrics = container.inferenceEngine.metrics.value
        val models = container.modelRepository.models.first()
        val projectors = container.modelRepository.projectors.first()
        return JSONObject()
            .put("createdAt", System.currentTimeMillis())
            .put("app", JSONObject()
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("llamaRevision", BuildConfig.LLAMA_RUNTIME_REVISION))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())))
            .put("runtime", JSONObject()
                .put("configuredBackend", backend.name)
                .put("effectiveBackend", effectiveBackend.name)
                .put("inferenceState", container.inferenceEngine.state.value.javaClass.simpleName)
                .put("residencyState", container.residencyController.state.value.javaClass.simpleName)
                .put("activeContext", container.inferenceEngine.activeContextSize)
                .put("modelContextLimit", container.inferenceEngine.modelContextLimit)
                .put("thermalStatus", context.getSystemService(PowerManager::class.java).currentThermalStatus)
                .put("pssBytes", memory.totalPss.toLong() * 1_024)
                .put("availableStorageBytes", storage.availableBytes)
                .put("modelLoadMillis", metrics.modelLoadMillis)
                .put("historyRestoreMillis", metrics.historyRestoreMillis)
                .put("promptEvaluationMillis", metrics.promptEvaluationMillis)
                .put("firstTokenMillis", metrics.firstTokenMillis))
            .put("selectedModel", selected?.let { model ->
                JSONObject()
                    .put("id", model.id)
                    .put("displayName", model.displayName)
                    .put("status", model.status.name)
                    .put("downloadedBytes", model.downloadedBytes)
                    .put("expectedBytes", model.expectedBytes)
                    .put("fingerprint", model.sha256?.take(16))
            })
            .put("models", JSONArray(models.map { model ->
                JSONObject()
                    .put("id", model.id)
                    .put("status", model.status.name)
                    .put("downloadedBytes", model.downloadedBytes)
                    .put("retryAttempt", model.retryAttempt)
                    .put("error", model.error?.take(240))
            }))
            .put("projectors", JSONArray(projectors.map { projector ->
                JSONObject()
                    .put("id", projector.id)
                    .put("status", projector.status.name)
                    .put("downloadedBytes", projector.downloadedBytes)
                    .put("retryAttempt", projector.retryAttempt)
                    .put("error", projector.error?.take(240))
            }))
            .put("collectors", collectorsSection())
            .toString(2)
    }

    private suspend fun collectorsSection(): JSONObject {
        val now = System.currentTimeMillis()
        val checkpoints = container.database.activityDao().checkpoints()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val workStates = withContext(Dispatchers.IO) {
            OfficeWorkScheduler.scheduledWorkNames.associateWith { name ->
                runCatching {
                    val infos = WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWork(name).get()
                    (infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.state
                        ?: infos.firstOrNull()?.state)?.name
                }.getOrNull() ?: "UNKNOWN"
            }
        }
        return JSONObject()
            .put("collectionPaused", container.settings.collectionPaused.first())
            .put(
                "notificationListenerEnabled",
                context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
            )
            .put(
                "ignoringBatteryOptimizations",
                powerManager.isIgnoringBatteryOptimizations(context.packageName),
            )
            .put("workStates", JSONObject().also { json ->
                workStates.forEach { (name, state) -> json.put(name, state) }
            })
            .put("checkpoints", JSONArray(checkpoints.map { checkpoint ->
                JSONObject()
                    .put("collector", checkpoint.collector)
                    .put("ageMillis", now - checkpoint.lastCollectedAt)
                    .put("error", checkpoint.error?.take(240))
            }))
    }
}
