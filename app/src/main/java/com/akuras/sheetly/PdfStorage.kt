package com.akuras.sheetly

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Immutable
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

/** Render PDF pages at this multiple of their native size to preserve quality (~216 DPI). */
private const val RENDER_SCALE = 3
/** Upper bound for a single rendered page, so oversized pages cannot exhaust the heap. */
private const val MAX_RENDER_PIXELS = 16_000_000L
private const val THUMBNAIL_WIDTH_PX = 240
/** Long edge of a scanned page, roughly 360 DPI on A4. */
private const val SCAN_MAX_LONG_SIDE_PX = 4200
private const val SCAN_JPEG_QUALITY = 0.9f
/** Scan pages are sized like A4 on their long edge. */
private const val SCAN_PAGE_LONG_SIDE_PT = 842f
private const val MAX_FILE_NAME_LENGTH = 60
private const val PDF_MIME_TYPE = "application/pdf"
private const val DOWNLOADS_SUBFOLDER = "Sheetly"
internal const val STORAGE_TAG = "PdfStorage"

enum class PdfSortOrder {
    DATE_DESC,
    DATE_ASC,
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC,
    SIZE_ASC,
}

@Immutable
data class SavedPdf(
    val name: String,
    val uri: Uri,
    val isExternal: Boolean = false,
    val sizeBytes: Long = 0L,
    val dateAdded: Long = 0L,
)

@Immutable
data class PdfPreview(
    val thumbnail: Bitmap?,
    val pageCount: Int?,
)

object PdfStorage {
    internal val downloadsPath = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBFOLDER"

    internal val downloadsCollection: Uri
        get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Creates a pending Downloads entry, publishes it on success and removes it on any failure. */
    internal inline fun writeToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        relativePath: String = downloadsPath,
        write: (OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            downloadsCollection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: return null

        return try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("No output stream for $uri")
            output.use { write(it) }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            uri
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to write $fileName", e)
            resolver.delete(uri, null, null)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(STORAGE_TAG, "Out of memory while writing $fileName", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    private inline fun <T> withRenderer(context: Context, uri: Uri, block: (PdfRenderer) -> T): T? {
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use(block)
        }
    }

    private fun PdfRenderer.Page.renderToBitmap(targetWidth: Int, targetHeight: Int, mode: Int): Bitmap {
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            render(bitmap, null, null, mode)
        }
    }

