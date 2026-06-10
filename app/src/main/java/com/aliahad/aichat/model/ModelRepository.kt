package com.aliahad.aichat.model

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.ProjectorRecord
import com.aliahad.aichat.core.ChatQualityMode
import com.aliahad.aichat.data.ModelDao
import com.aliahad.aichat.data.ModelRecordEntity
import com.aliahad.aichat.data.ProjectorDao
import com.aliahad.aichat.data.ProjectorRecordEntity
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

interface ModelRepository {
    val models: Flow<List<ModelRecord>>
    val projectors: Flow<List<ProjectorRecord>>
    fun modelsDirectory(): File
    suspend fun ensureOfficialRecords()
    suspend fun startOfficialDownload(id: String)
    suspend fun pauseOfficialDownload(id: String)
    suspend fun importModel(uri: Uri): ModelRecord
    suspend fun selectModel(id: String)
    suspend fun selectedModel(): ModelRecord?
    suspend fun deleteModel(id: String)
    suspend fun testHuggingFaceToken(token: String): Result<Unit>
    suspend fun modelForQuality(mode: ChatQualityMode): ModelRecord?
    suspend fun projectorForModel(modelId: String): ProjectorRecord?
    suspend fun startProjectorDownload(id: String)
    suspend fun pauseProjectorDownload(id: String)
    suspend fun deleteProjector(id: String)
}

