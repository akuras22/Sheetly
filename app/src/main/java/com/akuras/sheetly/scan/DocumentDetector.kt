package com.akuras.sheetly.scan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Point(val x: Float, val y: Float)

/** Corners in clockwise order, starting at the top left of the frame. */
data class DocumentQuad(
    val topLeft: Point,
    val topRight: Point,
    val bottomRight: Point,
    val bottomLeft: Point,
) {
    val corners: List<Point> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun scaled(scaleX: Float, scaleY: Float): DocumentQuad = DocumentQuad(
        Point(topLeft.x * scaleX, topLeft.y * scaleY),
        Point(topRight.x * scaleX, topRight.y * scaleY),
        Point(bottomRight.x * scaleX, bottomRight.y * scaleY),
        Point(bottomLeft.x * scaleX, bottomLeft.y * scaleY),
    )
}

data class Detection(val quad: DocumentQuad, val confidence: Float)

/** A small analysis frame. Chroma is optional and must have the same size as [luma]. */
class ScanFrame(
    val width: Int,
    val height: Int,
    val luma: IntArray,
    val chromaU: IntArray? = null,
    val chromaV: IntArray? = null,
)

/**
 * Finds a document by its straight edges instead of by a closed outline, so a thumb on the page,
 * a cut-off corner or a side with almost no contrast does not lose the whole page.
 *
 * 1. Edge pixels come from the strongest of the luma and chroma gradients, which also finds paper
 *    on a table of equal brightness.
 * 2. A Hough transform restricted to each pixel's gradient direction collects straight lines.
 * 3. Pairs of roughly opposite lines are combined into quads, and every quad is scored by how much
 *    of its outline is backed by edges, its size and how much it looks like a sheet of paper.
 */
internal class DocumentDetector {
    private var width = 0
    private var height = 0
    private var blurredLuma = IntArray(0)
    private var blurredU = IntArray(0)
    private var blurredV = IntArray(0)
    private var scratch = IntArray(0)
    private var gradientX = IntArray(0)
    private var gradientY = IntArray(0)
    private var magnitude = IntArray(0)
    /** Edge orientation + 1 per pixel, 0 where there is no edge. */
    private var edgeMap = ShortArray(0)
    private var textureMask = BooleanArray(0)
    private var edgeIndices = IntArray(0)
    private var edgeCount = 0
    private var accumulator = IntArray(0)
    private var segmentStart = 0
    private var segmentEnd = 0

    private class Line(
        val normalX: Float,
        val normalY: Float,
        val offset: Float,
        val length: Float,
        val isBorder: Boolean = false,
    ) {
        val angle: Float = (Math.toDegrees(atan2(normalY, normalX).toDouble()).toFloat() + 180f) % 180f
    }

    private class Candidate(val corners: Array<Point>, val score: Float)

    fun detect(frame: ScanFrame): Detection? {
        prepareBuffers(frame)
        findEdges(frame)
        if (edgeCount < MIN_EDGE_PIXELS) return null
        val best = bestQuad(findLines()) ?: return null
        return Detection(toQuad(best.corners), best.score.coerceIn(0f, 1f))
    }

    private fun prepareBuffers(frame: ScanFrame) {
        width = frame.width
        height = frame.height
        val size = width * height
        if (blurredLuma.size != size) {
            blurredLuma = IntArray(size)
            blurredU = IntArray(size)
            blurredV = IntArray(size)
            scratch = IntArray(size)
            gradientX = IntArray(size)
            gradientY = IntArray(size)
            magnitude = IntArray(size)
            edgeMap = ShortArray(size)
            textureMask = BooleanArray(size)
            edgeIndices = IntArray(size)
            val diagonal = (hypot(width.toDouble(), height.toDouble()).toInt() + 2) / RHO_STEP + 1
            accumulator = IntArray(ANGLE_BINS * (2 * diagonal + 1))
        }
    }

