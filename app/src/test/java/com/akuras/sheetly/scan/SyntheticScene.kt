package com.akuras.sheetly.scan

import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The situations a scanner meets on a desk, each with a known position of the page. */
internal enum class Scenario {
    DARK_DESK,
    WOOD_TABLE,
    WHITE_ON_LIGHT_TABLE,
    WHITE_ON_WHITE,
    SAME_BRIGHTNESS_COLOURED_TABLE,
    CLUTTERED_DESK,
    THUMB_ON_PAGE,
    LOW_LIGHT_NOISE,
    STEEP_ANGLE,
    FILLS_THE_FRAME,
    HARD_SHADOW,
    SMALL_PAGE,
    NARROW_RECEIPT,
    BENT_PAGE,
    CHECKERED_TABLECLOTH,
    SECOND_PAPER_BELOW,
    CORNER_OUTSIDE_FRAME,
    DARK_PAGE_ON_LIGHT_TABLE,
    MOTION_BLUR,
}

internal class SyntheticScene(val frame: ScanFrame, val quad: DocumentQuad)

/** Renders camera-like frames so detection quality can be measured without a device. */
internal object SceneRenderer {
    private const val WIDTH = 360
    private const val HEIGHT = 480

    private class Rgb(val r: FloatArray, val g: FloatArray, val b: FloatArray)

    fun render(scenario: Scenario, seed: Long): SyntheticScene {
        val random = Random(seed * 31 + scenario.ordinal)
        val quad = pageQuad(scenario, random)
        val image = Rgb(FloatArray(WIDTH * HEIGHT), FloatArray(WIDTH * HEIGHT), FloatArray(WIDTH * HEIGHT))
        paintBackground(image, scenario, random)
        if (scenario == Scenario.CLUTTERED_DESK) paintClutter(image, random)
        if (scenario == Scenario.SECOND_PAPER_BELOW) paintSecondPaper(image, quad, random)
        paintPageShadow(image, quad, scenario)
        paintPage(image, quad, scenario, random)
        paintLighting(image, scenario, random)
        if (scenario == Scenario.THUMB_ON_PAGE) paintThumb(image, quad, random)
        repeat(if (scenario == Scenario.MOTION_BLUR) 5 else 1) { blur(image) }
        addNoise(image, if (scenario == Scenario.LOW_LIGHT_NOISE) 9f else 2.5f, random)
        return SyntheticScene(toFrame(image), quad)
    }

    private fun pageQuad(scenario: Scenario, random: Random): DocumentQuad {
        fun jitter(amount: Float) = (random.nextFloat() - 0.5f) * 2 * amount
        val inset = when (scenario) {
            Scenario.FILLS_THE_FRAME -> 0.05f
            Scenario.STEEP_ANGLE -> 0.12f
            Scenario.SMALL_PAGE -> 0.3f
            else -> 0.14f + random.nextFloat() * 0.06f
        }
        val wobble = when (scenario) {
            Scenario.STEEP_ANGLE -> 0.05f
            Scenario.FILLS_THE_FRAME -> 0.015f
            else -> 0.05f
        }
        val taper = if (scenario == Scenario.STEEP_ANGLE) 0.13f + random.nextFloat() * 0.08f else 0f
        val narrow = if (scenario == Scenario.NARROW_RECEIPT) 0.2f else 0f
        val left = (inset + narrow) * WIDTH
        val right = (1 - inset - narrow) * WIDTH
        val top = inset * HEIGHT
        val bottom = (1 - inset) * HEIGHT
        fun x(value: Float) = value + jitter(wobble) * WIDTH
        fun y(value: Float) = value + jitter(wobble) * HEIGHT
        val quad = DocumentQuad(
            Point(x(left + taper * WIDTH), y(top)),
            Point(x(right - taper * WIDTH), y(top)),
            Point(x(right), y(bottom)),
            Point(x(left), y(bottom)),
        )
        val rotation = if (scenario == Scenario.FILLS_THE_FRAME) 0.0 else (random.nextDouble() - 0.5) * 0.5
        val rotated = rotate(quad, rotation)
        return if (scenario == Scenario.CORNER_OUTSIDE_FRAME) moveCornerOutside(rotated, random) else rotated
    }

    /** Pushes the whole page towards a frame corner until that page corner is cut off. */
    private fun moveCornerOutside(quad: DocumentQuad, random: Random): DocumentQuad {
        val corner = quad.corners[random.nextInt(4)]
        val shiftX = if (corner.x < WIDTH / 2f) -corner.x - 22f else WIDTH - corner.x + 22f
        val shiftY = if (corner.y < HEIGHT / 2f) -corner.y - 22f else HEIGHT - corner.y + 22f
        fun move(p: Point) = Point(p.x + shiftX, p.y + shiftY)
        return DocumentQuad(move(quad.topLeft), move(quad.topRight), move(quad.bottomRight), move(quad.bottomLeft))
    }

