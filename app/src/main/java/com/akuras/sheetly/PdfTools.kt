package com.akuras.sheetly

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "PdfTools"
private const val COMPRESSED_LONG_SIDE_PX = 1400
private const val COMPRESSED_JPEG_QUALITY = 0.62f
private const val OCR_PAGE_WIDTH_PX = 2000
private const val IMAGE_LONG_SIDE_PX = 3000
private const val IMAGE_JPEG_QUALITY = 0.88f
private const val IMAGE_PAGE_LONG_SIDE_PT = 842f
private const val PAGE_NUMBER_SIZE = 10f
private const val PAGE_NUMBER_MARGIN = 22f
private const val WATERMARK_SIZE = 64f
private const val WATERMARK_ALPHA = 0.16f
private const val PDF_MIME = "application/pdf"
private const val ENCRYPTION_KEY_BITS = 256
private const val OCR_LONG_SIDE_PX = 2600
private const val FONT_HEIGHT_RATIO = 0.9f
private const val BASELINE_RATIO = 0.2f
private const val MIN_TEXT_LAYER_FONT = 1f

/**
 * Puts recognized words as invisible text over a page image, which keeps the look of the scan while
 * making its text selectable and searchable. Blocks while recognizing, so use it off the main thread.
 */
internal class TextLayerWriter : Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val font = PDType1Font.HELVETICA
    private val encodable = HashMap<Char, Boolean>()

    /** A failure only costs the text layer, the page itself is already part of the document. */
    fun add(document: PDDocument, page: PDPage, bitmap: Bitmap) {
        try {
            val longSide = max(bitmap.width, bitmap.height)
            val scale = min(1f, OCR_LONG_SIDE_PX.toFloat() / longSide)
            val input = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(input, 0)))
            val width = input.width
            val height = input.height
            if (input !== bitmap) input.recycle()
            writeWords(document, page, text, width, height)
        } catch (e: Exception) {
            Log.w(TAG, "Page stays without text layer", e)
        }
    }

    private fun writeWords(document: PDDocument, page: PDPage, text: Text, imageWidth: Int, imageHeight: Int) {
        val box = page.mediaBox
        val scaleX = box.width / imageWidth
        val scaleY = box.height / imageHeight
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
            content.beginText()
            content.setRenderingMode(RenderingMode.NEITHER)
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val bounds = element.boundingBox ?: continue
                        val word = encodableText(element.text)
                        if (word.isBlank()) continue
                        val fontSize = max(bounds.height() * scaleY * FONT_HEIGHT_RATIO, MIN_TEXT_LAYER_FONT)
                        val naturalWidth = font.getStringWidth(word) / 1000 * fontSize
                        if (naturalWidth <= 0f) continue
                        content.setFont(font, fontSize)
                        content.setHorizontalScaling(100f * bounds.width() * scaleX / naturalWidth)
                        content.setTextMatrix(
                            Matrix.getTranslateInstance(
                                bounds.left * scaleX,
                                box.height - bounds.bottom * scaleY + bounds.height() * scaleY * BASELINE_RATIO
                            )
                        )
                        // The space keeps words apart when the text is copied or searched.
                        content.showText("$word ")
                    }
                }
            }
            content.endText()
        }
    }

    /** The built-in PDF font knows Western characters only, anything else would abort the page. */
    private fun encodableText(text: String): String = buildString {
        text.forEach { character ->
            val known = encodable.getOrPut(character) {
                runCatching { font.encode(character.toString()) }.isSuccess
            }
            append(if (known) character else '?')
        }
    }

    override fun close() = recognizer.close()
}

/** Everything here reads a PDF and writes the result as a new file, the source stays untouched. */
object PdfTools {

    private fun baseName(name: String) = name.removeSuffix(".pdf").removeSuffix(".PDF")

