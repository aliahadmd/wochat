package com.aliahad.aichat.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.ext.SdkExtensions
import android.text.Html
import android.util.Xml
import com.aliahad.aichat.core.AttachmentKind
import com.aliahad.aichat.data.AttachmentChunkEntity
import com.aliahad.aichat.data.AttachmentEntity
import org.xmlpull.v1.XmlPullParser
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

internal data class AttachmentProcessingResult(
    val previewPath: String?,
    val imagePaths: List<String>,
    val pageCount: Int?,
    val selectedPages: Set<Int>,
    val imageTokenBudget: Int?,
    val chunks: List<AttachmentChunkEntity>,
)

internal class AttachmentProcessor(
    private val context: Context,
) {
    fun process(entity: AttachmentEntity): AttachmentProcessingResult = when (entity.kind) {
        AttachmentKind.IMAGE -> processImage(entity)
        AttachmentKind.PDF -> processPdf(entity)
        AttachmentKind.DOCX,
        AttachmentKind.XLSX,
        AttachmentKind.PPTX -> processOoxml(entity)
        else -> processText(entity)
    }

    fun renderSelectedPdfPages(entity: AttachmentEntity, pages: Set<Int>): List<String> {
        require(entity.kind == AttachmentKind.PDF) { "Page selection is only available for PDFs." }
        val existing = entity.derivedImagePaths.toPathList().toMutableList()
        val existingPages = existing.mapNotNull {
            Regex("page-(\\d+)").find(File(it).name)?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()
        val missing = pages - existingPages
        if (missing.isEmpty()) return existing
        val source = File(entity.originalPath)
        val pageDirectory = File(source.parentFile, "pages").apply { mkdirs() }
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                missing.sorted().forEach { pageNumber ->
                    require(pageNumber in 1..renderer.pageCount) { "PDF page $pageNumber does not exist." }
                    renderer.openPage(pageNumber - 1).use { page ->
                        val output = File(pageDirectory, "page-$pageNumber.png")
                        renderPdfPage(page, output, PDF_RENDER_MAX_DIMENSION)
                        existing += output.absolutePath
                    }
                }
            }
        }
        return existing.distinct().sortedBy {
            Regex("page-(\\d+)").find(File(it).name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        }
    }

    private fun processImage(entity: AttachmentEntity): AttachmentProcessingResult {
        val directory = File(entity.originalPath).parentFile ?: error("Attachment directory is missing.")
        val normalized = normalizeImage(
            source = File(entity.originalPath),
            destinationBase = File(directory, "vision"),
            preferPng = entity.mimeType.contains("png") || entity.displayName.endsWith(".png", true),
            maxDimension = 2048,
        )
        val preview = normalizeImage(
            source = normalized,
            destinationBase = File(directory, "preview"),
            preferPng = true,
            maxDimension = 720,
        )
        return AttachmentProcessingResult(
            previewPath = preview.absolutePath,
            imagePaths = listOf(normalized.absolutePath),
            pageCount = null,
            selectedPages = emptySet(),
            imageTokenBudget = if (isTextHeavyImage(normalized)) 560 else 280,
            chunks = emptyList(),
        )
    }

    private fun processText(entity: AttachmentEntity): AttachmentProcessingResult {
        val file = File(entity.originalPath)
        require(file.length() <= MAX_TEXT_BYTES) { "Text files larger than 20 MB are not supported." }
        val raw = file.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val text = if (entity.kind == AttachmentKind.HTML) {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            raw
        }
        return AttachmentProcessingResult(
            previewPath = null,
            imagePaths = emptyList(),
            pageCount = null,
            selectedPages = emptySet(),
            imageTokenBudget = null,
            chunks = text.chunkedFor(entity.id, entity.displayName),
        )
    }

    private fun processPdf(entity: AttachmentEntity): AttachmentProcessingResult {
        val source = File(entity.originalPath)
        val directory = source.parentFile ?: error("Attachment directory is missing.")
        val pagesDirectory = File(directory, "pages").apply { mkdirs() }
        val chunks = mutableListOf<AttachmentChunkEntity>()
        val images = mutableListOf<String>()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount <= MAX_PDF_PAGES) {
                    "PDF has more than $MAX_PDF_PAGES pages."
                }
                repeat(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        val pageNumber = index + 1
                        val text = if (supportsPdfTextExtraction()) {
                            runCatching { extractPdfText(page) }.getOrDefault("")
                        } else {
                            ""
                        }.trim()
                        if (text.isNotEmpty()) {
                            text.chunked(CHUNK_CHARS).forEachIndexed { chunkIndex, value ->
                                chunks += AttachmentChunkEntity(
                                    id = UUID.randomUUID().toString(),
                                    attachmentId = entity.id,
                                    ordinal = chunks.size,
                                    pageNumber = pageNumber,
                                    label = "Page $pageNumber.${chunkIndex + 1}",
                                    content = value,
                                )
                            }
                        }
                        if (text.length < SCANNED_PAGE_TEXT_THRESHOLD || pageNumber <= PDF_PREVIEW_PAGES) {
                            val image = File(pagesDirectory, "page-$pageNumber.png")
                            renderPdfPage(page, image, PDF_RENDER_MAX_DIMENSION)
                            images += image.absolutePath
                        }
                    }
                }
                return AttachmentProcessingResult(
                    previewPath = images.firstOrNull(),
                    imagePaths = images,
                    pageCount = renderer.pageCount,
                    selectedPages = emptySet(),
                    imageTokenBudget = 560,
                    chunks = chunks,
                )
            }
        }
    }

    private fun processOoxml(entity: AttachmentEntity): AttachmentProcessingResult {
        val source = File(entity.originalPath)
        val directory = source.parentFile ?: error("Attachment directory is missing.")
        val mediaDirectory = File(directory, "media").apply { mkdirs() }
        val chunks = mutableListOf<AttachmentChunkEntity>()
        val images = mutableListOf<String>()
        var totalExpanded = 0L
        var entries = 0

        ZipInputStream(BufferedInputStream(source.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ZIP_ENTRIES) { "Document contains too many ZIP entries." }
                require(!entry.name.contains("../") && !entry.name.startsWith('/')) {
                    "Document contains an unsafe ZIP path."
                }
                val limit = when {
                    isRelevantXml(entity.kind, entry.name) -> MAX_XML_ENTRY_BYTES
                    isMediaEntry(entry.name) -> MAX_MEDIA_ENTRY_BYTES
                    else -> {
                        zip.closeEntry()
                        continue
                    }
                }
                val bytes = zip.readLimited(limit)
                totalExpanded += bytes.size
                require(totalExpanded <= MAX_EXPANDED_BYTES) { "Document expands beyond the safe limit." }
                if (entry.compressedSize > 0) {
                    require(bytes.size.toLong() / entry.compressedSize.coerceAtLeast(1) <= MAX_COMPRESSION_RATIO) {
                        "Document has an unsafe compression ratio."
                    }
                }
                if (isMediaEntry(entry.name)) {
                    val extension = entry.name.substringAfterLast('.', "bin").lowercase()
                    if (extension in IMAGE_EXTENSIONS) {
                        val raw = File(mediaDirectory, "raw-${images.size}.$extension")
                        raw.writeBytes(bytes)
                        runCatching {
                            normalizeImage(
                                source = raw,
                                destinationBase = File(mediaDirectory, "image-${images.size}"),
                                preferPng = extension == "png",
                                maxDimension = 1600,
                            )
                        }.onSuccess { images += it.absolutePath }
                        raw.delete()
                    }
                } else {
                    val text = extractXmlText(bytes.inputStream())
                    if (text.isNotBlank()) {
                        text.chunked(CHUNK_CHARS).forEachIndexed { index, value ->
                            chunks += AttachmentChunkEntity(
                                id = UUID.randomUUID().toString(),
                                attachmentId = entity.id,
                                ordinal = chunks.size,
                                pageNumber = slideNumber(entry, entity.kind),
                                label = "${entry.name} #${index + 1}",
                                content = value,
                            )
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        require(chunks.isNotEmpty() || images.isNotEmpty()) {
            "No readable content was found. Password-protected or malformed Office files are unsupported."
        }
        return AttachmentProcessingResult(
            previewPath = images.firstOrNull(),
            imagePaths = images,
            pageCount = if (entity.kind == AttachmentKind.PPTX) {
                chunks.mapNotNull(AttachmentChunkEntity::pageNumber).maxOrNull()
            } else null,
            selectedPages = emptySet(),
            imageTokenBudget = images.takeIf { it.isNotEmpty() }?.let { 560 },
            chunks = chunks,
        )
    }

    private fun normalizeImage(
        source: File,
        destinationBase: File,
        preferPng: Boolean,
        maxDimension: Int,
    ): File {
        val imageSource = ImageDecoder.createSource(source)
        val bitmap = ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = minOf(1f, maxDimension.toFloat() / maxOf(width, height))
            decoder.setTargetSize(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        require(bitmap.width > 0 && bitmap.height > 0) { "Image dimensions are invalid." }
        val extension = if (preferPng) "png" else "jpg"
        val destination = File(destinationBase.parentFile, "${destinationBase.name}.$extension")
        FileOutputStream(destination).use { output ->
            val format = if (preferPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            require(bitmap.compress(format, if (preferPng) 100 else 92, output)) {
                "Unable to normalize image."
            }
        }
        bitmap.recycle()
        return destination
    }

    private fun renderPdfPage(page: PdfRenderer.Page, destination: File, maxDimension: Int) {
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(page.width, page.height))
        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        destination.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun supportsPdfTextExtraction(): Boolean =
        Build.VERSION.SDK_INT >= 35 &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13

    @RequiresApi(35)
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun extractPdfText(page: PdfRenderer.Page): String =
        page.textContents.joinToString("\n") { it.text }

    private fun isTextHeavyImage(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val ratio = maxOf(options.outWidth, options.outHeight).toFloat() /
            minOf(options.outWidth, options.outHeight).coerceAtLeast(1)
        return file.extension.equals("png", true) || ratio > 1.8f
    }

    private fun extractXmlText(input: InputStream): String {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, "UTF-8")
        }
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.TEXT -> {
                    val value = parser.text?.trim().orEmpty()
                    if (value.isNotEmpty()) text.append(value).append(' ')
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name in BLOCK_TAGS) text.append('\n')
                }
            }
            event = parser.next()
        }
        return text.toString().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun isRelevantXml(kind: AttachmentKind, name: String): Boolean = when (kind) {
        AttachmentKind.DOCX ->
            name == "word/document.xml" || name.startsWith("word/header") ||
                name.startsWith("word/footer") || name.startsWith("word/footnotes") ||
                name.startsWith("word/endnotes")
        AttachmentKind.XLSX ->
            name == "xl/sharedStrings.xml" || name.startsWith("xl/worksheets/") ||
                name == "xl/workbook.xml"
        AttachmentKind.PPTX ->
            name.startsWith("ppt/slides/slide") || name.startsWith("ppt/notesSlides/notesSlide")
        else -> false
    }

    private fun isMediaEntry(name: String): Boolean =
        name.startsWith("word/media/") || name.startsWith("xl/media/") || name.startsWith("ppt/media/")

    private fun slideNumber(entry: ZipEntry, kind: AttachmentKind): Int? {
        if (kind != AttachmentKind.PPTX) return null
        return Regex("(?:slide|notesSlide)(\\d+)\\.xml").find(entry.name)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun String.chunkedFor(attachmentId: String, label: String): List<AttachmentChunkEntity> =
        chunked(CHUNK_CHARS).mapIndexed { index, value ->
            AttachmentChunkEntity(
                id = UUID.randomUUID().toString(),
                attachmentId = attachmentId,
                ordinal = index,
                pageNumber = null,
                label = "$label #${index + 1}",
                content = value,
            )
        }

    private fun ZipInputStream.readLimited(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Document entry exceeds the safe size limit." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_TEXT_BYTES = 20L * 1024 * 1024
        const val MAX_PDF_PAGES = 500
        const val PDF_PREVIEW_PAGES = 8
        const val PDF_RENDER_MAX_DIMENSION = 1400
        const val SCANNED_PAGE_TEXT_THRESHOLD = 48
        const val CHUNK_CHARS = 3_000
        const val MAX_ZIP_ENTRIES = 10_000
        const val MAX_XML_ENTRY_BYTES = 32L * 1024 * 1024
        const val MAX_MEDIA_ENTRY_BYTES = 40L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 256L * 1024 * 1024
        const val MAX_COMPRESSION_RATIO = 200L
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        val BLOCK_TAGS = setOf("p", "tr", "row", "si", "t", "br", "tab")
    }
}
