package com.aliahad.aichat.model

import android.content.Context
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.ModelRecord
import com.aliahad.aichat.core.ProjectorRecord
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

interface ModelRepository {
    val models: Flow<List<ModelRecord>>

    /** The sentence embedder, which is downloadable but never a chat model. */
    val embeddingModel: Flow<ModelRecord?>
    val projectors: Flow<List<ProjectorRecord>>
    fun modelsDirectory(): File
    suspend fun ensureOfficialRecords()
    suspend fun startOfficialDownload(id: String, allowMetered: Boolean = false)
    suspend fun pauseOfficialDownload(id: String)
    suspend fun selectModel(id: String)
    suspend fun selectedModel(): ModelRecord?
    suspend fun ensureSha256(model: ModelRecord): ModelRecord
    suspend fun hasActiveTransfers(): Boolean
    suspend fun deleteModel(id: String)
    suspend fun testHuggingFaceToken(token: String): Result<Unit>
    suspend fun projectorForModel(modelId: String): ProjectorRecord?
    suspend fun startProjectorDownload(id: String, allowMetered: Boolean = false)
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
        dao.observeAll().map { rows ->
            rows.filter { it.id == ModelConstants.GEMMA_4_E4B.id }
                .map(ModelRecordEntity::toDomain)
        }
    override val embeddingModel: Flow<ModelRecord?> =
        dao.observeAll().map { rows ->
            rows.firstOrNull { it.id == ModelConstants.EMBEDDING_GEMMA_300M.id }
                ?.toDomain()
        }
    override val projectors: Flow<List<ProjectorRecord>> =
        projectorDao.observeAll().map { rows ->
            rows.filter { it.id == ModelConstants.GEMMA_4_E4B_PROJECTOR.id }
                .map(ProjectorRecordEntity::toDomain)
        }

    override fun modelsDirectory(): File =
        File(context.noBackupFilesDir, "models").apply { mkdirs() }

