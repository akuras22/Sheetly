package com.akuras.sheetly

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import org.xmlpull.v1.XmlPullParser

private const val PAGE_WIDTH = 595
private const val PAGE_HEIGHT = 842
private const val MARGIN = 56f
private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
private const val DEFAULT_FONT_SIZE = 11f
private const val LINE_SPACING = 1.15f
private const val PARAGRAPH_GAP = 7f
private const val CELL_PADDING = 5f
private const val LIST_INDENT = 18f
private const val EMU_PER_POINT = 12700f
private const val BULLET = "•  "
private val HEADING_SIZES = floatArrayOf(22f, 17f, 14f, 12.5f)
private val HEADING_STYLE = Regex("(?:heading|berschrift)\\s*(\\d)", RegexOption.IGNORE_CASE)
private val TITLE_STYLE = Regex("^(title|titel)$", RegexOption.IGNORE_CASE)

/**
 * Sets a .docx as PDF with real, selectable text. It reads the structure of the document (headings,
 * formatting, lists, tables, images) and lays it out anew, so simple documents come out well while
 * text boxes, columns and exact page breaks of complex layouts are not reproduced.
 */
internal object DocxConverter {

    private class Run(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val size: Float?,
        val color: Int?,
    )

    private sealed class Block

    private class Paragraph(
        val runs: List<Run>,
        val alignment: Layout.Alignment,
        val heading: Int?,
        val isListItem: Boolean,
    ) : Block() {
        val isEmpty get() = runs.all { it.text.isEmpty() }
    }

    private class Picture(val relationId: String, val width: Float, val height: Float) : Block()
    private class Table(val rows: List<List<List<Paragraph>>>) : Block()
    private data object PageBreak : Block()

    fun convert(context: Context, uri: Uri, output: OutputStream) {
        val parts = readParts(context, uri)
        val documentXml = parts["word/document.xml"] ?: throw IOException("Not a Word document")
        val blocks = parseDocument(documentXml)
        val images = parseRelationships(parts["word/_rels/document.xml.rels"])
            .mapNotNull { (id, target) -> parts["word/${target.removePrefix("/word/")}"]?.let { id to it } }
            .toMap()
        render(blocks, images, output)
    }

    private fun readParts(context: Context, uri: Uri): Map<String, ByteArray> {
        val parts = HashMap<String, ByteArray>()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return parts
    }

