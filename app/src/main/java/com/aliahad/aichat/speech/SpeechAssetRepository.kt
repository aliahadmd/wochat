package com.aliahad.aichat.speech

import android.content.Context
import android.os.StatFs
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aliahad.aichat.core.DownloadStatus
import com.aliahad.aichat.core.SpeechAssetKind
import com.aliahad.aichat.core.SpeechAssetRecord
import com.aliahad.aichat.data.SpeechAssetDao
import com.aliahad.aichat.data.SpeechAssetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

interface SpeechAssetRepository {
    val assets: Flow<List<SpeechAssetRecord>>
    fun speechDirectory(): File
    suspend fun ensureOfficialRecords()
    suspend fun startDownload(id: String)
    suspend fun pauseDownload(id: String)
    suspend fun delete(id: String)
    suspend fun readyDirectory(kind: SpeechAssetKind): File?
}

class DefaultSpeechAssetRepository(
    private val context: Context,
    private val dao: SpeechAssetDao,
) : SpeechAssetRepository {
    private val workManager = WorkManager.getInstance(context)

    override val assets: Flow<List<SpeechAssetRecord>> =
        dao.observeAll().map { rows -> rows.map(SpeechAssetEntity::toDomain) }

    override fun speechDirectory(): File =
        File(context.noBackupFilesDir, "speech").apply { mkdirs() }

    override suspend fun ensureOfficialRecords() {
        SpeechAssetConstants.OFFICIAL_ASSETS.forEach { spec ->
            val existing = dao.get(spec.id)
            val installed = File(speechDirectory(), spec.installDirectory)
            val ready = SafeSpeechArchiveExtractor.validateInstallation(installed, spec)
            val partial = File(speechDirectory(), "${spec.archiveFileName}.part")
            if (existing?.archiveFileName != null &&
                existing.archiveFileName != spec.archiveFileName
            ) {
                File(speechDirectory(), "${existing.archiveFileName}.part").delete()
            }
            dao.upsert(
                SpeechAssetEntity(
                    id = spec.id,
                    kind = spec.kind,
                    displayName = spec.displayName,
                    archiveFileName = spec.archiveFileName,
                    localPath = installed.takeIf { ready }?.absolutePath,
                    sourceUrl = spec.downloadUrl,
                    expectedBytes = spec.sizeBytes,
                    sha256 = spec.sha256,
                    downloadedBytes = if (ready) spec.sizeBytes else partial.length(),
                    status = when {
                        ready -> DownloadStatus.READY
                        existing?.status in ACTIVE_STATES -> requireNotNull(existing).status
                        partial.exists() -> DownloadStatus.PAUSED
                        else -> DownloadStatus.NOT_DOWNLOADED
                    },
                    error = if (ready) null else existing?.error,
                ),
            )
        }
    }

    override suspend fun startDownload(id: String) {
        ensureOfficialRecords()
        val spec = requireNotNull(SpeechAssetConstants.officialAsset(id)) { "Speech model not found" }
        val record = requireNotNull(dao.get(id)) { "Speech model record not found" }
        if (record.status == DownloadStatus.READY && record.localPath != null) return
        val partial = File(speechDirectory(), "${spec.archiveFileName}.part")
        val remaining = (spec.sizeBytes - partial.length()).coerceAtLeast(0)
        require(StatFs(speechDirectory().absolutePath).availableBytes > remaining + MIN_FREE_SPACE) {
            "Not enough storage for the speech model."
        }
        dao.upsert(
            record.copy(
                downloadedBytes = partial.length(),
                status = DownloadStatus.QUEUED,
                error = null,
            ),
        )
        val request = OneTimeWorkRequestBuilder<SpeechAssetDownloadWorker>()
            .setInputData(workDataOf(SpeechAssetConstants.WORK_INPUT_ASSET_ID to id))
            .build()
        workManager.enqueueUniqueWork(spec.workName, ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun pauseDownload(id: String) {
        val spec = requireNotNull(SpeechAssetConstants.officialAsset(id)) { "Speech model not found" }
        workManager.cancelUniqueWork(spec.workName)
        dao.get(id)?.let { record ->
            dao.upsert(
                record.copy(
                    downloadedBytes = File(
                        speechDirectory(),
                        "${spec.archiveFileName}.part",
                    ).length(),
                    status = DownloadStatus.PAUSED,
                    error = null,
                ),
            )
        }
    }

    override suspend fun delete(id: String) {
        val spec = requireNotNull(SpeechAssetConstants.officialAsset(id)) { "Speech model not found" }
        workManager.cancelUniqueWork(spec.workName)
        val installation = File(speechDirectory(), spec.installDirectory)
        require(!installation.exists() || installation.deleteRecursively()) {
            "Unable to delete the installed speech model"
        }
        File(speechDirectory(), "${spec.archiveFileName}.part").delete()
        File(speechDirectory(), ".install-${spec.id}").deleteRecursively()
        dao.get(id)?.let {
            dao.upsert(
                it.copy(
                    localPath = null,
                    downloadedBytes = 0,
                    status = DownloadStatus.NOT_DOWNLOADED,
                    error = null,
                ),
            )
        }
    }

    override suspend fun readyDirectory(kind: SpeechAssetKind): File? {
        val record = dao.getByKind(kind) ?: return null
        val directory = record.localPath?.let(::File) ?: return null
        val spec = SpeechAssetConstants.officialAsset(kind)
        return directory.takeIf {
            record.status == DownloadStatus.READY &&
                SafeSpeechArchiveExtractor.validateInstallation(it, spec)
        }
    }

    private companion object {
        const val MIN_FREE_SPACE = 256L * 1024 * 1024
        val ACTIVE_STATES = setOf(
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.VERIFYING,
        )
    }
}

private fun SpeechAssetEntity.toDomain(): SpeechAssetRecord = SpeechAssetRecord(
    id = id,
    kind = kind,
    displayName = displayName,
    archiveFileName = archiveFileName,
    localPath = localPath,
    sourceUrl = sourceUrl,
    expectedBytes = expectedBytes,
    sha256 = sha256,
    downloadedBytes = downloadedBytes,
    status = status,
    error = error,
)