    override suspend fun ensureOfficialRecords() {
        recoverInterruptedInstalls()
        retireUnsupportedArtifacts()
        // Embedding models get the same download/verify/resume machinery as the
        // chat model. They are filtered out of the `models` flow above, so they can
        // never be picked as a chat model.
        (ModelConstants.OFFICIAL_MODELS + ModelConstants.EMBEDDING_MODELS).forEach { spec ->
            val existing = dao.get(spec.id)
            val finalFile = File(modelsDirectory(), spec.fileName)
            val part = File(modelsDirectory(), "${spec.fileName}.part")
            val metadataChanged = existing != null && (
                existing.fileName != spec.fileName ||
                    existing.sourceRepo != spec.repository ||
                    existing.expectedBytes != spec.sizeBytes ||
                    !existing.sha256.equals(spec.sha256, ignoreCase = true)
                )
            if (metadataChanged) {
                workManager.cancelUniqueWork(spec.workName)
                part.delete()
                existing?.localPath?.let(::File)?.takeUnless { isPathInUse(it.absolutePath) }?.delete()
            }
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
                val localFileExists = !metadataChanged &&
                    existing.localPath?.let(::File)?.let { it.exists() && it.length() == spec.sizeBytes } == true
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
                        else -> part.length()
                    },
                    status = when {
                        fileReady -> DownloadStatus.READY
                        localFileExists -> existing.status
                        !metadataChanged && existing.status in ACTIVE_DOWNLOAD_STATES -> existing.status
                        else -> DownloadStatus.NOT_DOWNLOADED
                    },
                    error = if (fileReady || metadataChanged) null else existing.error,
                    selected = existing.selected && (fileReady || localFileExists),
                )
                if (reconciled != existing) dao.upsert(reconciled)
            }
        }
        ModelConstants.OFFICIAL_PROJECTORS.forEach { spec ->
            val existing = projectorDao.get(spec.id)
            val finalFile = File(modelsDirectory(), spec.fileName)
            val part = File(modelsDirectory(), "${spec.fileName}.part")
            val metadataChanged = existing != null && (
                existing.fileName != spec.fileName ||
                    existing.sourceRepo != spec.repository ||
                    existing.expectedBytes != spec.sizeBytes ||
                    !existing.sha256.equals(spec.sha256, ignoreCase = true)
                )
            if (metadataChanged) {
                workManager.cancelUniqueWork(spec.workName)
                part.delete()
                existing?.localPath?.let(::File)?.takeUnless { isPathInUse(it.absolutePath) }?.delete()
            }
            val fileReady = finalFile.exists() && finalFile.length() == spec.sizeBytes
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
                        !metadataChanged && existing?.status in ACTIVE_DOWNLOAD_STATES ->
                            requireNotNull(existing).status
                        else -> DownloadStatus.NOT_DOWNLOADED
                    },
                    error = if (fileReady || metadataChanged) null else existing?.error,
                ),
            )
        }
    }

    override suspend fun startOfficialDownload(id: String, allowMetered: Boolean) {
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
                bytesPerSecond = 0,
                etaSeconds = null,
                retryAttempt = 0,
            ),
        )
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelConstants.WORK_INPUT_MODEL_ID to id))
            .setConstraints(downloadConstraints(allowMetered))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
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
                    bytesPerSecond = 0,
                    etaSeconds = null,
                ),
            )
        }
    }

    override suspend fun selectModel(id: String) {
        val model = requireNotNull(dao.get(id)) { "Model not found" }
        require(model.status == DownloadStatus.READY && model.localPath != null) { "Model is not ready" }
        dao.selectOnly(id)
    }

    override suspend fun selectedModel(): ModelRecord? = dao.getSelected()?.toDomain()

    override suspend fun ensureSha256(model: ModelRecord): ModelRecord = withContext(Dispatchers.IO) {
        model.sha256?.takeIf(String::isNotBlank)?.let { return@withContext model }
        val path = requireNotNull(model.localPath) { "The model file is unavailable" }
        val digest = sha256(File(path))
        dao.updateSha256(model.id, digest)
        model.copy(sha256 = digest)
    }

    override suspend fun hasActiveTransfers(): Boolean =
        dao.activeTransferCount() > 0 || projectorDao.activeTransferCount() > 0

    override suspend fun deleteModel(id: String) {
        val model = requireNotNull(dao.get(id)) { "Model not found" }
        model.localPath?.let { path ->
            require(!isPathInUse(path)) { "Unload this model before deleting it" }
            val file = File(path)
            require(!file.exists() || file.delete()) { "Unable to delete the model file" }
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

    override suspend fun projectorForModel(modelId: String): ProjectorRecord? =
        projectorDao.getForModel(modelId)?.toDomain()

    override suspend fun startProjectorDownload(id: String, allowMetered: Boolean) {
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
                bytesPerSecond = 0,
                etaSeconds = null,
                retryAttempt = 0,
            ),
        )
        val request = OneTimeWorkRequestBuilder<ProjectorDownloadWorker>()
            .setInputData(workDataOf(ModelConstants.WORK_INPUT_PROJECTOR_ID to id))
            .setConstraints(downloadConstraints(allowMetered))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
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
                    bytesPerSecond = 0,
                    etaSeconds = null,
                ),
            )
        }
    }

    override suspend fun deleteProjector(id: String) {
        val record = requireNotNull(projectorDao.get(id)) { "Projector not found" }
        record.localPath?.let { path ->
            require(!isPathInUse(path)) { "Unload vision before deleting this projector" }
            val file = File(path)
            require(!file.exists() || file.delete()) { "Unable to delete the projector file" }
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

    /**
     * Restores `*.old` install backups left by a process death inside AtomicFileInstaller's
     * rename sequence, before the retire sweep below can classify them as stray files. Must
     * run before [retireUnsupportedArtifacts].
     */
    private suspend fun recoverInterruptedInstalls() = withContext(Dispatchers.IO) {
        modelsDirectory().listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".old") }
            .forEach { backup ->
                AtomicFileInstaller.recoverInterrupted(
                    File(modelsDirectory(), backup.name.removeSuffix(".old")),
                )
            }
    }

    private suspend fun retireUnsupportedArtifacts() = withContext(Dispatchers.IO) {
        // Every spec the app still ships, not just the chat model. Embedding models
        // live in the same table and directory, so leaving them out of these two sets
        // meant this sweep deleted a freshly downloaded embedder — record and 318 MB
        // file — on the very next app start, silently and after a successful verify.
        val supportedSpecs = ModelConstants.OFFICIAL_MODELS + ModelConstants.EMBEDDING_MODELS
        val supportedModelIds = supportedSpecs.map { it.id }.toSet()
        val supportedProjector = ModelConstants.GEMMA_4_E4B_PROJECTOR
        dao.getAll().filter { it.id !in supportedModelIds }.forEach { record ->
            workManager.cancelUniqueWork("official-model-download-${record.id}")
            val inUse = record.localPath?.let(isPathInUse) == true
            if (!inUse) {
                record.localPath?.let(::File)?.delete()
                File(modelsDirectory(), "${record.fileName}.part").delete()
                dao.delete(record.id)
            }
        }
        projectorDao.getAll().filter { it.id != supportedProjector.id }.forEach { record ->
            workManager.cancelUniqueWork("official-projector-download-${record.id}")
            val inUse = record.localPath?.let(isPathInUse) == true
            if (!inUse) {
                record.localPath?.let(::File)?.delete()
                File(modelsDirectory(), "${record.fileName}.part").delete()
                projectorDao.delete(record.id)
            }
        }
        val allowedNames = buildSet {
            supportedSpecs.forEach { spec ->
                add(spec.fileName)
                add("${spec.fileName}.part")
            }
            add(supportedProjector.fileName)
            add("${supportedProjector.fileName}.part")
        }
        modelsDirectory().listFiles().orEmpty()
            .filter { it.isFile && it.name !in allowedNames && !isPathInUse(it.absolutePath) }
            .forEach(File::delete)
    }

    private companion object {
        const val MIN_FREE_SPACE = 2L * 1024 * 1024 * 1024
        val ACTIVE_DOWNLOAD_STATES = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.VERIFYING,
            DownloadStatus.PAUSED,
        )
    }

    private fun downloadConstraints(allowMetered: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
}

internal fun ModelRecordEntity.toDomain() = ModelRecord(
    id, displayName, fileName, localPath, sourceRepo, expectedBytes, sha256,
    downloadedBytes, status, error, selected, bytesPerSecond, etaSeconds, retryAttempt,
)

internal fun ModelRecord.toEntity() = ModelRecordEntity(
    id, displayName, fileName, localPath, sourceRepo, expectedBytes, sha256,
    downloadedBytes, status, error, selected, bytesPerSecond, etaSeconds, retryAttempt,
)

internal fun ProjectorRecordEntity.toDomain() = ProjectorRecord(
    id, modelId, displayName, fileName, localPath, sourceRepo, expectedBytes, sha256,
    downloadedBytes, status, error, bytesPerSecond, etaSeconds, retryAttempt,
)

fun formatBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024 * 1024 * 1024)
    return if (gib >= 1) "%.2f GB".format(gib) else "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
