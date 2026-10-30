package com.akuras.sheetly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamePatternTest {

    @Test
    fun resolvesAllKnownTokens() {
        val value = resolveFileName("scan_{yyyy}{MM}{dd}_{HH}{mm}{ss}")
        assertFalse(value.contains("{"))
        assertTrue(value.startsWith("scan_"))
    }

    @Test
    fun keepsUnknownTokensUntouched() {
        val value = resolveFileName("doc_{unknown}_{yy}")
        assertTrue(value.contains("{unknown}"))
        assertFalse(value.contains("{yy}"))
    }

    @Test
    fun replacesCharactersNotAllowedInFileNames() {
        assertEquals("a_b_c", sanitizeFileName("a/b:c"))
    }

    @Test
    fun usesFallbackWhenNothingUsableRemains() {
        assertEquals("scan", sanitizeFileName("   "))
        assertEquals("scan", sanitizeFileName("..."))
        assertEquals("", sanitizeFileName(" ", fallback = ""))
    }
}
