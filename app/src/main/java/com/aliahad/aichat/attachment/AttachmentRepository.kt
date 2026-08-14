package com.aliahad.aichat.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aliahad.aichat.core.Attachment
import com.aliahad.aichat.core.AttachmentContext
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.core.AttachmentProcessingState
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.data.AttachmentDao
import com.aliahad.aichat.data.AttachmentEntity
import com.aliahad.aichat.data.MessageAttachmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

interface AttachmentRepository {
    fun observeDraft(draftKey: String): Flow<List<Attachment>>
    suspend fun stage(draftKey: String, uri: Uri): Attachment
    suspend fun retry(id: String)
    suspend fun remove(id: String)
    suspend fun selectPages(id: String, pages: Set<Int>)
    suspend fun bind(
        messageId: String,
        conversationId: String,
        attachmentIds: List<String>,
        imageTokenBudget: Int?,
    )
    suspend fun contextsForMessage(messageId: String, prompt: String = ""): List<AttachmentContext>
    suspend fun contexts(ids: List<String>, prompt: String): List<AttachmentContext>
    suspend fun attachmentsForMessage(messageId: String): List<Attachment>
    suspend fun attachmentsForMessages(messageIds: List<String>): Map<String, List<Attachment>>
    suspend fun cleanupAbandonedDrafts()
    suspend fun markInterrupted()
    suspend fun hasActiveProcessing(): Boolean
}

