package com.akuras.sheetly.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.Immutable
import com.akuras.sheetly.ScanEnhancer
import com.akuras.sheetly.ScanMode
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val DETECTION_LONG_SIDE_PX = 960
private const val ANALYSIS_LONG_SIDE_PX = 400
/** Roughly 360 DPI on A4. */
private const val OUTPUT_LONG_SIDE_PX = 4200
private const val DEFAULT_INSET = 0.06f

/** One photographed page. The quad is normalized to the upright source image. */
@Immutable
internal data class ScanPage(
    val id: String = UUID.randomUUID().toString(),
    val file: File,
    val quad: DocumentQuad,
    val mode: ScanMode,
    val quarterTurns: Int = 0,
)

internal fun defaultQuad() = DocumentQuad(
    Point(DEFAULT_INSET, DEFAULT_INSET),
    Point(1 - DEFAULT_INSET, DEFAULT_INSET),
    Point(1 - DEFAULT_INSET, 1 - DEFAULT_INSET),
    Point(DEFAULT_INSET, 1 - DEFAULT_INSET),
)

internal object ScanImaging {

    fun decode(source: ImageDecoder.Source, maxLongSide: Int): Bitmap {
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val longSide = max(info.size.width, info.size.height)
            if (longSide > maxLongSide) {
                val scale = maxLongSide.toFloat() / longSide
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt(),
                    (info.size.height * scale).roundToInt()
                )
            }
        }
    }

    fun decodeFile(file: File, maxLongSide: Int): Bitmap = decode(ImageDecoder.createSource(file), maxLongSide)

    /** Copies an imported image into the cache so a scan session owns all of its files. */
    fun importImage(context: Context, uri: Uri, directory: File): File {
        val target = File(directory, "${UUID.randomUUID()}.jpg")
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read $uri")
        input.use { source -> target.outputStream().use(source::copyTo) }
        return target
    }

    /** Runs the detector on a photo, which sees finer detail than the live preview. */
    fun detect(file: File): DocumentQuad? {
        val bitmap = decodeFile(file, DETECTION_LONG_SIDE_PX)
        val frame = bitmap.toScanFrame()
        val scaleX = 1f / bitmap.width
        val scaleY = 1f / bitmap.height
        bitmap.recycle()
        return DocumentDetector().detect(frame)?.quad?.scaled(scaleX, scaleY)
    }

    /** Straightens the page and applies its filter. [maxLongSide] limits the source resolution. */
    fun render(page: ScanPage, maxLongSide: Int = OUTPUT_LONG_SIDE_PX): Bitmap {
        val source = decodeFile(page.file, maxLongSide)
        val rectified = rectify(source, page.quad)
        source.recycle()
        applyMode(rectified, page.mode)
        return rotate(rectified, page.quarterTurns)
    }

    private fun rectify(source: Bitmap, normalized: DocumentQuad): Bitmap {
        val quad = normalized.scaled(source.width.toFloat(), source.height.toFloat())
        val aspect = estimateAspectRatio(quad, source.width, source.height)
        val longestSide = quad.corners.indices.maxOf { index ->
            val first = quad.corners[index]
            val second = quad.corners[(index + 1) % 4]
            hypot(first.x - second.x, first.y - second.y)
        }
        val width: Int
        val height: Int
        if (aspect >= 1f) {
            width = longestSide.roundToInt().coerceAtLeast(1)
            height = (longestSide / aspect).roundToInt().coerceAtLeast(1)
        } else {
            height = longestSide.roundToInt().coerceAtLeast(1)
            width = (longestSide * aspect).roundToInt().coerceAtLeast(1)
        }

        val from = FloatArray(8)
        quad.corners.forEachIndexed { index, corner ->
            from[index * 2] = corner.x
            from[index * 2 + 1] = corner.y
        }
        val to = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
        val matrix = Matrix().apply { setPolyToPoly(from, 0, to, 0, 4) }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.eraseColor(Color.WHITE)
        Canvas(result).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return result
    }

    private fun applyMode(bitmap: Bitmap, mode: ScanMode) {
        if (mode == ScanMode.ORIGINAL) return
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        ScanEnhancer.enhance(pixels, width, height, mode)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun rotate(bitmap: Bitmap, quarterTurns: Int): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return bitmap
        val matrix = Matrix().apply { postRotate(90f * turns) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        return rotated
    }

    private fun Bitmap.toScanFrame(): ScanFrame {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = IntArray(pixels.size)
        val chromaU = IntArray(pixels.size)
        val chromaV = IntArray(pixels.size)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val y = (r * 77 + g * 150 + b * 29) shr 8
            luma[index] = y
            chromaU[index] = 128 + (((b - y) * 126) shr 8)
            chromaV[index] = 128 + (((r - y) * 224) shr 8)
        }
        return ScanFrame(width, height, luma, chromaU, chromaV)
    }
}

/** Shrinks camera frames for analysis and turns them upright, reusing its buffers between frames. */
internal class CameraFrameConverter {
    private var rawLuma = IntArray(0)
    private var rawU = IntArray(0)
    private var rawV = IntArray(0)

    fun convert(image: ImageProxy): ScanFrame {
        val step = max(1, max(image.width, image.height) / ANALYSIS_LONG_SIDE_PX)
        val width = image.width / step
        val height = image.height / step
        val size = width * height
        if (rawLuma.size != size) {
            rawLuma = IntArray(size)
            rawU = IntArray(size)
            rawV = IntArray(size)
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (y in 0 until height) {
            val sourceY = y * step
            val chromaY = sourceY / 2
            for (x in 0 until width) {
                val sourceX = x * step
                val chromaX = sourceX / 2
                val index = y * width + x
                rawLuma[index] = yBuffer.get(sourceY * yPlane.rowStride + sourceX * yPlane.pixelStride).toInt() and 0xFF
                rawU[index] = uBuffer.get(chromaY * uPlane.rowStride + chromaX * uPlane.pixelStride).toInt() and 0xFF
                rawV[index] = vBuffer.get(chromaY * vPlane.rowStride + chromaX * vPlane.pixelStride).toInt() and 0xFF
            }
        }

        val rotation = image.imageInfo.rotationDegrees
        val rotatedWidth = if (rotation % 180 == 0) width else height
        val rotatedHeight = if (rotation % 180 == 0) height else width
        return ScanFrame(
            rotatedWidth,
            rotatedHeight,
            rotatePlane(rawLuma, width, height, rotation),
            rotatePlane(rawU, width, height, rotation),
            rotatePlane(rawV, width, height, rotation),
        )
    }

    private fun rotatePlane(source: IntArray, width: Int, height: Int, rotation: Int): IntArray {
        if (rotation == 0) return source.copyOf()
        val target = IntArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = source[y * width + x]
                when (rotation) {
                    90 -> target[x * height + (height - 1 - y)] = value
                    180 -> target[(height - 1 - y) * width + (width - 1 - x)] = value
                    else -> target[(width - 1 - x) * height + y] = value
                }
            }
        }
        return target
    }
}

/** Keeps a dragged corner inside the image and the outline convex enough to be usable. */
internal fun DocumentQuad.withCorner(index: Int, x: Float, y: Float): DocumentQuad {
    val point = Point(min(max(x, 0f), 1f), min(max(y, 0f), 1f))
    return when (index) {
        0 -> copy(topLeft = point)
        1 -> copy(topRight = point)
        2 -> copy(bottomRight = point)
        else -> copy(bottomLeft = point)
    }
}
