package com.akuras.sheetly

import java.util.stream.IntStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class ScanMode {
    ORIGINAL,
    DOCUMENT,
    BLACK_WHITE,
}

/**
 * Cleans up photographed document pages. Works on plain ARGB pixel arrays so it can run and be
 * tested without Android graphics classes.
 *
 * The paper brightness is estimated on a coarse grid and every pixel is divided by it. That
 * flattens shadows and uneven lighting, after which levels (or a threshold) can be applied to
 * the whole page at once.
 */
internal object ScanEnhancer {
    private const val MIN_CELL_SIZE = 8
    private const val CELLS_ON_LONG_SIDE = 64
    private const val CELL_SAMPLE_STEP = 2
    private const val PAPER_PERCENTILE = 0.9f
    private const val GLOBAL_PAPER_PERCENTILE = 0.95f
    /** Paper fills most of a cell with one brightness, even in deep shadow. Photos spread out. */
    private const val PAPER_BAND = 14
    private const val MIN_PAPER_SHARE = 0.42f
    private const val MAX_PAPER_CHROMA = 48
    private const val UNKNOWN = -1f
    /** Last line of defence against dividing by almost nothing in black areas. */
    private const val MIN_BACKGROUND_RATIO = 0.15f
    private const val BLUR_RADIUS = 1
    private const val WHITE_POINT = 232
    private const val MAX_BLACK_POINT = 70
    private const val BLACK_PERCENTILE = 0.01f
    private const val MIDTONE_GAMMA = 1.25
    private const val MIN_THRESHOLD = 110
    private const val MAX_THRESHOLD = 215
    /** Half width of the grey ramp at the threshold, keeps letter edges smooth. */
    private const val THRESHOLD_SOFTNESS = 14

    fun enhance(pixels: IntArray, width: Int, height: Int, mode: ScanMode) {
        if (mode == ScanMode.ORIGINAL || width <= 0 || height <= 0) return
        flattenLighting(pixels, width, height)
        when (mode) {
            ScanMode.DOCUMENT -> applyLevels(pixels, width, height)
            ScanMode.BLACK_WHITE -> applyThreshold(pixels, width, height)
            ScanMode.ORIGINAL -> Unit
        }
    }