class DefaultAttachmentRepository(
    private val context: Context,
    private val database: AppDatabase,
) : AttachmentRepository {
    private val dao = database.attachmentDao()
    private val workManager = WorkManager.getInstance(context)

    override fun observeDraft(draftKey: String): Flow<List<Attachment>> =
        dao.observeDraft(draftKey).map { rows -> rows.map(AttachmentEntity::toDomain) }

    override suspend fun stage(draftKey: String, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val metadata = queryMetadata(uri)
        val kind = AttachmentTypeDetector.detect(metadata.name, metadata.mime)
            ?: error(
                "Unsupported file type. Use images, WAV/MP3/FLAC audio, PDF, text/code, " +
                    "CSV, JSON, XML/HTML, DOCX, XLSX, or PPTX.",
            )
        val maximumBytes = if (kind == AttachmentKind.AUDIO) MAX_AUDIO_BYTES else MAX_SINGLE_BYTES
        require(metadata.size <= maximumBytes) {
            if (kind == AttachmentKind.AUDIO) "Audio files are limited to 25 MB."
            else "File is larger than 500 MB."
        }
        val id = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "attachments/$id").apply { mkdirs() }
        val destination = File(directory, "original-${safeName(metadata.name)}")
        val entity = AttachmentEntity(
            id = id,
            conversationId = null,
            draftKey = draftKey,
            displayName = metadata.name,
            mimeType = metadata.mime,
            kind = kind,
            originalPath = destination.absolutePath,
            previewPath = null,
            derivedImagePaths = "",
            byteSize = metadata.size.coerceAtLeast(0),
            pageCount = null,
            selectedPages = "",
            imageTokenBudget = null,
            state = AttachmentProcessingState.COPYING,
            progress = 0f,
            error = null,
            createdAt = System.currentTimeMillis(),
            durationMillis = null,
        )
        dao.upsert(entity)
        try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                destination.outputStream().buffered().use { output ->
                    copyWithLimit(input, output, maximumBytes)
                }
            } ?: error("Unable to read the selected file.")
            require(destination.length() > 0) { "File is empty." }
            dao.upsert(entity.copy(byteSize = destination.length(), progress = 0.15f))
            enqueue(id)
            entity.copy(byteSize = destination.length(), progress = 0.15f).toDomain()
        } catch (error: Throwable) {
            destination.parentFile?.deleteRecursively()
            dao.delete(id)
            if (error is CancellationException) throw error
            throw error
        }
    }

    override suspend fun retry(id: String) {
        val item = requireNotNull(dao.get(id)) { "Attachment not found." }
        require(File(item.originalPath).exists()) { "The private attachment file is missing." }
        dao.upsert(item.copy(state = AttachmentProcessingState.EXTRACTING, progress = 0.2f, error = null))
        enqueue(id)
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(id))
        dao.get(id)?.let { File(it.originalPath).parentFile?.deleteRecursively() }
        dao.delete(id)
    }

    override suspend fun selectPages(id: String, pages: Set<Int>) {
        val entity = requireNotNull(dao.get(id)) { "Attachment not found." }
        if (entity.kind == AttachmentKind.PDF && pages.isNotEmpty()) {
            val images = AttachmentProcessor(context).renderSelectedPdfPages(entity, pages)
            dao.updateSelectedPagesAndImages(
                id,
                pages.sorted().joinToString(","),
                images.joinToString("\n"),
            )
        } else {
            dao.updateSelectedPages(id, pages.sorted().joinToString(","))
        }
    }

    override suspend fun bind(
        messageId: String,
        conversationId: String,
        attachmentIds: List<String>,
        imageTokenBudget: Int?,
    ) {
        if (attachmentIds.isEmpty()) return
        database.withTransaction {
            dao.assignToConversation(attachmentIds, conversationId)
            imageTokenBudget?.let { dao.updateImageTokenBudget(attachmentIds, it) }
            dao.bind(attachmentIds.mapIndexed { index, id -> MessageAttachmentEntity(messageId, id, index) })
        }
    }

    override suspend fun contextsForMessage(messageId: String, prompt: String): List<AttachmentContext> =
        dao.getForMessage(messageId).map { contextFor(it, prompt) }

    override suspend fun contexts(ids: List<String>, prompt: String): List<AttachmentContext> {
        if (ids.isEmpty()) return emptyList()
        val byId = dao.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull(byId::get).map { contextFor(it, prompt) }
    }

    override suspend fun attachmentsForMessage(messageId: String): List<Attachment> =
        dao.getForMessage(messageId).map(AttachmentEntity::toDomain)

    override suspend fun attachmentsForMessages(messageIds: List<String>): Map<String, List<Attachment>> =
        dao.attachmentsForMessages(messageIds)

    override suspend fun cleanupAbandonedDrafts() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - DRAFT_TTL_MILLIS
        dao.abandonedDrafts(cutoff).forEach {
            File(it.originalPath).parentFile?.deleteRecursively()
            dao.delete(it.id)
        }
    }

    override suspend fun markInterrupted() = dao.markInterrupted()

    override suspend fun hasActiveProcessing(): Boolean = dao.activeProcessingCount() > 0

    private suspend fun contextFor(entity: AttachmentEntity, prompt: String): AttachmentContext {
        require(entity.state == AttachmentProcessingState.READY) {
            "${entity.displayName} is not ready."
        }
        val selectedPages = entity.selectedPages.toPageSet()
        val keywords = prompt.keywordList()
        val chunks = dao.chunks(entity.id)
            .filter { selectedPages.isEmpty() || it.pageNumber == null || it.pageNumber in selectedPages }
            .sortedByDescending { chunk ->
                keywords.sumOf { keyword ->
                    Regex("\\b${Regex.escape(keyword)}", RegexOption.IGNORE_CASE)
                        .findAll(chunk.content)
                        .count()
                }
            }
            .take(MAX_CONTEXT_CHUNKS)
        val images = entity.derivedImagePaths.toPathList().let { paths ->
            if (entity.kind == AttachmentKind.PDF && selectedPages.isEmpty() && chunks.isNotEmpty()) {
                val relevantPages = chunks.mapNotNull { it.pageNumber }.toSet()
                paths.filter { path ->
                    PAGE_FILE.find(File(path).name)?.groupValues?.get(1)?.toIntOrNull() in relevantPages
                }
            } else if (selectedPages.isEmpty() || entity.kind != AttachmentKind.PDF) paths
            else paths.filter { path ->
                PAGE_FILE.find(File(path).name)?.groupValues?.get(1)?.toIntOrNull() in selectedPages
            }
        }
        return AttachmentContext(
            attachmentId = entity.id,
            displayName = entity.displayName,
            extractedText = chunks.joinToString("\n\n") { chunk ->
                buildString {
                    append("[")
                    append(entity.displayName)
                    chunk.pageNumber?.let { append(", page $it") }
                    append("]\n")
                    append(chunk.content)
                }
            }.take(MAX_CONTEXT_CHARS),
            imagePaths = images.take(MAX_IMAGES_PER_ATTACHMENT),
            selectedPages = selectedPages,
            imageTokenBudget = entity.imageTokenBudget ?: 280,
            audioPaths = if (entity.kind == AttachmentKind.AUDIO) {
                listOf(entity.originalPath)
            } else {
                emptyList()
            },
            audioTokenEstimate = entity.durationMillis
                ?.let { AudioAttachmentInspector.estimateAudioTokens(it) }
                ?: 0,
        )
    }

    private fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<AttachmentProcessingWorker>()
            .setInputData(workDataOf(AttachmentProcessingWorker.ATTACHMENT_ID to id))
            .build()
        workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun queryMetadata(uri: Uri): Metadata {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        var size = -1L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    cursor.getString(it)?.let { value -> name = value }
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                    if (!cursor.isNull(it)) size = cursor.getLong(it)
                }
            }
        }
        if (size < 0) {
            size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0
        }
        return Metadata(name, context.contentResolver.getType(uri) ?: "application/octet-stream", size)
    }

    private data class Metadata(val name: String, val mime: String, val size: Long)

    private fun copyWithLimit(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maximumBytes: Long,
    ) {
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximumBytes) { "File is larger than 500 MB." }
            output.write(buffer, 0, count)
        }
    }

    private companion object {
        const val MAX_SINGLE_BYTES = 500L * 1024 * 1024
        const val MAX_AUDIO_BYTES = AudioAttachmentInspector.MAX_AUDIO_BYTES
        const val COPY_BUFFER = 256 * 1024
        const val DRAFT_TTL_MILLIS = 24L * 60 * 60 * 1000
        const val MAX_CONTEXT_CHUNKS = 12
        const val MAX_CONTEXT_CHARS = 36_000
        const val MAX_IMAGES_PER_ATTACHMENT = 6
        val PAGE_FILE = Regex("page-(\\d+)")
        fun workName(id: String) = "attachment-process-$id"
    }
}