    private fun openDocument(context: Context, uri: Uri, password: String = ""): PDDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot read $uri")
        return input.use { PDDocument.load(it, password, MemoryUsageSetting.setupTempFileOnly()) }
    }

    fun importPdf(context: Context, uri: Uri, displayName: String?): Uri? {
        val name = "${sanitizeFileName(baseName(displayName ?: "import"), fallback = "import")}.pdf"
        return PdfStorage.writeToDownloads(context, name, PDF_MIME) { output ->
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
            input.use { it.copyTo(output) }
        }
    }

    fun wordToPdf(context: Context, uri: Uri, displayName: String?): Uri? {
        val base = (displayName ?: "dokument").substringBeforeLast('.')
        val name = "${sanitizeFileName(base, fallback = "dokument")}.pdf"
        return PdfStorage.writeToDownloads(context, name, PDF_MIME) { output ->
            DocxConverter.convert(context, uri, output)
        }
    }

    /** Re-encodes every page as a smaller image. Text stops being selectable, sizes shrink a lot. */
    fun compress(context: Context, item: SavedPdf): Uri? {
        PDFBoxResourceLoader.init(context.applicationContext)
        val name = "${baseName(item.name)}_klein.pdf"
        return PdfStorage.writeToDownloads(context, name, PDF_MIME) { output ->
            val descriptor = context.contentResolver.openFileDescriptor(item.uri, "r")
                ?: throw IOException("Cannot read ${item.uri}")
            descriptor.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
                        for (index in 0 until renderer.pageCount) {
                            renderer.openPage(index).use { source ->
                                val scale = COMPRESSED_LONG_SIDE_PX.toFloat() / max(source.width, source.height)
                                val width = (source.width * scale).toInt().coerceAtLeast(1)
                                val height = (source.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                bitmap.setHasAlpha(false)
                                val page = PDPage(PDRectangle(source.width.toFloat(), source.height.toFloat()))
                                document.addPage(page)
                                val image = JPEGFactory.createFromImage(document, bitmap, COMPRESSED_JPEG_QUALITY)
                                bitmap.recycle()
                                PDPageContentStream(document, page).use {
                                    it.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                                }
                            }
                        }
                        document.save(output)
                    }
                }
            }
        }
    }

    /** Writes every page into its own PDF and returns how many were created. */
    fun splitIntoPages(context: Context, item: SavedPdf): Int {
        return try {
            openDocument(context, item.uri).use { source ->
                val total = source.numberOfPages
                (0 until total).count { index ->
                    PdfStorage.writeToDownloads(
                        context,
                        "${baseName(item.name)}_S${index + 1}.pdf",
                        PDF_MIME
                    ) { output ->
                        PDDocument().use { single ->
                            single.importPage(source.getPage(index))
                            single.save(output)
                        }
                    } != null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to split PDF", e)
            0
        }
    }

    fun protectWithPassword(context: Context, item: SavedPdf, password: String): Uri? {
        return editCopy(context, item, "${baseName(item.name)}_geschuetzt.pdf") { document ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = ENCRYPTION_KEY_BITS
            document.protect(policy)
        }
    }

    fun addPageNumbers(context: Context, item: SavedPdf): Uri? {
        return editCopy(context, item, "${baseName(item.name)}_nummeriert.pdf") { document ->
            val total = document.numberOfPages
            document.pages.forEachIndexed { index, page ->
                val text = "${index + 1} / $total"
                val width = PDType1Font.HELVETICA.getStringWidth(text) / 1000 * PAGE_NUMBER_SIZE
                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, PAGE_NUMBER_SIZE)
                    content.newLineAtOffset((page.mediaBox.width - width) / 2, PAGE_NUMBER_MARGIN)
                    content.showText(text)
                    content.endText()
                }
            }
        }
    }

    fun addWatermark(context: Context, item: SavedPdf, text: String): Uri? {
        return editCopy(context, item, "${baseName(item.name)}_wasserzeichen.pdf") { document ->
            val state = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = WATERMARK_ALPHA }
            document.pages.forEach { page ->
                val box = page.mediaBox
                val angle = atan2(box.height, box.width).toDouble()
                val textWidth = PDType1Font.HELVETICA_BOLD.getStringWidth(text) / 1000 * WATERMARK_SIZE
                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                    content.setGraphicsStateParameters(state)
                    content.setNonStrokingColor(200, 30, 30)
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA_BOLD, WATERMARK_SIZE)
                    val diagonal = kotlin.math.hypot(box.width, box.height)
                    val inset = (diagonal - textWidth) / 2
                    content.setTextMatrix(
                        Matrix.getRotateInstance(
                            angle,
                            inset * kotlin.math.cos(angle).toFloat(),
                            inset * kotlin.math.sin(angle).toFloat()
                        )
                    )
                    content.showText(text)
                    content.endText()
                }
            }
        }
    }

    private fun editCopy(context: Context, item: SavedPdf, fileName: String, edit: (PDDocument) -> Unit): Uri? {
        return PdfStorage.writeToDownloads(context, fileName, PDF_MIME) { output ->
            openDocument(context, item.uri).use { document ->
                edit(document)
                document.save(output)
            }
        }
    }

    /** Turns gallery images into one PDF, one image per page. */
    fun imagesToPdf(context: Context, uris: List<Uri>, pattern: String): Uri? {
        if (uris.isEmpty()) return null
        PDFBoxResourceLoader.init(context.applicationContext)
        val name = "${sanitizeFileName(resolveFileName(pattern))}.pdf"
        return PdfStorage.writeToDownloads(context, name, PDF_MIME) { output ->
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
                uris.forEach { uri ->
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val longSide = max(info.size.width, info.size.height)
                        if (longSide > IMAGE_LONG_SIDE_PX) {
                            val scale = IMAGE_LONG_SIDE_PX.toFloat() / longSide
                            decoder.setTargetSize(
                                (info.size.width * scale).toInt(),
                                (info.size.height * scale).toInt()
                            )
                        }
                    }
                    val opaque = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    bitmap.recycle()
                    opaque.setHasAlpha(false)
                    val scale = IMAGE_PAGE_LONG_SIDE_PT / max(opaque.width, opaque.height)
                    val page = PDPage(PDRectangle(opaque.width * scale, opaque.height * scale))
                    document.addPage(page)
                    val image = JPEGFactory.createFromImage(document, opaque, IMAGE_JPEG_QUALITY)
                    opaque.recycle()
                    PDPageContentStream(document, page).use {
                        it.drawImage(image, 0f, 0f, page.mediaBox.width, page.mediaBox.height)
                    }
                }
                document.save(output)
            }
        }
    }

    /** Recognizes the text of all pages on the device. No page ever leaves the phone. */
    suspend fun recognizeText(context: Context, item: SavedPdf): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val pageCount = PdfStorage.countPdfPages(context, item.uri) ?: 0
            val result = StringBuilder()
            for (page in 0 until pageCount) {
                val bitmap = PdfStorage.renderPdfPage(context, item.uri, page, OCR_PAGE_WIDTH_PX) ?: continue
                val text = suspendCancellableCoroutine { continuation ->
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { continuation.resume(it.text) }
                        .addOnFailureListener { continuation.resumeWithException(it) }
                }
                bitmap.recycle()
                if (text.isNotBlank()) {
                    if (result.isNotEmpty()) result.append("\n\n")
                    result.append(text)
                }
            }
            return result.toString()
        } finally {
            recognizer.close()
        }
    }

    fun print(context: Context, item: SavedPdf) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(item.name, PdfPrintAdapter(context.applicationContext, item), null)
    }

    private class PdfPrintAdapter(
        private val context: Context,
        private val item: SavedPdf,
    ) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(item.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                val input = context.contentResolver.openInputStream(item.uri) ?: throw IOException("Cannot read")
                input.use { source ->
                    FileOutputStream(destination.fileDescriptor).use { target -> source.copyTo(target) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: IOException) {
                Log.e(TAG, "Failed to print", e)
                callback.onWriteFailed(e.message)
            }
        }
    }
}