    private fun parseRelationships(xml: ByteArray?): Map<String, String> {
        if (xml == null) return emptyMap()
        val relationships = HashMap<String, String>()
        val parser = newParser(xml)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) relationships[id] = target
            }
        }
        return relationships
    }

    private fun newParser(xml: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(xml), "UTF-8")
    }

    private fun parseDocument(xml: ByteArray): List<Block> {
        val parser = newParser(xml)
        val blocks = ArrayList<Block>()
        var runs = ArrayList<Run>()
        var alignment = Layout.Alignment.ALIGN_NORMAL
        var heading: Int? = null
        var isListItem = false
        var bold = false
        var italic = false
        var underline = false
        var size: Float? = null
        var color: Int? = null
        var inRunProperties = false
        var tableDepth = 0
        var rows = ArrayList<List<List<Paragraph>>>()
        var row = ArrayList<List<Paragraph>>()
        var cell = ArrayList<Paragraph>()
        var pictureWidth = 0f
        var pictureHeight = 0f
        val pendingPictures = ArrayList<Block>()

        fun flag(): Boolean = parser.getAttributeValue(null, "w:val").let { it != "0" && it != "false" }
        fun addRun(text: String) {
            runs.add(Run(text, bold, italic, underline, size, color))
        }

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:p" -> {
                        runs = ArrayList()
                        alignment = Layout.Alignment.ALIGN_NORMAL
                        heading = null
                        isListItem = false
                    }
                    "w:r" -> {
                        bold = false
                        italic = false
                        underline = false
                        size = null
                        color = null
                    }
                    "w:rPr" -> inRunProperties = true
                    "w:b" -> if (inRunProperties) bold = flag()
                    "w:i" -> if (inRunProperties) italic = flag()
                    "w:u" -> if (inRunProperties) underline = parser.getAttributeValue(null, "w:val") != "none"
                    "w:sz" -> if (inRunProperties) {
                        size = parser.getAttributeValue(null, "w:val")?.toFloatOrNull()?.div(2)
                    }
                    "w:color" -> if (inRunProperties) color = parseColor(parser.getAttributeValue(null, "w:val"))
                    "w:jc" -> alignment = when (parser.getAttributeValue(null, "w:val")) {
                        "center" -> Layout.Alignment.ALIGN_CENTER
                        "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                    "w:pStyle" -> heading = headingLevel(parser.getAttributeValue(null, "w:val"))
                    "w:numPr" -> isListItem = true
                    "w:t" -> addRun(parser.nextText())
                    "w:tab" -> if (!inRunProperties) addRun("\t")
                    "w:br" -> {
                        if (parser.getAttributeValue(null, "w:type") == "page") {
                            if (tableDepth == 0) pendingPictures.add(PageBreak)
                        } else {
                            addRun("\n")
                        }
                    }
                    "wp:extent" -> {
                        pictureWidth = (parser.getAttributeValue(null, "cx")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT
                        pictureHeight = (parser.getAttributeValue(null, "cy")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT
                    }
                    "a:blip" -> {
                        val id = parser.getAttributeValue(null, "r:embed")
                        if (id != null && tableDepth == 0) pendingPictures.add(Picture(id, pictureWidth, pictureHeight))
                    }
                    "w:tbl" -> {
                        tableDepth++
                        if (tableDepth == 1) rows = ArrayList()
                    }
                    "w:tr" -> if (tableDepth == 1) row = ArrayList()
                    "w:tc" -> if (tableDepth == 1) cell = ArrayList()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:rPr" -> inRunProperties = false
                    "w:p" -> {
                        val paragraph = Paragraph(runs, alignment, heading, isListItem)
                        if (tableDepth > 0) {
                            cell.add(paragraph)
                        } else {
                            blocks.add(paragraph)
                            blocks.addAll(pendingPictures)
                            pendingPictures.clear()
                        }
                    }
                    "w:tc" -> if (tableDepth == 1) row.add(cell)
                    "w:tr" -> if (tableDepth == 1) rows.add(row)
                    "w:tbl" -> {
                        if (tableDepth == 1) blocks.add(Table(rows))
                        tableDepth--
                    }
                }
            }
        }
        return blocks
    }

    private fun parseColor(value: String?): Int? {
        if (value == null || value.length != 6) return null
        return value.toIntOrNull(16)?.let { Color.rgb(it shr 16, (it shr 8) and 0xFF, it and 0xFF) }
    }

    private fun headingLevel(style: String?): Int? {
        if (style == null) return null
        if (TITLE_STYLE.matches(style)) return 0
        return HEADING_STYLE.find(style)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, HEADING_SIZES.lastIndex)
    }

    private class PageWriter(private val document: PdfDocument, private val output: OutputStream) {
        private var page: PdfDocument.Page? = null
        private var pageNumber = 0
        var y = MARGIN
            private set
        val bottom = PAGE_HEIGHT - MARGIN
        val canvas: Canvas
            get() = (page ?: startPage()).canvas

        val remaining: Float get() = bottom - y

        private fun startPage(): PdfDocument.Page {
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            return document.startPage(info).also {
                page = it
                y = MARGIN
            }
        }

        fun nextPage() {
            page?.let { document.finishPage(it) }
            page = null
            y = MARGIN
        }

        fun advance(distance: Float) {
            y += distance
        }

        fun finish() {
            if (page == null && pageNumber == 0) startPage()
            page?.let { document.finishPage(it) }
            document.writeTo(output)
        }
    }

    private fun render(blocks: List<Block>, images: Map<String, ByteArray>, output: OutputStream) {
        val document = PdfDocument()
        try {
            val writer = PageWriter(document, output)
            blocks.forEach { block ->
                when (block) {
                    is Paragraph -> drawParagraph(writer, block)
                    is Picture -> images[block.relationId]?.let { drawPicture(writer, block, it) }
                    is Table -> drawTable(writer, block)
                    PageBreak -> writer.nextPage()
                }
            }
            writer.finish()
        } finally {
            document.close()
        }
    }

    private fun styledText(paragraph: Paragraph): SpannableStringBuilder {
        val text = SpannableStringBuilder()
        if (paragraph.isListItem) text.append(BULLET)
        val headingSize = paragraph.heading?.let { HEADING_SIZES[it] }
        paragraph.runs.forEach { run ->
            val start = text.length
            text.append(run.text)
            val end = text.length
            if (start == end) return@forEach
            val bold = run.bold || headingSize != null
            val style = when {
                bold && run.italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                run.italic -> Typeface.ITALIC
                else -> null
            }
            style?.let { text.setSpan(StyleSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (run.underline) text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            run.color?.let { text.setSpan(ForegroundColorSpan(it), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            val size = run.size ?: headingSize
            if (size != null) {
                text.setSpan(AbsoluteSizeSpan(size.toInt(), false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return text
    }

    private fun layout(text: CharSequence, width: Float, alignment: Layout.Alignment): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = DEFAULT_FONT_SIZE
            color = Color.BLACK
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(alignment)
            .setLineSpacing(0f, LINE_SPACING)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
    }

    private fun drawParagraph(writer: PageWriter, paragraph: Paragraph) {
        if (paragraph.isEmpty) {
            writer.advance(DEFAULT_FONT_SIZE)
            return
        }
        val indent = if (paragraph.isListItem) LIST_INDENT else 0f
        val width = CONTENT_WIDTH - indent
        var remainingText: CharSequence = styledText(paragraph)
        val gapBefore = if (paragraph.heading != null) PARAGRAPH_GAP else 0f
        writer.advance(gapBefore)

        // A page break inside a paragraph gets a separate layout per page: drawing one layout twice
        // with a clip would put the whole text into the PDF twice.
        while (true) {
            val full = layout(remainingText, width, paragraph.alignment)
            if (full.height <= writer.remaining) {
                drawLayout(writer, full, indent)
                break
            }
            var fittingLines = 0
            while (fittingLines < full.lineCount && full.getLineBottom(fittingLines) <= writer.remaining) {
                fittingLines++
            }
            if (fittingLines == 0) {
                writer.nextPage()
                continue
            }
            val splitAt = full.getLineStart(fittingLines)
            drawLayout(writer, layout(remainingText.subSequence(0, splitAt), width, paragraph.alignment), indent)
            remainingText = remainingText.subSequence(splitAt, remainingText.length)
            writer.nextPage()
        }
        writer.advance(PARAGRAPH_GAP)
    }

    private fun drawLayout(writer: PageWriter, layout: StaticLayout, indent: Float) {
        val canvas = writer.canvas
        canvas.save()
        canvas.translate(MARGIN + indent, writer.y)
        layout.draw(canvas)
        canvas.restore()
        writer.advance(layout.height.toFloat())
    }

    private fun drawPicture(writer: PageWriter, picture: Picture, data: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        val nativeWidth = if (picture.width > 0) picture.width else bitmap.width.toFloat()
        val nativeHeight = if (picture.height > 0) picture.height else bitmap.height.toFloat()
        val scale = min(1f, min(CONTENT_WIDTH / nativeWidth, (writer.bottom - MARGIN) / nativeHeight))
        val width = nativeWidth * scale
        val height = nativeHeight * scale
        if (height > writer.remaining) writer.nextPage()
        val canvas = writer.canvas
        val target = RectF(MARGIN, writer.y, MARGIN + width, writer.y + height)
        canvas.drawBitmap(bitmap, null, target, Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
        writer.advance(height + PARAGRAPH_GAP)
    }

    private fun drawTable(writer: PageWriter, table: Table) {
        val columns = table.rows.maxOfOrNull { it.size } ?: return
        if (columns == 0) return
        val columnWidth = CONTENT_WIDTH / columns
        val line = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            color = Color.GRAY
        }
        table.rows.forEach { row ->
            val cells = row.map { paragraphs ->
                val text = SpannableStringBuilder()
                paragraphs.forEachIndexed { index, paragraph ->
                    if (index > 0) text.append('\n')
                    text.append(styledText(paragraph))
                }
                layout(text, columnWidth - 2 * CELL_PADDING, Layout.Alignment.ALIGN_NORMAL)
            }
            val height = min(
                (cells.maxOfOrNull { it.height } ?: 0) + 2 * CELL_PADDING,
                writer.bottom - MARGIN
            )
            if (height > writer.remaining) writer.nextPage()
            val canvas = writer.canvas
            cells.forEachIndexed { index, cell ->
                val left = MARGIN + index * columnWidth
                canvas.drawRect(left, writer.y, left + columnWidth, writer.y + height, line)
                canvas.save()
                canvas.translate(left + CELL_PADDING, writer.y + CELL_PADDING)
                cell.draw(canvas)
                canvas.restore()
            }
            writer.advance(height)
        }
        writer.advance(max(PARAGRAPH_GAP, DEFAULT_FONT_SIZE))
    }
}