/** Loads attachments for many messages with a single batched query, grouped by message id. */
internal suspend fun AttachmentDao.attachmentsForMessages(
    messageIds: List<String>,
): Map<String, List<Attachment>> {
    if (messageIds.isEmpty()) return emptyMap()
    return getForMessages(messageIds)
        .groupBy({ it.messageId }, { it.attachment.toDomain() })
}

internal object AttachmentTypeDetector {
    fun detect(name: String, mime: String): AttachmentKind? {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") -> AttachmentKind.IMAGE
            mime in setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/mpeg", "audio/mp3", "audio/flac") ||
                extension in setOf("wav", "mp3", "flac") -> AttachmentKind.AUDIO
            mime == "application/pdf" || extension == "pdf" -> AttachmentKind.PDF
            extension == "docx" -> AttachmentKind.DOCX
            extension == "xlsx" -> AttachmentKind.XLSX
            extension == "pptx" -> AttachmentKind.PPTX
            extension == "csv" || mime == "text/csv" -> AttachmentKind.CSV
            extension == "json" || mime == "application/json" -> AttachmentKind.JSON
            extension == "xml" || mime.endsWith("/xml") -> AttachmentKind.XML
            extension in setOf("html", "htm") || mime == "text/html" -> AttachmentKind.HTML
            extension in CODE_EXTENSIONS -> AttachmentKind.CODE
            mime.startsWith("text/") || extension in setOf("txt", "md", "markdown") -> AttachmentKind.TEXT
            else -> null
        }
    }

    private val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "c", "cc", "cpp", "h", "hpp", "py", "js", "ts", "tsx",
        "jsx", "rs", "go", "swift", "sql", "sh", "zsh", "yaml", "yml", "toml", "gradle",
    )
}

internal fun AttachmentEntity.toDomain() = Attachment(
    id = id,
    conversationId = conversationId,
    draftKey = draftKey,
    displayName = displayName,
    mimeType = mimeType,
    kind = kind,
    originalPath = originalPath,
    previewPath = previewPath,
    derivedImagePaths = derivedImagePaths.toPathList(),
    byteSize = byteSize,
    pageCount = pageCount,
    selectedPages = selectedPages.toPageSet(),
    imageTokenBudget = imageTokenBudget,
    state = state,
    progress = progress,
    error = error,
    createdAt = createdAt,
    durationMillis = durationMillis,
)

internal fun String.toPathList(): List<String> = lineSequence().filter(String::isNotBlank).toList()
internal fun String.toPageSet(): Set<Int> =
    split(',').mapNotNull(String::toIntOrNull).toSet()
internal fun String.keywordList(): List<String> =
    lowercase().split(Regex("[^\\p{L}\\p{N}_]+")).filter { it.length >= 3 }.distinct().take(8)
internal fun safeName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(120).ifEmpty { "attachment" }
