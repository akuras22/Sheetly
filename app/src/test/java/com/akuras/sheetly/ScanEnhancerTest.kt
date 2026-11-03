package com.akuras.sheetly

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanEnhancerTest {

    private val size = 600
    private val textRows = 200..260
    private val textColumns = 100..500

    /** Paper with a dark shadow towards the right and a black text block. */
    private fun shadowedPage(): IntArray {
        return IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val paper = 235 - 135 * x / size
            val value = if (y in textRows && x in textColumns && x % 8 < 4) paper / 5 else paper
            grey(value)
        }
    }

    private fun grey(value: Int): Int {
        return (0xFF shl 24) or (value shl 16) or (value shl 8) or value
    }

    private fun value(pixels: IntArray, x: Int, y: Int): Int {
        return pixels[y * size + x] and 0xFF
    }

    @Test
    fun originalModeKeepsPixels() {
        val pixels = shadowedPage()
        ScanEnhancer.enhance(pixels, size, size, ScanMode.ORIGINAL)
        assertArrayEquals(shadowedPage(), pixels)
    }

    @Test
    fun documentModeRemovesShadowFromPaper() {
        val pixels = shadowedPage()
        ScanEnhancer.enhance(pixels, size, size, ScanMode.DOCUMENT)
        assertTrue(value(pixels, 40, 40) > 235)
        assertTrue(value(pixels, 560, 40) > 235)
        assertTrue(value(pixels, 560, 560) > 235)
    }

    @Test
    fun documentModeKeepsTextDark() {
        val pixels = shadowedPage()
        ScanEnhancer.enhance(pixels, size, size, ScanMode.DOCUMENT)
        assertTrue(value(pixels, 120, 230) < 90)
        assertTrue(value(pixels, 480, 230) < 90)
    }

    @Test
    fun blackWhiteModeSeparatesTextFromPaper() {
        val pixels = shadowedPage()
        ScanEnhancer.enhance(pixels, size, size, ScanMode.BLACK_WHITE)
        assertEquals(255, value(pixels, 560, 40))
        assertEquals(0, value(pixels, 120, 230))
        assertEquals(0, value(pixels, 480, 230))
    }

    @Test
    fun removesDeepShadowFromPaper() {
        val pixels = IntArray(size * size) { index ->
            grey(if (index % size > size / 2) 60 else 235)
        }
        ScanEnhancer.enhance(pixels, size, size, ScanMode.DOCUMENT)
        assertTrue(value(pixels, 40, 300) > 235)
        assertTrue(value(pixels, 560, 300) > 225)
    }

    /** A hand's shadow fades out softly. Its edge must turn white as well, not stay as a grey ring. */
    @Test
    fun removesSoftShadowEdgeWithoutHalo() {
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val distance = kotlin.math.hypot((x - 450).toDouble(), (y - 450).toDouble())
            val shade = ((distance - 80) / 160).coerceIn(0.0, 1.0)
            grey((235 * (0.45 + 0.55 * shade)).toInt())
        }
        ScanEnhancer.enhance(pixels, size, size, ScanMode.DOCUMENT)
        val darkest = (0 until size * size).minOf { pixels[it] and 0xFF }
        assertTrue("darkest paper pixel is $darkest", darkest > 225)
    }

    @Test
    fun keepsColouredBoxColoured() {
        val orange = (0xFF shl 24) or (210 shl 16) or (80 shl 8) or 20
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (x in 150..450 && y in 200..400) orange else grey(235)
        }
        ScanEnhancer.enhance(pixels, size, size, ScanMode.DOCUMENT)
        val center = pixels[300 * size + 300]
        val red = (center shr 16) and 0xFF
        val green = (center shr 8) and 0xFF
        assertTrue("red=$red green=$green", red > 190 && green < 130)
        assertTrue(value(pixels, 40, 40) > 235)
    }

    @Test
    fun keepsDarkPhotoOnPage() {
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val inPhoto = x in 150..450 && y in 150..450
            // A noisy dark picture on white paper, its brightness spreads over many values.
            grey(if (inPhoto) 40 + (x * 7 + y * 13) % 90 else 235)
        }
        val darkBefore = (150..450).count { value(pixels, it, 300) < 110 }
        ScanEnhancer.enhance(pixels, size, size, ScanMode.DOCUMENT)
        assertTrue(value(pixels, 40, 40) > 235)
        val darkAfter = (150..450).count { value(pixels, it, 300) < 140 }
        assertTrue("dark pixels before=$darkBefore after=$darkAfter", darkAfter > darkBefore / 2)
    }

    @Test
    fun keepsAlphaChannel() {
        val pixels = shadowedPage()
        ScanEnhancer.enhance(pixels, size, size, ScanMode.DOCUMENT)
        assertEquals(0xFF, pixels[0] ushr 24)
    }

    @Test
    fun handlesImagesSmallerThanOneCell() {
        val pixels = IntArray(9) { grey(200) }
        ScanEnhancer.enhance(pixels, 3, 3, ScanMode.BLACK_WHITE)
        assertEquals(9, pixels.size)
    }
}
