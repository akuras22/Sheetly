package com.akuras.pdfscanner

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryFiltersTest {

    @Test
    fun returnsAllWhenQueryBlank() {
        val items = listOf("Invoice.pdf", "Contract.pdf")

        assertEquals(items, filterByName(items, " ") { it })
    }

    @Test
    fun filtersCaseInsensitiveByName() {
        val items = listOf("Invoice_March.pdf", "Contract.pdf")

        assertEquals(listOf("Invoice_March.pdf"), filterByName(items, "inVOICE") { it })
    }
}