class DefaultModelRepository(
    private val context: Context,
    private val dao: ModelDao,
    private val projectorDao: ProjectorDao,
    private val settings: AppSettingsRepository,
    private val isPathInUse: (String) -> Boolean,
) : ModelRepository {
    private val workManager = WorkManager.getInstance(context)

    override val models: Flow<List<ModelRecord>> =
        dao.observeAll().map { rows -> rows.map(ModelRecordEntity::toDomain) }
    override val projectors: Flow<List<ProjectorRecord>> =
        projectorDao.observeAll().map { rows -> rows.map(ProjectorRecordEntity::toDomain) }

    override fun modelsDirectory(): File =
        File(context.noBackupFilesDir, "models").apply { mkdirs() }

    override suspend fun ensureOfficialRecords() {
        ModelConstants.OFFICIAL_MODELS.forEach { spec ->
            val existing = dao.get(spec.id)
            val finalFile = File(modelsDirectory(), spec.fileName)
            val fileReady = finalFile.exists() && finalFile.length() == spec.sizeBytes
            if (existing == null) {
                dao.upsert(
                    ModelRecordEntity(
                        id = spec.id,
                        displayName = spec.displayName,
                        fileName = spec.fileName,
                        localPath = finalFile.takeIf { fileReady }?.absolutePath,
                        sourceRepo = spec.repository,
                        expectedBytes = spec.sizeBytes,
                        sha256 = spec.sha256,
                        downloadedBytes = finalFile.takeIf { fileReady }?.length() ?: 0,
                        status = if (fileReady) DownloadStatus.READY else DownloadStatus.NOT_DOWNLOADED,
                        error = null,
                        selected = false,
                    ),
                )
            } else {
                val localFileExists = existing.localPath?.let(::File)?.exists() == true
                val reconciled = existing.copy(
                    displayName = spec.displayName,
                    fileName = spec.fileName,
                    localPath = when {
                        fileReady -> finalFile.absolutePath
                        localFileExists -> existing.localPath
                        else -> null
                    },
                    sourceRepo = spec.repository,
                    expectedBytes = spec.sizeBytes,
                    sha256 = spec.sha256,
                    downloadedBytes = when {
                        fileReady -> finalFile.length()
                        localFileExists -> existing.downloadedBytes
                        else -> File(modelsDirectory(), "${spec.fileName}.part").length()
                    },
                    status = when {
                        fileReady -> DownloadStatus.READY
                        localFileExists -> existing.status
                        existing.status in ACTIVE_DOWNLOAD_STATES -> existing.status
                        else -> DownloadStatus.NOT_DOWNLOADED
                    },
                    error = if (fileReady) null else existing.error,
                    selected = existing.selected && (fileReady || localFileExists),
                )
                if (reconciled != existing) dao.upsert(reconciled)
            }
        }
        ModelConstants.OFFICIAL_PROJECTORS.forEach { spec ->
            val existing = projectorDao.get(spec.id)
            val finalFile = File(modelsDirectory(), spec.fileName)
            val fileReady = finalFile.exists() && finalFile.length() == spec.sizeBytes
            val part = File(modelsDirectory(), "${spec.fileName}.part")
            projectorDao.upsert(
                ProjectorRecordEntity(
                    id = spec.id,
                    modelId = spec.modelId,
                    displayName = spec.displayName,
                    fileName = spec.fileName,
                    localPath = finalFile.takeIf { fileReady }?.absolutePath,
                    sourceRepo = spec.repository,
                    expectedBytes = spec.sizeBytes,
                    sha256 = spec.sha256,
                    downloadedBytes = if (fileReady) finalFile.length() else part.length(),
                    status = when {
                        fileReady -> DownloadStatus.READY
                        existing?.status in ACTIVE_DOWNLOAD_STATES -> requireNotNull(existing).status
                        else -> DownloadStatus.NOT_DOWNLOADED
                    },
                    error = if (fileReady) null else existing?.error,
                ),
            )
        }
    }

    override suspend fun startOfficialDownload(id: String) {
        ensureOfficialRecords()
        val spec = requireNotNull(ModelConstants.officialModel(id)) { "Official model not found" }
        val record = requireNotNull(dao.get(id)) { "Model record not found" }
        if (record.status == DownloadStatus.READY && record.localPath != null) return
        val stat = StatFs(modelsDirectory().absolutePath)
        val part = File(modelsDirectory(), "${spec.fileName}.part")
        val remaining = (spec.sizeBytes - part.length()).coerceAtLeast(0)
        require(stat.availableBytes > remaining + MIN_FREE_SPACE) {
            "Not enough storage. Free at least ${formatBytes(remaining + MIN_FREE_SPACE)}."
        }
        dao.upsert(
            record.copy(
                downloadedBytes = part.length(),
                status = DownloadStatus.QUEUED,
                error = null,
            ),
        )
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelConstants.WORK_INPUT_MODEL_ID to id))
            .build()
        workManager.enqueueUniqueWork(
            spec.workName,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override suspend fun pauseOfficialDownload(id: String) {
        val spec = requireNotNull(ModelConstants.officialModel(id)) { "Official model not found" }
        workManager.cancelUniqueWork(spec.workName)
        dao.get(id)?.let {
            dao.upsert(
                it.copy(
                    downloadedBytes = File(modelsDirectory(), "${it.fileName}.part").length(),
                    status = DownloadStatus.PAUSED,
                    error = null,
                ),
            )
        }
    }

    override suspend fun importModel(uri: Uri): ModelRecord = withContext(Dispatchers.IO) {
        val id = "import-${UUID.randomUUID()}"
        val displayName = queryDisplayName(uri)?.removeSuffix(".gguf") ?: "Imported model"
        val fileName = "${safeFileName(displayName)}-${id.takeLast(8)}.gguf"
        val destination = File(modelsDirectory(), fileName)
        val temporary = File(modelsDirectory(), "$fileName.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                GgufValidator.validateStream(input)
            } ?: error("Unable to open selected file")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
            } ?: error("Unable to open selected file")
            GgufValidator.validate(temporary).getOrThrow()
            require(temporary.renameTo(destination)) { "Unable to finish importing model" }
            ModelRecord(
                id = id,
                displayName = displayName,
                fileName = fileName,
                localPath = destination.absolutePath,
                sourceRepo = null,
                expectedBytes = destination.length(),
                sha256 = null,
                downloadedBytes = destination.length(),
                status = DownloadStatus.READY,
                error = null,
                selected = false,
            ).also { dao.upsert(it.toEntity()) }
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    override suspend fun selectModel(id: String) {
        val model = requireNotNull(dao.get(id)) { "Model not found" }
        require(model.status == DownloadStatus.READY && model.localPath != null) { "Model is not ready" }
        dao.selectOnly(id)
    }

    override suspend fun selectedModel(): ModelRecord? = dao.getSelected()?.toDomain()

    override suspend fun deleteModel(id: String) {
        val model = requireNotNull(dao.get(id)) { "Model not found" }
        model.localPath?.let { path ->
            require(!isPathInUse(path)) { "Unload this model before deleting it" }
            File(path).delete()
        }
        File(modelsDirectory(), "${model.fileName}.part").delete()
        if (ModelConstants.officialModel(id) != null) {
            dao.upsert(
                model.copy(
                    localPath = null,
                    downloadedBytes = 0,
                    status = DownloadStatus.NOT_DOWNLOADED,
                    error = null,
                    selected = false,
                ),
            )
        } else {
            dao.delete(id)
        }
    }

    override suspend fun testHuggingFaceToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://huggingface.co/api/whoami-v2").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
                require(connection.responseCode in 200..299) { "Hugging Face rejected this token" }
            } finally {
                connection.disconnect()
            }
        }
    }

    override suspend fun modelForQuality(mode: ChatQualityMode): ModelRecord? =
        dao.get(
            when (mode) {
                ChatQualityMode.FAST -> ModelConstants.GEMMA_4_E4B.id
                ChatQualityMode.BEST -> ModelConstants.GEMMA_4_12B.id
            },
        )?.toDomain()

    override suspend fun projectorForModel(modelId: String): ProjectorRecord? =
        projectorDao.getForModel(modelId)?.toDomain()

    override suspend fun startProjectorDownload(id: String) {
        ensureOfficialRecords()
        val spec = requireNotNull(ModelConstants.officialProjector(id)) { "Official projector not found" }
        val record = requireNotNull(projectorDao.get(id)) { "Projector record not found" }
        if (record.status == DownloadStatus.READY && record.localPath != null) return
        val part = File(modelsDirectory(), "${spec.fileName}.part")
        val remaining = (spec.sizeBytes - part.length()).coerceAtLeast(0)
        require(StatFs(modelsDirectory().absolutePath).availableBytes > remaining + MIN_FREE_SPACE) {
            "Not enough storage. Free at least ${formatBytes(remaining + MIN_FREE_SPACE)}."
        }
        projectorDao.upsert(
            record.copy(
                downloadedBytes = part.length(),
                status = DownloadStatus.QUEUED,
                error = null,
            ),
        )
        val request = OneTimeWorkRequestBuilder<ProjectorDownloadWorker>()
            .setInputData(workDataOf(ModelConstants.WORK_INPUT_PROJECTOR_ID to id))
            .build()
        workManager.enqueueUniqueWork(spec.workName, ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun pauseProjectorDownload(id: String) {
        val spec = requireNotNull(ModelConstants.officialProjector(id)) { "Official projector not found" }
        workManager.cancelUniqueWork(spec.workName)
        projectorDao.get(id)?.let {
            projectorDao.upsert(
                it.copy(
                    downloadedBytes = File(modelsDirectory(), "${it.fileName}.part").length(),
                    status = DownloadStatus.PAUSED,
                    error = null,
                ),
            )
        }
    }

    override suspend fun deleteProjector(id: String) {
        val record = requireNotNull(projectorDao.get(id)) { "Projector not found" }
        record.localPath?.let { path ->
            require(!isPathInUse(path)) { "Unload vision before deleting this projector" }
            File(path).delete()
        }
        File(modelsDirectory(), "${record.fileName}.part").delete()
        projectorDao.upsert(
            record.copy(
                localPath = null,
                downloadedBytes = 0,
                status = DownloadStatus.NOT_DOWNLOADED,
                error = null,
            ),
        )
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return uri.lastPathSegment
    }

    private fun safeFileName(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "model" }

    private companion object {
        const val MIN_FREE_SPACE = 2L * 1024 * 1024 * 1024
        val ACTIVE_DOWNLOAD_STATES = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.VERIFYING,
            DownloadStatus.PAUSED,
        )
    }
}

internal fun ModelRecordEntity.toDomain() = ModelRecord(
    id, displayName, fileName, localPath, sourceRepo, expectedBytes, sha256,
    downloadedBytes, status, error, selected,
)

internal fun ModelRecord.toEntity() = ModelRecordEntity(
    id, displayName, fileName, localPath, sourceRepo, expectedBytes, sha256,
    downloadedBytes, status, error, selected,
)

internal fun ProjectorRecordEntity.toDomain() = ProjectorRecord(
    id, modelId, displayName, fileName, localPath, sourceRepo, expectedBytes, sha256,
    downloadedBytes, status, error,
)

fun formatBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024 * 1024 * 1024)
    return if (gib >= 1) "%.2f GB".format(gib) else "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
}
