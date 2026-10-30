package com.akuras.pdfscanner

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