    private fun findEdges(frame: ScanFrame) {
        smooth(frame.luma, blurredLuma)
        computeGradients(blurredLuma, 1, reset = true)
        val chromaU = frame.chromaU
        val chromaV = frame.chromaV
        if (chromaU != null && chromaV != null) {
            smooth(chromaU, blurredU)
            computeGradients(blurredU, CHROMA_WEIGHT, reset = false)
            smooth(chromaV, blurredV)
            computeGradients(blurredV, CHROMA_WEIGHT, reset = false)
        }

        val threshold = max(MIN_EDGE_MAGNITUDE, noiseFloor())
        edgeCount = 0
        edgeMap.fill(0)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val value = magnitude[index]
                if (value < threshold || !isLocalMaximum(index, value)) continue
                val degrees = Math.toDegrees(atan2(gradientY[index].toDouble(), gradientX[index].toDouble()))
                val orientation = (degrees.roundToInt() + 360) % 180
                edgeMap[index] = (orientation + 1).toShort()
                edgeIndices[edgeCount++] = index
            }
        }
        removeTextureEdges()
    }

    /**
     * Text and grain have further edges on both sides, a page border has calm paper or table on
     * at least one. Without this, lines of text drown out the page outline.
     */
    private fun removeTextureEdges() {
        var kept = 0
        for (edge in 0 until edgeCount) {
            val index = edgeIndices[edge]
            val x = index % width
            val y = index / width
            val orientation = edgeMap[index] - 1
            val normalX = COS[orientation]
            val normalY = SIN[orientation]
            val minNeighbour = magnitude[index] * TEXTURE_NEIGHBOUR_STRENGTH / 100
            if (hasSimilarEdgeAlong(x, y, normalX, normalY, orientation, minNeighbour) &&
                hasSimilarEdgeAlong(x, y, -normalX, -normalY, orientation, minNeighbour)
            ) {
                textureMask[index] = true
            } else {
                edgeIndices[kept++] = index
            }
        }
        edgeCount = kept
        for (index in textureMask.indices) {
            if (textureMask[index]) {
                edgeMap[index] = 0
                textureMask[index] = false
            }
        }
    }

    /** Only parallel edges of similar strength count, sensor noise next to a border must not. */
    private fun hasSimilarEdgeAlong(
        x: Int,
        y: Int,
        stepX: Float,
        stepY: Float,
        orientation: Int,
        minMagnitude: Int,
    ): Boolean {
        for (distance in TEXTURE_MIN_DISTANCE..TEXTURE_REACH) {
            val sampleX = (x + stepX * distance).roundToInt()
            val sampleY = (y + stepY * distance).roundToInt()
            if (sampleX !in 0 until width || sampleY !in 0 until height) return false
            val index = sampleY * width + sampleX
            val edge = edgeMap[index].toInt()
            if (edge != 0 && magnitude[index] >= minMagnitude &&
                angleDistance(edge - 1, orientation) <= TEXTURE_ANGLE
            ) {
                return true
            }
        }
        return false
    }

    private fun smooth(source: IntArray, target: IntArray) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val left2 = source[row + max(x - 2, 0)]
                val left = source[row + max(x - 1, 0)]
                val right = source[row + min(x + 1, width - 1)]
                val right2 = source[row + min(x + 2, width - 1)]
                scratch[row + x] = (left2 + 4 * left + 6 * source[row + x] + 4 * right + right2) shr 4
            }
        }
        for (y in 0 until height) {
            val up2 = max(y - 2, 0) * width
            val up = max(y - 1, 0) * width
            val row = y * width
            val down = min(y + 1, height - 1) * width
            val down2 = min(y + 2, height - 1) * width
            for (x in 0 until width) {
                target[row + x] = (scratch[up2 + x] + 4 * scratch[up + x] + 6 * scratch[row + x] +
                    4 * scratch[down + x] + scratch[down2 + x]) shr 4
            }
        }
    }

    /** Keeps the strongest response per pixel across the channels that were processed so far. */
    private fun computeGradients(plane: IntArray, weight: Int, reset: Boolean) {
        if (reset) magnitude.fill(0)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val a = plane[index - width - 1]
                val b = plane[index - width]
                val c = plane[index - width + 1]
                val d = plane[index - 1]
                val f = plane[index + 1]
                val g = plane[index + width - 1]
                val h = plane[index + width]
                val i = plane[index + width + 1]
                val gx = (c + 2 * f + i - a - 2 * d - g) * weight
                val gy = (g + 2 * h + i - a - 2 * b - c) * weight
                val absX = abs(gx)
                val absY = abs(gy)
                val value = max(absX, absY) + min(absX, absY) / 2
                if (value > magnitude[index]) {
                    magnitude[index] = value
                    gradientX[index] = gx
                    gradientY[index] = gy
                }
            }
        }
    }

    private fun noiseFloor(): Int {
        val histogram = IntArray(NOISE_HISTOGRAM_SIZE)
        for (value in magnitude) {
            histogram[min(value, NOISE_HISTOGRAM_SIZE - 1)]++
        }
        val target = magnitude.size / 2
        var seen = 0
        for (value in histogram.indices) {
            seen += histogram[value]
            if (seen >= target) return value * NOISE_MULTIPLIER
        }
        return NOISE_HISTOGRAM_SIZE
    }

    private fun isLocalMaximum(index: Int, value: Int): Boolean {
        val gx = gradientX[index]
        val gy = gradientY[index]
        val absX = abs(gx)
        val absY = abs(gy)
        val step = when {
            absX > 2 * absY -> 1
            absY > 2 * absX -> width
            (gx > 0) == (gy > 0) -> width + 1
            else -> width - 1
        }
        return value >= magnitude[index - step] && value > magnitude[index + step]
    }

    private fun findLines(): List<Line> {
        val diagonal = (hypot(width.toDouble(), height.toDouble()).toInt() + 2) / RHO_STEP + 1
        val rhoBins = 2 * diagonal + 1
        accumulator.fill(0)
        for (edge in 0 until edgeCount) {
            val index = edgeIndices[edge]
            val x = index % width
            val y = index / width
            val orientation = edgeMap[index] - 1
            for (spread in -HOUGH_ANGLE_SPREAD..HOUGH_ANGLE_SPREAD) {
                val angle = (orientation + spread + ANGLE_BINS) % ANGLE_BINS
                val rho = ((x * COS[angle] + y * SIN[angle]) / RHO_STEP).roundToInt()
                accumulator[angle * rhoBins + rho + diagonal]++
            }
        }

        val minDimension = min(width, height)
        val minVotes = max(MIN_LINE_VOTES, (minDimension * MIN_LINE_FRACTION).toInt())
        val peaks = ArrayList<Long>()
        for (angle in 0 until ANGLE_BINS) {
            for (rho in 2 until rhoBins - 2) {
                val votes = accumulator[angle * rhoBins + rho]
                if (votes < minVotes || !isHoughPeak(angle, rho, rhoBins, votes)) continue
                peaks += (votes.toLong() shl 32) or (angle * rhoBins + rho).toLong()
            }
        }
        peaks.sortDescending()

        val lines = ArrayList<Line>()
        for (peak in peaks) {
            if (lines.size >= MAX_REFINED_LINES) break
            val packed = (peak and 0xFFFFFFFFL).toInt()
            val rho = (packed % rhoBins - diagonal) * RHO_STEP
            val line = refineLine(packed / rhoBins, rho, minDimension) ?: continue
            if (lines.none { isSameLine(it, line) }) lines += line
        }
        val selected = selectLines(lines).toMutableList()
        addFrameBorders(selected)
        return selected
    }

    /**
     * Rows of text can outnumber the four page sides by far. A page border is always outside of
     * its content, so the outermost lines of each direction survive next to the longest ones.
     */
    private fun selectLines(lines: List<Line>): List<Line> {
        val midX = width / 2f
        val midY = height / 2f
        val selected = LinkedHashSet<Line>()
        for (bucket in lines.groupBy { ((it.angle + ANGLE_BUCKET / 2) / ANGLE_BUCKET).toInt() % (ANGLE_BINS / ANGLE_BUCKET) }.values) {
            val byPosition = bucket.sortedBy { line ->
                val signed = line.offset - midX * line.normalX - midY * line.normalY
                // Normals of one direction can point either way, compare them on a common axis.
                if (line.normalY < 0 || (line.normalY == 0f && line.normalX < 0)) -signed else signed
            }
            selected += byPosition.take(OUTERMOST_LINES)
            selected += byPosition.takeLast(OUTERMOST_LINES)
            selected += bucket.sortedByDescending { it.length }.take(LONGEST_LINES)
        }
        return selected.toList()
    }

    private fun isHoughPeak(angle: Int, rho: Int, rhoBins: Int, votes: Int): Boolean {
        for (angleOffset in -2..2) {
            val neighbourAngle = (angle + angleOffset + ANGLE_BINS) % ANGLE_BINS
            for (rhoOffset in -2..2) {
                if (angleOffset == 0 && rhoOffset == 0) continue
                if (accumulator[neighbourAngle * rhoBins + rho + rhoOffset] > votes) return false
            }
        }
        return true
    }

    /** Fits a line through the edge pixels near a Hough peak and measures how long it really is. */
    private fun refineLine(angle: Int, rho: Int, minDimension: Int): Line? {
        val normalX = COS[angle]
        val normalY = SIN[angle]
        var count = 0
        var sumX = 0f
        var sumY = 0f
        var sumXX = 0f
        var sumYY = 0f
        var sumXY = 0f
        for (edge in 0 until edgeCount) {
            val index = edgeIndices[edge]
            val x = (index % width).toFloat()
            val y = (index / width).toFloat()
            if (abs(x * normalX + y * normalY - rho) > PEAK_BAND) continue
            if (angleDistance(edgeMap[index] - 1, angle) > INLIER_ANGLE) continue
            count++
            sumX += x
            sumY += y
            sumXX += x * x
            sumYY += y * y
            sumXY += x * y
        }
        if (count < MIN_LINE_VOTES) return null

        val meanX = sumX / count
        val meanY = sumY / count
        val covXX = sumXX / count - meanX * meanX
        val covYY = sumYY / count - meanY * meanY
        val covXY = sumXY / count - meanX * meanY
        val theta = 0.5f * atan2(2 * covXY, covXX - covYY)
        val dirX = cos(theta)
        val dirY = sin(theta)
        val fittedNormalX = -dirY
        val fittedNormalY = dirX
        val offset = meanX * fittedNormalX + meanY * fittedNormalY

        val length = measureSegment(fittedNormalX, fittedNormalY, offset, dirX, dirY)
        if (length < minDimension * MIN_SEGMENT_FRACTION) return null
        if (flipsSidesAlongLine(fittedNormalX, fittedNormalY, offset, dirX, dirY)) return null
        return Line(fittedNormalX, fittedNormalY, offset, length)
    }

    /**
     * Walking along a page border, the brighter side stays the same. On a pattern like a checkered
     * tablecloth it changes every few centimetres, which rules such a line out as a page side.
     * Only changes along the line count: a contact shadow also has two edges of opposite direction,
     * but those lie next to each other.
     */
    private fun flipsSidesAlongLine(normalX: Float, normalY: Float, offset: Float, dirX: Float, dirY: Float): Boolean {
        var flips = 0
        var lastSign = 0
        var position = segmentStart
        while (position <= segmentEnd) {
            val x = dirX * position + normalX * offset
            val y = dirY * position + normalY * offset
            val aheadX = (x + normalX * POLARITY_DISTANCE).roundToInt()
            val aheadY = (y + normalY * POLARITY_DISTANCE).roundToInt()
            val behindX = (x - normalX * POLARITY_DISTANCE).roundToInt()
            val behindY = (y - normalY * POLARITY_DISTANCE).roundToInt()
            position += POLARITY_STEP
            if (aheadX !in 0 until width || aheadY !in 0 until height) continue
            if (behindX !in 0 until width || behindY !in 0 until height) continue
            val difference = blurredLuma[aheadY * width + aheadX] - blurredLuma[behindY * width + behindX]
            if (abs(difference) < POLARITY_MIN_DIFFERENCE) continue
            val sign = if (difference > 0) 1 else -1
            if (lastSign != 0 && sign != lastSign) flips++
            lastSign = sign
        }
        return flips >= POLARITY_MAX_FLIPS
    }

    private fun measureSegment(normalX: Float, normalY: Float, offset: Float, dirX: Float, dirY: Float): Float {
        val span = (width + height)
        val covered = BooleanArray(2 * span + 1)
        val lineAngle = orientationOf(normalX, normalY)
        for (edge in 0 until edgeCount) {
            val index = edgeIndices[edge]
            val x = (index % width).toFloat()
            val y = (index / width).toFloat()
            if (abs(x * normalX + y * normalY - offset) > INLIER_DISTANCE) continue
            if (angleDistance(edgeMap[index] - 1, lineAngle) > INLIER_ANGLE) continue
            covered[(x * dirX + y * dirY).roundToInt() + span] = true
        }
        val maxGap = (min(width, height) * MAX_GAP_FRACTION).toInt()
        var best = 0
        var current = 0
        var gap = 0
        for (position in covered.indices) {
            if (covered[position]) {
                current += 1 + gap
                gap = 0
                if (current > best) {
                    best = current
                    segmentEnd = position - span
                    segmentStart = segmentEnd - best + 1
                }
            } else if (current > 0) {
                gap++
                if (gap > maxGap) {
                    current = 0
                    gap = 0
                }
            }
        }
        return best.toFloat()
    }

    private fun isSameLine(first: Line, second: Line): Boolean {
        if (angleDistance(first.angle.roundToInt(), second.angle.roundToInt()) > DUPLICATE_ANGLE) return false
        val midX = width / 2f
        val midY = height / 2f
        val firstDistance = midX * first.normalX + midY * first.normalY - first.offset
        val secondDistance = midX * second.normalX + midY * second.normalY - second.offset
        val sameDirection = first.normalX * second.normalX + first.normalY * second.normalY > 0
        val difference = if (sameDirection) firstDistance - secondDistance else firstDistance + secondDistance
        return abs(difference) < DUPLICATE_DISTANCE
    }

    private fun addFrameBorders(lines: MutableList<Line>) {
        val right = (width - 1).toFloat()
        val bottom = (height - 1).toFloat()
        lines += Line(0f, 1f, 0f, width.toFloat(), isBorder = true)
        lines += Line(0f, 1f, bottom, width.toFloat(), isBorder = true)
        lines += Line(1f, 0f, 0f, height.toFloat(), isBorder = true)
        lines += Line(1f, 0f, right, height.toFloat(), isBorder = true)
    }

    private fun bestQuad(lines: List<Line>): Candidate? {
        val minDistance = min(width, height) * MIN_SIDE_DISTANCE_FRACTION
        val pairs = ArrayList<Pair<Line, Line>>()
        for (first in lines.indices) {
            for (second in first + 1 until lines.size) {
                val a = lines[first]
                val b = lines[second]
                if (angleDistance(a.angle.roundToInt(), b.angle.roundToInt()) > MAX_OPPOSITE_ANGLE) continue
                if (separation(a, b) < minDistance) continue
                pairs += a to b
            }
        }

        var best: Candidate? = null
        for (first in pairs.indices) {
            val (a1, a2) = pairs[first]
            for (second in first + 1 until pairs.size) {
                val (b1, b2) = pairs[second]
                if (a1 === b1 || a1 === b2 || a2 === b1 || a2 === b2) continue
                if (a1.isBorder.toInt() + a2.isBorder.toInt() + b1.isBorder.toInt() + b2.isBorder.toInt() > MAX_BORDER_SIDES) continue
                if (angleDistance(a1.angle.roundToInt(), b1.angle.roundToInt()) < MIN_ADJACENT_ANGLE) continue
                val corners = quadCorners(a1, b1, a2, b2) ?: continue
                // Side i runs from corner i to corner i + 1, see quadCorners.
                val score = scoreQuad(corners, listOf(b1.isBorder, a2.isBorder, b2.isBorder, a1.isBorder))
                if (score > (best?.score ?: MIN_SCORE)) best = Candidate(corners, score)
            }
        }
        return best
    }

    private fun Boolean.toInt() = if (this) 1 else 0

    private fun separation(a: Line, b: Line): Float {
        val midX = width / 2f
        val midY = height / 2f
        val projection = midX * a.normalX + midY * a.normalY - a.offset
        val nearestX = midX - projection * a.normalX
        val nearestY = midY - projection * a.normalY
        return abs(nearestX * b.normalX + nearestY * b.normalY - b.offset)
    }

    private fun quadCorners(a1: Line, b1: Line, a2: Line, b2: Line): Array<Point>? {
        val p0 = intersect(a1, b1) ?: return null
        val p1 = intersect(b1, a2) ?: return null
        val p2 = intersect(a2, b2) ?: return null
        val p3 = intersect(b2, a1) ?: return null
        val corners = arrayOf(p0, p1, p2, p3)
        val marginX = width * CORNER_OUTSIDE_FRACTION
        val marginY = height * CORNER_OUTSIDE_FRACTION
        for (corner in corners) {
            if (corner.x < -marginX || corner.x > width + marginX) return null
            if (corner.y < -marginY || corner.y > height + marginY) return null
        }
        return if (isConvex(corners)) corners else null
    }

    private fun intersect(a: Line, b: Line): Point? {
        val determinant = a.normalX * b.normalY - a.normalY * b.normalX
        if (abs(determinant) < 1e-3f) return null
        return Point(
            (a.offset * b.normalY - b.offset * a.normalY) / determinant,
            (a.normalX * b.offset - b.normalX * a.offset) / determinant,
        )
    }

    private fun isConvex(corners: Array<Point>): Boolean {
        var sign = 0
        for (i in corners.indices) {
            val p = corners[i]
            val q = corners[(i + 1) % 4]
            val r = corners[(i + 2) % 4]
            val cross = (q.x - p.x) * (r.y - q.y) - (q.y - p.y) * (r.x - q.x)
            val current = if (cross > 0) 1 else -1
            if (sign != 0 && current != sign) return false
            sign = current
        }
        return true
    }

    private fun scoreQuad(corners: Array<Point>, borderSides: List<Boolean>): Float {
        val area = polygonArea(corners) / (width * height)
        if (area < MIN_AREA_FRACTION || area > MAX_AREA_FRACTION) return 0f

        val center = Point(corners.map { it.x }.average().toFloat(), corners.map { it.y }.average().toFloat())
        var supportSum = 0f
        var weakest = 1f
        var contrastSum = 0f
        var brighterInside = 0
        for (side in 0 until 4) {
            val start = corners[side]
            val end = corners[(side + 1) % 4]
            val support = if (borderSides[side]) BORDER_SUPPORT else sideSupport(start, end)
            supportSum += support
            weakest = min(weakest, support)
            val contrast = if (borderSides[side]) 0f else sideContrast(start, end, center)
            contrastSum += min(abs(contrast) / FULL_CONTRAST, 1f)
            if (contrast >= 0f) brighterInside++
        }
        val support = 0.6f * supportSum / 4 + 0.4f * weakest
        if (support < MIN_SUPPORT) return 0f

        val rectangularity = 1f - cornerSkew(corners)
        val aspect = aspectFactor(corners)
        val sizeFactor = 0.3f + 0.7f * sqrt(area)
        val contrastFactor = 0.75f + 0.25f * contrastSum / 4
        val polarityFactor = 0.8f + 0.2f * brighterInside / 4
        return support * sqrt(support) * sizeFactor * (0.55f + 0.45f * rectangularity) *
            aspect * contrastFactor * polarityFactor
    }

    private fun polygonArea(corners: Array<Point>): Float {
        var sum = 0f
        for (i in corners.indices) {
            val p = corners[i]
            val q = corners[(i + 1) % 4]
            sum += p.x * q.y - q.x * p.y
        }
        return abs(sum) / 2
    }

    private fun sideSupport(start: Point, end: Point): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        if (length < 1f) return 0f
        val normalX = -dy / length
        val normalY = dx / length
        val sideAngle = orientationOf(normalX, normalY)
        var inside = 0
        var hits = 0
        for (sample in 0 until SIDE_SAMPLES) {
            val t = (sample + 0.5f) / SIDE_SAMPLES
            val x = start.x + dx * t
            val y = start.y + dy * t
            if (x < 2 || y < 2 || x >= width - 2 || y >= height - 2) continue
            inside++
            if (hasEdgeNear(x, y, normalX, normalY, sideAngle)) hits++
        }
        // A side that leaves the frame is judged by the part that can actually be seen.
        return if (inside < SIDE_SAMPLES / 4) 0f else hits.toFloat() / inside * min(1f, inside / (SIDE_SAMPLES * 0.6f))
    }

    private fun hasEdgeNear(x: Float, y: Float, normalX: Float, normalY: Float, sideAngle: Int): Boolean {
        for (offset in -SIDE_TOLERANCE..SIDE_TOLERANCE) {
            val sampleX = (x + normalX * offset).roundToInt()
            val sampleY = (y + normalY * offset).roundToInt()
            if (sampleX !in 0 until width || sampleY !in 0 until height) continue
            val edge = edgeMap[sampleY * width + sampleX].toInt()
            if (edge != 0 && angleDistance(edge - 1, sideAngle) <= SIDE_ANGLE_TOLERANCE) return true
        }
        return false
    }

    /** Mean brightness inside minus outside along a side. Paper is usually the brighter one. */
    private fun sideContrast(start: Point, end: Point, center: Point): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        if (length < 1f) return 0f
        var normalX = -dy / length
        var normalY = dx / length
        if ((center.x - start.x) * normalX + (center.y - start.y) * normalY < 0) {
            normalX = -normalX
            normalY = -normalY
        }
        var inside = 0L
        var outside = 0L
        var samples = 0
        for (sample in 0 until SIDE_SAMPLES) {
            val t = (sample + 0.5f) / SIDE_SAMPLES
            val x = start.x + dx * t
            val y = start.y + dy * t
            val inX = (x + normalX * CONTRAST_DISTANCE).roundToInt()
            val inY = (y + normalY * CONTRAST_DISTANCE).roundToInt()
            val outX = (x - normalX * CONTRAST_DISTANCE).roundToInt()
            val outY = (y - normalY * CONTRAST_DISTANCE).roundToInt()
            if (inX !in 0 until width || inY !in 0 until height) continue
            if (outX !in 0 until width || outY !in 0 until height) continue
            inside += blurredLuma[inY * width + inX]
            outside += blurredLuma[outY * width + outX]
            samples++
        }
        return if (samples == 0) 0f else (inside - outside).toFloat() / samples
    }

    private fun cornerSkew(corners: Array<Point>): Float {
        var sum = 0f
        for (i in corners.indices) {
            val p = corners[i]
            val q = corners[(i + 1) % 4]
            val r = corners[(i + 2) % 4]
            val ax = p.x - q.x
            val ay = p.y - q.y
            val bx = r.x - q.x
            val by = r.y - q.y
            sum += abs(ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))
        }
        return sum / 4
    }

    private fun aspectFactor(corners: Array<Point>): Float {
        val first = distance(corners[0], corners[1]) + distance(corners[2], corners[3])
        val second = distance(corners[1], corners[2]) + distance(corners[3], corners[0])
        val ratio = max(first, second) / max(min(first, second), 1f)
        return when {
            ratio < 1.1f -> 0.85f
            ratio <= 1.8f -> 1f
            ratio <= 3.2f -> 0.9f
            else -> 0.7f
        }
    }

    private fun distance(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

    private fun orientationOf(normalX: Float, normalY: Float): Int {
        val degrees = Math.toDegrees(atan2(normalY, normalX).toDouble()).roundToInt()
        return (degrees + 360) % 180
    }

    private fun angleDistance(first: Int, second: Int): Int {
        val difference = abs(first - second) % ANGLE_BINS
        return min(difference, ANGLE_BINS - difference)
    }

    private fun toQuad(corners: Array<Point>): DocumentQuad {
        val clamped = corners.map {
            Point(it.x.coerceIn(0f, (width - 1).toFloat()), it.y.coerceIn(0f, (height - 1).toFloat()))
        }
        val first = clamped.indices.minBy { clamped[it].x + clamped[it].y }
        val ordered = List(4) { clamped[(first + it) % 4] }
        val clockwise = (ordered[1].x - ordered[0].x) * (ordered[2].y - ordered[1].y) -
            (ordered[1].y - ordered[0].y) * (ordered[2].x - ordered[1].x) > 0
        return if (clockwise) {
            DocumentQuad(ordered[0], ordered[1], ordered[2], ordered[3])
        } else {
            DocumentQuad(ordered[0], ordered[3], ordered[2], ordered[1])
        }
    }

    private companion object {
        const val ANGLE_BINS = 180
        const val CHROMA_WEIGHT = 2
        const val MIN_EDGE_MAGNITUDE = 10
        const val NOISE_MULTIPLIER = 3
        const val NOISE_HISTOGRAM_SIZE = 256
        const val MIN_EDGE_PIXELS = 40
        const val TEXTURE_MIN_DISTANCE = 5
        const val TEXTURE_REACH = 10
        const val TEXTURE_NEIGHBOUR_STRENGTH = 40
        const val TEXTURE_ANGLE = 25
        /** Slightly tilted edges form pixel stairs whose local gradient is several degrees off. */
        const val HOUGH_ANGLE_SPREAD = 9
        /** Coarse enough that a tilted line, which never lies exactly on a bin, still forms one peak. */
        const val RHO_STEP = 3
        const val PEAK_BAND = 3.5f
        const val MIN_LINE_VOTES = 24
        const val MIN_LINE_FRACTION = 0.14f
        const val MAX_REFINED_LINES = 70
        const val ANGLE_BUCKET = 20
        const val OUTERMOST_LINES = 2
        const val LONGEST_LINES = 3
        const val INLIER_DISTANCE = 2.2f
        const val INLIER_ANGLE = 16
        const val MIN_SEGMENT_FRACTION = 0.16f
        const val MAX_GAP_FRACTION = 0.1f
        const val POLARITY_DISTANCE = 6
        const val POLARITY_STEP = 5
        const val POLARITY_MIN_DIFFERENCE = 16
        const val POLARITY_MAX_FLIPS = 3
        const val DUPLICATE_ANGLE = 3
        const val DUPLICATE_DISTANCE = 5f
        const val MIN_SIDE_DISTANCE_FRACTION = 0.2f
        const val MAX_OPPOSITE_ANGLE = 28
        const val MIN_ADJACENT_ANGLE = 45
        const val MAX_BORDER_SIDES = 2
        const val CORNER_OUTSIDE_FRACTION = 0.16f
        const val MIN_AREA_FRACTION = 0.1f
        const val MAX_AREA_FRACTION = 0.995f
        const val MIN_SCORE = 0.08f
        const val MIN_SUPPORT = 0.3f
        const val BORDER_SUPPORT = 0.35f
        const val SIDE_SAMPLES = 40
        const val SIDE_TOLERANCE = 2
        const val SIDE_ANGLE_TOLERANCE = 18
        const val CONTRAST_DISTANCE = 5
        const val FULL_CONTRAST = 24f

        val COS = FloatArray(ANGLE_BINS) { cos(Math.toRadians(it.toDouble())).toFloat() }
        val SIN = FloatArray(ANGLE_BINS) { sin(Math.toRadians(it.toDouble())).toFloat() }
    }
}