    private fun rotate(quad: DocumentQuad, radians: Double): DocumentQuad {
        val centerX = WIDTH / 2f
        val centerY = HEIGHT / 2f
        val cosine = kotlin.math.cos(radians).toFloat()
        val sine = kotlin.math.sin(radians).toFloat()
        fun turn(p: Point) = Point(
            centerX + (p.x - centerX) * cosine - (p.y - centerY) * sine,
            centerY + (p.x - centerX) * sine + (p.y - centerY) * cosine,
        )
        return DocumentQuad(turn(quad.topLeft), turn(quad.topRight), turn(quad.bottomRight), turn(quad.bottomLeft))
    }

    private fun paintBackground(image: Rgb, scenario: Scenario, random: Random) {
        val phase = random.nextFloat() * 10
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val index = y * WIDTH + x
                when (scenario) {
                    Scenario.WOOD_TABLE, Scenario.THUMB_ON_PAGE, Scenario.STEEP_ANGLE -> {
                        val grain = 18f * sin(y * 0.09f + 3f * sin(x * 0.012f + phase)) + 8f * sin(y * 0.41f + phase)
                        set(image, index, 142f + grain, 104f + grain, 70f + grain * 0.7f)
                    }
                    Scenario.WHITE_ON_LIGHT_TABLE -> set(image, index, 214f, 212f, 206f)
                    Scenario.WHITE_ON_WHITE -> set(image, index, 228f, 228f, 226f)
                    Scenario.SAME_BRIGHTNESS_COLOURED_TABLE -> set(image, index, 150f, 212f, 255f)
                    Scenario.DARK_PAGE_ON_LIGHT_TABLE -> set(image, index, 226f, 224f, 218f)
                    Scenario.CHECKERED_TABLECLOTH -> {
                        val rotatedX = x * 0.96f + y * 0.28f + phase * 10
                        val rotatedY = y * 0.96f - x * 0.28f + phase * 10
                        val dark = ((rotatedX / 28f).toInt() + (rotatedY / 28f).toInt()) % 2 == 0
                        if (dark) set(image, index, 170f, 40f, 44f) else set(image, index, 232f, 226f, 220f)
                    }
                    else -> set(image, index, 52f, 50f, 54f)
                }
            }
        }
    }

    private fun paintClutter(image: Rgb, random: Random) {
        repeat(5) {
            val x0 = random.nextInt(WIDTH)
            val y0 = random.nextInt(HEIGHT)
            val width = 40 + random.nextInt(140)
            val height = 10 + random.nextInt(90)
            val r = 60f + random.nextInt(190)
            val g = 60f + random.nextInt(190)
            val b = 60f + random.nextInt(190)
            for (y in max(y0, 0) until min(y0 + height, HEIGHT)) {
                for (x in max(x0, 0) until min(x0 + width, WIDTH)) {
                    set(image, y * WIDTH + x, r, g, b)
                }
            }
        }
    }

    private fun paintSecondPaper(image: Rgb, quad: DocumentQuad, random: Random) {
        val angle = (random.nextDouble() - 0.5) * 0.35
        val shiftX = 18f + random.nextFloat() * 22f
        val shiftY = 14f + random.nextFloat() * 22f
        val rotated = rotate(quad, angle)
        fun move(p: Point) = Point(p.x + shiftX, p.y + shiftY)
        val below = DocumentQuad(move(rotated.topLeft), move(rotated.topRight), move(rotated.bottomRight), move(rotated.bottomLeft))
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                if (contains(below, x + 0.5f, y + 0.5f)) set(image, y * WIDTH + x, 232f, 232f, 228f)
            }
        }
    }

    /** The soft contact shadow is often the only trace of a white page on a white table. */
    private fun paintPageShadow(image: Rgb, quad: DocumentQuad, scenario: Scenario) {
        val strength = when (scenario) {
            Scenario.WHITE_ON_WHITE -> 0.08f
            Scenario.WHITE_ON_LIGHT_TABLE -> 0.12f
            else -> 0.25f
        }
        val shifted = quad.scaled(1f, 1f).let {
            DocumentQuad(shift(it.topLeft), shift(it.topRight), shift(it.bottomRight), shift(it.bottomLeft))
        }
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                if (contains(shifted, x + 0.5f, y + 0.5f)) {
                    val index = y * WIDTH + x
                    image.r[index] *= 1 - strength
                    image.g[index] *= 1 - strength
                    image.b[index] *= 1 - strength
                }
            }
        }
    }

    private fun shift(point: Point) = Point(point.x + 2.5f, point.y + 3f)

    private fun paintPage(image: Rgb, quad: DocumentQuad, scenario: Scenario, random: Random) {
        val homography = Homography.fromUnitSquare(quad)
        val paper = when (scenario) {
            Scenario.WHITE_ON_WHITE -> 240f
            Scenario.SAME_BRIGHTNESS_COLOURED_TABLE -> 208f
            Scenario.DARK_PAGE_ON_LIGHT_TABLE -> 74f
            else -> 245f
        }
        val bend = if (scenario == Scenario.BENT_PAGE) 0.02f + random.nextFloat() * 0.015f else 0f
        val lineGap = 0.03f + random.nextFloat() * 0.02f
        val inverse = homography.inverse()
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val (u, flatV) = inverse.map(x + 0.5f, y + 0.5f)
                val v = flatV + bend * sin(u * Math.PI.toFloat())
                if (u !in 0f..1f || v !in 0f..1f) continue
                val isText = u in 0.1f..0.9f && v in 0.1f..0.85f &&
                    (v / lineGap) % 1f < 0.45f && sin(u * 170f + v * 40f) > -0.55f
                val value = if (isText) (if (paper < 128f) 228f else 40f) else paper
                set(image, y * WIDTH + x, value, value, value - if (isText) 0f else 4f)
            }
        }
    }

    private fun paintLighting(image: Rgb, scenario: Scenario, random: Random) {
        val slope = 0.15f + random.nextFloat() * 0.2f
        val hardShadow = scenario == Scenario.HARD_SHADOW
        val shadowX = WIDTH * (0.3f + random.nextFloat() * 0.4f)
        val dim = if (scenario == Scenario.LOW_LIGHT_NOISE) 0.42f else 1f
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val index = y * WIDTH + x
                var light = dim * (1f - slope * x / WIDTH)
                if (hardShadow && x + y * 0.2f > shadowX) light *= 0.5f
                image.r[index] *= light
                image.g[index] *= light
                image.b[index] *= light
            }
        }
    }

    private fun paintThumb(image: Rgb, quad: DocumentQuad, random: Random) {
        val side = random.nextInt(4)
        val start = quad.corners[side]
        val end = quad.corners[(side + 1) % 4]
        val t = 0.25f + random.nextFloat() * 0.5f
        val centerX = start.x + (end.x - start.x) * t
        val centerY = start.y + (end.y - start.y) * t
        val radius = 34f
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val dx = (x - centerX) / radius
                val dy = (y - centerY) / (radius * 1.5f)
                if (dx * dx + dy * dy < 1f) {
                    val shade = 1f - 0.2f * (dx * dx + dy * dy)
                    set(image, y * WIDTH + x, 214f * shade, 160f * shade, 134f * shade)
                }
            }
        }
    }

    private fun blur(image: Rgb) {
        for (channel in listOf(image.r, image.g, image.b)) {
            val copy = channel.copyOf()
            for (y in 1 until HEIGHT - 1) {
                for (x in 1 until WIDTH - 1) {
                    val index = y * WIDTH + x
                    channel[index] = (copy[index] * 4 + copy[index - 1] + copy[index + 1] +
                        copy[index - WIDTH] + copy[index + WIDTH]) / 8
                }
            }
        }
    }

    private fun addNoise(image: Rgb, sigma: Float, random: Random) {
        for (index in image.r.indices) {
            val luminance = (random.nextGaussian() * sigma).toFloat()
            image.r[index] += luminance
            image.g[index] += luminance
            image.b[index] += luminance
        }
    }

    private fun toFrame(image: Rgb): ScanFrame {
        val luma = IntArray(WIDTH * HEIGHT)
        val chromaU = IntArray(WIDTH * HEIGHT)
        val chromaV = IntArray(WIDTH * HEIGHT)
        for (index in luma.indices) {
            val r = image.r[index].coerceIn(0f, 255f)
            val g = image.g[index].coerceIn(0f, 255f)
            val b = image.b[index].coerceIn(0f, 255f)
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            luma[index] = y.toInt()
            chromaU[index] = (128 + 0.492f * (b - y)).toInt()
            chromaV[index] = (128 + 0.877f * (r - y)).toInt()
        }
        return ScanFrame(WIDTH, HEIGHT, luma, chromaU, chromaV)
    }

    private fun set(image: Rgb, index: Int, r: Float, g: Float, b: Float) {
        image.r[index] = r
        image.g[index] = g
        image.b[index] = b
    }

    fun contains(quad: DocumentQuad, x: Float, y: Float): Boolean {
        val corners = quad.corners
        var positive = 0
        var negative = 0
        for (i in corners.indices) {
            val p = corners[i]
            val q = corners[(i + 1) % 4]
            val cross = (q.x - p.x) * (y - p.y) - (q.y - p.y) * (x - p.x)
            if (cross >= 0) positive++ else negative++
        }
        return positive == 0 || negative == 0
    }

    /** Overlap of two quads inside the frame, 1 means identical. */
    fun intersectionOverUnion(first: DocumentQuad, second: DocumentQuad): Float {
        var both = 0
        var either = 0
        var y = 0.5f
        while (y < HEIGHT) {
            var x = 0.5f
            while (x < WIDTH) {
                val inFirst = contains(first, x, y)
                val inSecond = contains(second, x, y)
                if (inFirst && inSecond) both++
                if (inFirst || inSecond) either++
                x += 2f
            }
            y += 2f
        }
        return if (either == 0) 0f else both.toFloat() / either
    }
}