    private fun flattenLighting(pixels: IntArray, width: Int, height: Int) {
        val cellSize = max(MIN_CELL_SIZE, max(width, height) / CELLS_ON_LONG_SIDE)
        val cellsX = ceil(width / cellSize.toFloat()).toInt()
        val cellsY = ceil(height / cellSize.toFloat()).toInt()
        val background = estimateBackground(pixels, width, height, cellSize, cellsX, cellsY)

        forEachRow(height) { y ->
            val cellY = (y.toFloat() / cellSize - 0.5f).coerceIn(0f, (cellsY - 1).toFloat())
            val row0 = cellY.toInt()
            val row1 = min(row0 + 1, cellsY - 1)
            val fy = cellY - row0
            for (x in 0 until width) {
                val cellX = (x.toFloat() / cellSize - 0.5f).coerceIn(0f, (cellsX - 1).toFloat())
                val col0 = cellX.toInt()
                val col1 = min(col0 + 1, cellsX - 1)
                val fx = cellX - col0
                val top = background[row0 * cellsX + col0] * (1 - fx) + background[row0 * cellsX + col1] * fx
                val bottom = background[row1 * cellsX + col0] * (1 - fx) + background[row1 * cellsX + col1] * fx
                val gain = 255f / (top * (1 - fy) + bottom * fy)

                val index = y * width + x
                val pixel = pixels[index]
                val r = min(255, (((pixel shr 16) and 0xFF) * gain).toInt())
                val g = min(255, (((pixel shr 8) and 0xFF) * gain).toInt())
                val b = min(255, ((pixel and 0xFF) * gain).toInt())
                pixels[index] = (pixel and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun estimateBackground(
        pixels: IntArray,
        width: Int,
        height: Int,
        cellSize: Int,
        cellsX: Int,
        cellsY: Int,
    ): FloatArray {
        val cells = FloatArray(cellsX * cellsY)
        forEachRow(cellsY) { cellY ->
            val histogram = IntArray(256)
            for (cellX in 0 until cellsX) {
                histogram.fill(0)
                var samples = 0
                val endY = min((cellY + 1) * cellSize, height)
                val endX = min((cellX + 1) * cellSize, width)
                var y = cellY * cellSize
                while (y < endY) {
                    var x = cellX * cellSize
                    while (x < endX) {
                        histogram[luma(pixels[y * width + x])]++
                        samples++
                        x += CELL_SAMPLE_STEP
                    }
                    y += CELL_SAMPLE_STEP
                }
                val brightness = percentile(histogram, samples, PAPER_PERCENTILE)
                val isPaper = isPaperCell(histogram, samples, brightness) &&
                    !isColouredCell(pixels, width, cellX * cellSize, cellY * cellSize, endX, endY, brightness)
                cells[cellY * cellsX + cellX] = if (isPaper) {
                    brightness.toFloat()
                } else {
                    UNKNOWN
                }
            }
        }
        fillUnknownCells(cells, cellsX, cellsY)

        // Cells hidden by dense text or photos were filled in above. Taking the brightest neighbour
        // here instead would carry bright paper into the soft edge of a shadow and leave a grey ring.
        val paper = percentileOf(cells, GLOBAL_PAPER_PERCENTILE)
        val floor = max(paper * MIN_BACKGROUND_RATIO, 1f)
        for (i in cells.indices) {
            cells[i] = max(cells[i], floor)
        }
        return blur(cells, cellsX, cellsY)
    }

    private fun isPaperCell(histogram: IntArray, samples: Int, brightness: Int): Boolean {
        var nearPaper = 0
        for (value in max(brightness - PAPER_BAND, 0)..min(brightness + PAPER_BAND, 255)) {
            nearPaper += histogram[value]
        }
        return nearPaper >= samples * MIN_PAPER_SHARE
    }

    /**
     * Paper is close to grey even under warm light. A large area of one strong colour, like a stamp
     * or a coloured box, is also uniform, but brightening it as if it were shaded paper would wash
     * it out.
     */
    private fun isColouredCell(
        pixels: IntArray,
        width: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        brightness: Int,
    ): Boolean {
        var chromaSum = 0L
        var count = 0
        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val pixel = pixels[y * width + x]
                if (kotlin.math.abs(luma(pixel) - brightness) <= PAPER_BAND) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    chromaSum += max(r, max(g, b)) - min(r, min(g, b))
                    count++
                }
                x += CELL_SAMPLE_STEP
            }
            y += CELL_SAMPLE_STEP
        }
        return count > 0 && chromaSum / count > MAX_PAPER_CHROMA
    }

    /** Takes the paper level under a photo from the paper around it. */
    private fun fillUnknownCells(cells: FloatArray, cellsX: Int, cellsY: Int) {
        val known = cells.filter { it != UNKNOWN }
        if (known.isEmpty()) {
            cells.fill(255f)
            return
        }
        repeat(cellsX + cellsY) {
            val snapshot = cells.copyOf()
            var remaining = false
            for (y in 0 until cellsY) {
                for (x in 0 until cellsX) {
                    val index = y * cellsX + x
                    if (snapshot[index] != UNKNOWN) continue
                    var sum = 0f
                    var count = 0
                    for (ny in max(y - 1, 0)..min(y + 1, cellsY - 1)) {
                        for (nx in max(x - 1, 0)..min(x + 1, cellsX - 1)) {
                            val value = snapshot[ny * cellsX + nx]
                            if (value != UNKNOWN) {
                                sum += value
                                count++
                            }
                        }
                    }
                    if (count > 0) cells[index] = sum / count else remaining = true
                }
            }
            if (!remaining) return
        }
    }

    private fun blur(values: FloatArray, cellsX: Int, cellsY: Int): FloatArray {
        val result = FloatArray(values.size)
        for (y in 0 until cellsY) {
            for (x in 0 until cellsX) {
                var sum = 0f
                var count = 0
                for (ny in max(y - BLUR_RADIUS, 0)..min(y + BLUR_RADIUS, cellsY - 1)) {
                    for (nx in max(x - BLUR_RADIUS, 0)..min(x + BLUR_RADIUS, cellsX - 1)) {
                        sum += values[ny * cellsX + nx]
                        count++
                    }
                }
                result[y * cellsX + x] = sum / count
            }
        }
        return result
    }

    private fun applyLevels(pixels: IntArray, width: Int, height: Int) {
        val histogram = lumaHistogram(pixels, width, height)
        val black = min(percentile(histogram, pixels.size, BLACK_PERCENTILE), MAX_BLACK_POINT)
        val range = (WHITE_POINT - black).toDouble()
        val curve = IntArray(256) { value ->
            val normalized = ((value - black) / range).coerceIn(0.0, 1.0)
            (normalized.pow(MIDTONE_GAMMA) * 255).toInt()
        }

        forEachRow(height) { y ->
            for (index in y * width until (y + 1) * width) {
                val pixel = pixels[index]
                val r = curve[(pixel shr 16) and 0xFF]
                val g = curve[(pixel shr 8) and 0xFF]
                val b = curve[pixel and 0xFF]
                pixels[index] = (pixel and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun applyThreshold(pixels: IntArray, width: Int, height: Int) {
        val threshold = otsuThreshold(lumaHistogram(pixels, width, height), pixels.size)
            .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        val low = threshold - THRESHOLD_SOFTNESS
        val span = 2 * THRESHOLD_SOFTNESS
        forEachRow(height) { y ->
            for (index in y * width until (y + 1) * width) {
                val pixel = pixels[index]
                val value = ((luma(pixel) - low) * 255 / span).coerceIn(0, 255)
                pixels[index] = (pixel and 0xFF000000.toInt()) or (value shl 16) or (value shl 8) or value
            }
        }
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var weightedTotal = 0L
        for (value in histogram.indices) weightedTotal += value.toLong() * histogram[value]

        var backgroundCount = 0L
        var backgroundSum = 0L
        var bestVariance = -1.0
        var best = 128
        for (value in histogram.indices) {
            backgroundCount += histogram[value]
            if (backgroundCount == 0L) continue
            val foregroundCount = total - backgroundCount
            if (foregroundCount == 0L) break
            backgroundSum += value.toLong() * histogram[value]
            val backgroundMean = backgroundSum.toDouble() / backgroundCount
            val foregroundMean = (weightedTotal - backgroundSum).toDouble() / foregroundCount
            val difference = backgroundMean - foregroundMean
            val variance = backgroundCount.toDouble() * foregroundCount * difference * difference
            if (variance > bestVariance) {
                bestVariance = variance
                best = value
            }
        }
        return best
    }

    private fun lumaHistogram(pixels: IntArray, width: Int, height: Int): IntArray {
        val histogram = IntArray(256)
        for (index in 0 until width * height) {
            histogram[luma(pixels[index])]++
        }
        return histogram
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).toLong()
        var seen = 0L
        for (value in histogram.indices) {
            seen += histogram[value]
            if (seen > target) return value
        }
        return histogram.lastIndex
    }

    private fun percentileOf(values: FloatArray, fraction: Float): Float {
        val sorted = values.sortedArray()
        return sorted[((sorted.size - 1) * fraction).toInt()]
    }

    private fun luma(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    private inline fun forEachRow(rows: Int, crossinline block: (Int) -> Unit) {
        IntStream.range(0, rows).parallel().forEach { block(it) }
    }
}
