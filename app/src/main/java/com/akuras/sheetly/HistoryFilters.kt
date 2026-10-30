package com.akuras.pdfscanner

fun filterPdfsByQuery(items: List<SavedPdf>, query: String): List<SavedPdf> {
    return filterByName(items, query) { it.name }
}

internal fun <T> filterByName(items: List<T>, query: String, name: (T) -> String): List<T> {
    val q = query.trim()
    if (q.isEmpty()) return items
    return items.filter { name(it).contains(q, ignoreCase = true) }
}