    private fun PdfRenderer.Page.renderToWidth(targetWidth: Int): Bitmap {
        val clampedWidth = maxOf(targetWidth, 1)
        val targetHeight = (clampedWidth * height.toFloat() / maxOf(width, 1)).toInt().coerceAtLeast(1)
        return renderToBitmap(clampedWidth, targetHeight, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    }

    /** Renders a page at export quality, scaled down when it would exceed [MAX_RENDER_PIXELS]. */
    private fun PdfRenderer.renderForExport(pageIndex: Int): Bitmap {
        return openPage(pageIndex).use { page ->
            val (targetWidth, targetHeight) = exportSize(page.width, page.height)
            page.renderToBitmap(targetWidth, targetHeight, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        }
    }

    private fun exportSize(pageWidth: Int, pageHeight: Int): Pair<Int, Int> {
        val width = maxOf(pageWidth, 1)
        val height = maxOf(pageHeight, 1)
        val pixels = width.toLong() * height
        val scale = minOf(RENDER_SCALE.toDouble(), sqrt(MAX_RENDER_PIXELS.toDouble() / pixels))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    private fun exportFileName(baseName: String): String {
        return sanitizeFileName(baseName.take(MAX_FILE_NAME_LENGTH), fallback = "export")
    }

    fun savePdfToDownloads(context: Context, sourceUri: Uri, pattern: String): Uri? {
        val fileName = "${sanitizeFileName(resolveFileName(pattern))}.pdf"
        return writeToDownloads(context, fileName, PDF_MIME_TYPE) { output ->
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Cannot read $sourceUri")
            input.use { it.copyTo(output) }
        }
    }

    /**
     * Builds a PDF from the scanner's page images. In [ScanMode.ORIGINAL] the JPEGs are embedded
     * unchanged, the other modes run them through [ScanEnhancer] first.
     */
    fun saveScanToDownloads(context: Context, pageUris: List<Uri>, mode: ScanMode, pattern: String): Uri? {
        if (pageUris.isEmpty()) return null
        PDFBoxResourceLoader.init(context.applicationContext)
        val fileName = "${sanitizeFileName(resolveFileName(pattern))}.pdf"
        return writeToDownloads(context, fileName, PDF_MIME_TYPE) { output ->
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val textLayer = if (loadScanOcr(context)) TextLayerWriter() else null
                pageUris.forEach { pageUri ->
                    val image = if (mode == ScanMode.ORIGINAL) {
                        val input = context.contentResolver.openInputStream(pageUri)
                            ?: throw IOException("Cannot read $pageUri")
                        input.use { JPEGFactory.createFromStream(document, it) }
                    } else {
                        enhancedPageImage(context, document, pageUri, mode)
                    }
                    val page = addImagePage(document, image)
                    if (textLayer != null) {
                        val bitmap = decodeScanPage(context, pageUri)
                        textLayer.add(document, page, bitmap)
                        bitmap.recycle()
                    }
                }
                textLayer?.close()
                document.save(output)
            }
        }
    }

    /** Builds a PDF from finished page bitmaps, which are loaded one at a time and then recycled. */
    fun saveBitmapPagesToDownloads(
        context: Context,
        pageCount: Int,
        pattern: String,
        loadPage: (Int) -> Bitmap,
    ): Uri? {
        if (pageCount <= 0) return null
        PDFBoxResourceLoader.init(context.applicationContext)
        val fileName = "${sanitizeFileName(resolveFileName(pattern))}.pdf"
        return writeToDownloads(context, fileName, PDF_MIME_TYPE) { output ->
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val textLayer = if (loadScanOcr(context)) TextLayerWriter() else null
                for (pageIndex in 0 until pageCount) {
                    val bitmap = loadPage(pageIndex)
                    try {
                        bitmap.setHasAlpha(false)
                        val image = JPEGFactory.createFromImage(document, bitmap, SCAN_JPEG_QUALITY)
                        val page = addImagePage(document, image)
                        textLayer?.add(document, page, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                textLayer?.close()
                document.save(output)
            }
        }
    }

    private fun enhancedPageImage(
        context: Context,
        document: PDDocument,
        pageUri: Uri,
        mode: ScanMode,
    ): PDImageXObject {
        val bitmap = decodeScanPage(context, pageUri)
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            ScanEnhancer.enhance(pixels, width, height, mode)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return JPEGFactory.createFromImage(document, bitmap, SCAN_JPEG_QUALITY)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeScanPage(context: Context, pageUri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, pageUri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > SCAN_MAX_LONG_SIDE_PX) {
                val scale = SCAN_MAX_LONG_SIDE_PX.toFloat() / longSide
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
        bitmap.setHasAlpha(false)
        return bitmap
    }

    private fun addImagePage(document: PDDocument, image: PDImageXObject): PDPage {
        val scale = SCAN_PAGE_LONG_SIDE_PT / maxOf(image.width, image.height)
        val pageWidth = image.width * scale
        val pageHeight = image.height * scale
        val page = PDPage(PDRectangle(pageWidth, pageHeight))
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            content.drawImage(image, 0f, 0f, pageWidth, pageHeight)
        }
        return page
    }

    fun querySavedPdfs(context: Context, sortOrder: PdfSortOrder = PdfSortOrder.DATE_DESC): List<SavedPdf> {
        val resolver = context.contentResolver
        val collection = downloadsCollection
        val items = mutableListOf<SavedPdf>()
        val sort = when (sortOrder) {
            PdfSortOrder.DATE_DESC -> "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            PdfSortOrder.DATE_ASC -> "${MediaStore.MediaColumns.DATE_ADDED} ASC"
            PdfSortOrder.NAME_ASC -> "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC"
            PdfSortOrder.NAME_DESC -> "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE DESC"
            PdfSortOrder.SIZE_DESC -> "${MediaStore.MediaColumns.SIZE} DESC"
            PdfSortOrder.SIZE_ASC -> "${MediaStore.MediaColumns.SIZE} ASC"
        }
        try {
            resolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_ADDED
                ),
                "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(PDF_MIME_TYPE, "$downloadsPath/"),
                sort
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: "scan_$id.pdf"
                    items += SavedPdf(
                        name = name,
                        uri = ContentUris.withAppendedId(collection, id),
                        sizeBytes = cursor.getLong(sizeIdx),
                        dateAdded = cursor.getLong(dateIdx)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to query saved PDFs", e)
        }
        return items
    }

    fun renamePdf(context: Context, uri: Uri, newNameRaw: String): Boolean {
        val baseName = sanitizeFileName(newNameRaw.trim().removeSuffix(".pdf").removeSuffix(".PDF"), fallback = "")
        if (baseName.isEmpty()) return false
        return try {
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.pdf")
                },
                null,
                null
            ) > 0
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to rename PDF $uri", e)
            false
        }
    }

    /** Merges without re-rendering, so text, image quality and file size of the sources are kept. */
    fun mergePdfsToDownloads(context: Context, sourceUris: List<Uri>, pattern: String): Uri? {
        if (sourceUris.size < 2) return null
        PDFBoxResourceLoader.init(context.applicationContext)
        val fileName = "${sanitizeFileName(resolveFileName(pattern))}_merged.pdf"
        return writeToDownloads(context, fileName, PDF_MIME_TYPE) { output ->
            val inputs = mutableListOf<InputStream>()
            try {
                val merger = PDFMergerUtility()
                sourceUris.forEach { sourceUri ->
                    val input = context.contentResolver.openInputStream(sourceUri)
                        ?: throw IOException("Cannot read $sourceUri")
                    inputs += input
                    merger.addSource(input)
                }
                merger.destinationStream = output
                merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            } finally {
                inputs.forEach { it.close() }
            }
        }
    }

    /**
     * Rewrites the PDF with only the pages in [pageOrder], in that order. The original is kept
     * in the cache until the new content is written, so a failed write can restore it.
     */
    fun reorderPdfPages(
        context: Context,
        targetUri: Uri,
        pageOrder: List<Int>,
        rotations: Map<Int, Int> = emptyMap(),
    ): Boolean {
        if (pageOrder.isEmpty() || pageOrder.distinct().size != pageOrder.size) return false
        PDFBoxResourceLoader.init(context.applicationContext)
        val resolver = context.contentResolver
        val original = File.createTempFile("original_", ".pdf", context.cacheDir)
        val edited = File.createTempFile("edited_", ".pdf", context.cacheDir)
        return try {
            val input = resolver.openInputStream(targetUri) ?: return false
            input.use { source -> original.outputStream().use(source::copyTo) }

            PDDocument.load(original, MemoryUsageSetting.setupTempFileOnly()).use { source ->
                if (pageOrder.any { it !in 0 until source.numberOfPages }) return false
                PDDocument().use { result ->
                    pageOrder.forEach { pageIndex ->
                        val page = result.importPage(source.getPage(pageIndex))
                        page.rotation = (page.rotation + (rotations[pageIndex] ?: 0)) % 360
                    }
                    result.save(edited)
                }
            }

            try {
                overwrite(context, targetUri, edited)
            } catch (e: IOException) {
                Log.e(STORAGE_TAG, "Failed to replace PDF, restoring original", e)
                overwrite(context, targetUri, original)
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to reorder PDF pages", e)
            false
        } finally {
            original.delete()
            edited.delete()
        }
    }

    private fun overwrite(context: Context, targetUri: Uri, source: File) {
        val output = context.contentResolver.openOutputStream(targetUri, "wt")
            ?: throw IOException("Cannot write $targetUri")
        output.use { target -> source.inputStream().use { it.copyTo(target) } }
    }

    fun getPdfPageOrder(context: Context, uri: Uri): List<Int> {
        return try {
            withRenderer(context, uri) { renderer -> List(renderer.pageCount) { it } } ?: emptyList()
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to read PDF page order", e)
            emptyList()
        }
    }

    fun countPdfPages(context: Context, uri: Uri): Int? {
        return try {
            withRenderer(context, uri) { it.pageCount }
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to count PDF pages", e)
            null
        }
    }

    fun renderPdfPage(context: Context, uri: Uri, pageIndex: Int, width: Int = 900): Bitmap? {
        return try {
            withRenderer(context, uri) { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page -> page.renderToWidth(width) }
            }
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to render PDF page", e)
            null
        }
    }

    /** Thumbnail and page count from one renderer, so a list item only opens its PDF once. */
    fun loadPreview(context: Context, uri: Uri): PdfPreview {
        return try {
            withRenderer(context, uri) { renderer ->
                val thumbnail = if (renderer.pageCount > 0) {
                    renderer.openPage(0).use { page -> page.renderToWidth(THUMBNAIL_WIDTH_PX) }
                } else {
                    null
                }
                PdfPreview(thumbnail, renderer.pageCount)
            } ?: PdfPreview(null, null)
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to load PDF preview", e)
            PdfPreview(null, null)
        }
    }

    fun sharePdf(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = PDF_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf_chooser)))
    }

    /** [context] must be an Activity context, the chooser is started without a new task. */
    fun sharePdfs(context: Context, items: List<SavedPdf>) {
        if (items.isEmpty()) return
        val uris = ArrayList(items.map { it.uri })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = PDF_MIME_TYPE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_pdf_chooser)))
    }

    fun openPdf(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, PDF_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_pdf_chooser)))
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to open PDF", e)
            Toast.makeText(context, R.string.no_pdf_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun deletePdf(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to delete PDF", e)
            false
        }
    }

    /**
     * Exports every page of the PDF as a separate PNG file into a sub-folder
     * Downloads/Sheetly/<baseName>/.
     * Returns the number of pages exported.
     */
    fun exportPdfAsPngs(context: Context, uri: Uri, baseName: String): Int {
        val safeName = exportFileName(baseName)
        var count = 0
        try {
            withRenderer(context, uri) { renderer ->
                for (pageIndex in 0 until renderer.pageCount) {
                    val bitmap = renderer.renderForExport(pageIndex)
                    try {
                        val saved = writeToDownloads(
                            context,
                            "${safeName}_page${pageIndex + 1}.png",
                            "image/png",
                            "$downloadsPath/$safeName"
                        ) { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        }
                        if (saved != null) count++
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(STORAGE_TAG, "Failed to export PDF as PNG", e)
        } catch (e: OutOfMemoryError) {
            Log.e(STORAGE_TAG, "Out of memory while exporting PDF as PNG", e)
        }
        return count
    }

    /**
     * Exports the PDF as an HTML file with each page embedded as a base64 JPEG image.
     * Saved to Downloads/Sheetly/<baseName>.html.
     */
    fun exportPdfAsHtml(context: Context, uri: Uri, baseName: String): Boolean {
        val safeName = exportFileName(baseName)
        return writeToDownloads(context, "$safeName.html", "text/html") { output ->
            val writer = output.bufferedWriter(Charsets.UTF_8)
            writer.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
            writer.append("<title>").append(TextUtils.htmlEncode(safeName)).append("</title>")
            writer.append("<style>body{margin:0;background:#888;padding:12px}")
            writer.append("img{display:block;margin:10px auto;max-width:100%;")
            writer.append("box-shadow:0 2px 8px rgba(0,0,0,.4)}</style></head><body>")
            withRenderer(context, uri) { renderer ->
                for (pageIndex in 0 until renderer.pageCount) {
                    val bitmap = renderer.renderForExport(pageIndex)
                    val jpeg = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpeg)
                    bitmap.recycle()
                    val encoded = android.util.Base64.encodeToString(jpeg.toByteArray(), android.util.Base64.NO_WRAP)
                    writer.append("<img src='data:image/jpeg;base64,").append(encoded)
                        .append("' alt='Page ").append((pageIndex + 1).toString()).append("'>")
                }
            } ?: throw IOException("Cannot read $uri")
            writer.append("</body></html>")
            writer.flush()
        } != null
    }

    /**
     * Exports the PDF as a DOCX file where every page is an embedded JPEG image. Pages are
     * rendered one at a time so long documents do not have to fit in memory.
     * Saved to Downloads/Sheetly/<baseName>.docx.
     */
    fun exportPdfAsDocx(context: Context, uri: Uri, baseName: String): Boolean {
        val safeName = exportFileName(baseName)
        val mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        return writeToDownloads(context, "$safeName.docx", mimeType) { output ->
            withRenderer(context, uri) { renderer ->
                if (renderer.pageCount == 0) throw IOException("PDF has no pages")
                val pageSizes = List(renderer.pageCount) { pageIndex ->
                    renderer.openPage(pageIndex).use { page -> exportSize(page.width, page.height) }
                }
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                    zip.write(buildContentTypesXml().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("_rels/.rels"))
                    zip.write(buildRootRelsXml().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
                    zip.write(buildDocRelsXml(pageSizes.size).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("word/document.xml"))
                    zip.write(buildDocumentXml(pageSizes).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    pageSizes.indices.forEach { pageIndex ->
                        val bitmap = renderer.renderForExport(pageIndex)
                        zip.putNextEntry(ZipEntry("word/media/page${pageIndex + 1}.jpg"))
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, zip)
                        zip.closeEntry()
                        bitmap.recycle()
                    }
                }
            } ?: throw IOException("Cannot read $uri")
        } != null
    }

    private fun buildContentTypesXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
        append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        append("</Types>")
    }

    private fun buildRootRelsXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>")
        append("</Relationships>")
    }

    private fun buildDocRelsXml(pageCount: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 1..pageCount) {
            append("<Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/page$i.jpg\"/>")
        }
        append("</Relationships>")
    }

    private fun buildDocumentXml(pageSizes: List<Pair<Int, Int>>): String = buildString {
        val maxWidthEmu = 5_486_400L
        val maxHeightEmu = 8_229_600L
        val pixToEmu = 9525L
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<w:document")
        append(" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"")
        append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"")
        append(" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"")
        append(" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"")
        append(" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        append("<w:body>")
        pageSizes.forEachIndexed { i, (width, height) ->
            var cx = width.toLong() * pixToEmu
            var cy = height.toLong() * pixToEmu
            if (cx > maxWidthEmu) {
                cy = (cy * maxWidthEmu / cx)
                cx = maxWidthEmu
            }
            if (cy > maxHeightEmu) {
                cx = (cx * maxHeightEmu / cy)
                cy = maxHeightEmu
            }
            val rId = "rId${i + 1}"
            val imgId = i + 1
            append("<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
            append("<wp:extent cx=\"$cx\" cy=\"$cy\"/>")
            append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
            append("<wp:docPr id=\"$imgId\" name=\"page$imgId\"/>")
            append("<wp:cNvGraphicFramePr/>")
            append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            append("<pic:pic><pic:nvPicPr>")
            append("<pic:cNvPr id=\"$imgId\" name=\"page$imgId\"/>")
            append("<pic:cNvPicPr/></pic:nvPicPr>")
            append("<pic:blipFill><a:blip r:embed=\"$rId\"/>")
            append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>")
            append("<a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
            append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
            append("</pic:spPr></pic:pic>")
            append("</a:graphicData></a:graphic>")
            append("</wp:inline></w:drawing></w:r></w:p>")
            if (i < pageSizes.lastIndex) {
                append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }
        append("<w:sectPr/>")
        append("</w:body></w:document>")
    }
}

fun queryPdfDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIdx < 0) return@use null
            cursor.getString(nameIdx)
        }
    } catch (_: Exception) {
        null
    }
}
