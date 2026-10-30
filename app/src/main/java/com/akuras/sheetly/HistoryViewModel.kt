package com.akuras.sheetly

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryUiState(
    val items: List<SavedPdf> = emptyList(),
    val allItems: List<SavedPdf> = emptyList(),
    val externalItems: List<SavedPdf> = emptyList(),
    val selectionMode: Boolean = false,
    val isMerging: Boolean = false,
    val isSavingScan: Boolean = false,
    val isToolRunning: Boolean = false,
    // Insertion order is the merge order
    val selectedUris: Set<String> = emptySet(),
    val thumbnailSeed: Int = 0,
    val sortOrder: PdfSortOrder = PdfSortOrder.DATE_DESC,
    val searchQuery: String = "",
) {
    val selectedItems: List<SavedPdf>
        get() {
            val byUri = (allItems + externalItems).associateBy { it.uri.toString() }
            return selectedUris.mapNotNull(byUri::get)
        }
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val _messages = MutableSharedFlow<Int>()
    val messages: SharedFlow<Int> = _messages

    var uiState by mutableStateOf(HistoryUiState())
        private set

    fun refresh() {
        viewModelScope.launch {
            val allItems = withContext(Dispatchers.IO) {
                PdfStorage.querySavedPdfs(appContext, uiState.sortOrder)
            }
            val known = (allItems + uiState.externalItems).mapTo(mutableSetOf()) { it.uri.toString() }
            uiState = uiState.copy(
                allItems = allItems,
                selectedUris = uiState.selectedUris.filterTo(linkedSetOf()) { it in known }
            ).withVisibleItems()
        }
    }

    /** Falls back to the scanner's own PDF if the enhanced PDF cannot be built. */
    fun saveScan(pageUris: List<Uri>, fallbackPdfUri: Uri?) {
        viewModelScope.launch {
            uiState = uiState.copy(isSavingScan = true)
            val saved = withContext(Dispatchers.IO) {
                val pattern = loadFileNamePattern(appContext)
                PdfStorage.saveScanToDownloads(appContext, pageUris, loadScanMode(appContext), pattern)
                    ?: fallbackPdfUri?.let { PdfStorage.savePdfToDownloads(appContext, it, pattern) }
            }
            uiState = uiState.copy(isSavingScan = false)
            _messages.emit(if (saved != null) R.string.saved_to_downloads else R.string.unable_to_save_pdf)
            refresh()
        }
    }

    /** Runs a tool that writes a new PDF and reports the outcome. */
    fun runTool(successMessage: Int, tool: suspend () -> Boolean) {
        if (uiState.isToolRunning) return
        viewModelScope.launch {
            uiState = uiState.copy(isToolRunning = true)
            val ok = withContext(Dispatchers.IO) { runCatching { tool() }.getOrDefault(false) }
            uiState = uiState.copy(isToolRunning = false)
            _messages.emit(if (ok) successMessage else R.string.tool_failed)
            if (ok) refresh()
        }
    }

    fun importPdf(uri: Uri) {
        runTool(R.string.tool_import_done) {
            PdfTools.importPdf(appContext, uri, queryPdfDisplayName(appContext, uri)) != null
        }
    }

    fun setSearchQuery(query: String) {
        uiState = uiState.copy(searchQuery = query).withVisibleItems()
    }

    fun setSortOrder(order: PdfSortOrder) {
        if (order == uiState.sortOrder) return
        uiState = uiState.copy(sortOrder = order)
        refresh()
    }

    fun enterSelectionMode(initial: Uri? = null) {
        uiState = uiState.copy(
            selectionMode = true,
            selectedUris = initial?.let { linkedSetOf(it.toString()) } ?: emptySet()
        ).withVisibleItems()
    }

    fun exitSelectionMode() {
        uiState = uiState.copy(
            selectionMode = false,
            selectedUris = emptySet(),
            externalItems = emptyList()
        ).withVisibleItems()
    }

    fun toggleSelection(uri: Uri) {
        val key = uri.toString()
        val selected = uiState.selectedUris
        uiState = uiState.copy(
            selectedUris = if (selected.contains(key)) selected - key else selected + key
        )
    }

    fun selectAll() {
        uiState = uiState.copy(selectedUris = uiState.selectedUris + uiState.items.map { it.uri.toString() })
    }

    fun addExternal(items: List<SavedPdf>) {
        uiState = uiState.copy(
            externalItems = (uiState.externalItems + items).distinctBy { it.uri.toString() },
            selectedUris = uiState.selectedUris + items.map { it.uri.toString() }
        ).withVisibleItems()
    }

    fun merge(sources: List<SavedPdf>) {
        if (sources.size < 2 || uiState.isMerging) return
        viewModelScope.launch {
            uiState = uiState.copy(isMerging = true)
            val merged = withContext(Dispatchers.IO) {
                PdfStorage.mergePdfsToDownloads(appContext, sources.map { it.uri }, loadFileNamePattern(appContext))
            }
            uiState = uiState.copy(isMerging = false)
            if (merged != null) {
                _messages.emit(R.string.toast_merge_success)
                exitSelectionMode()
                refresh()
            } else {
                _messages.emit(R.string.toast_merge_failed)
            }
        }
    }

    /** Removes the selected saved PDFs. External PDFs only leave the selection, their files stay. */
    fun deleteSelected() {
        val uris = uiState.selectedItems.filterNot { it.isExternal }.map { it.uri }
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val deletedCount = withContext(Dispatchers.IO) {
                uris.count { uri -> PdfStorage.deletePdf(appContext, uri) }
            }
            _messages.emit(if (deletedCount == uris.size) R.string.toast_deleted else R.string.toast_delete_failed)
            if (deletedCount > 0) {
                exitSelectionMode()
                refresh()
            }
        }
    }

    fun rename(uri: Uri, newName: String) {
        viewModelScope.launch {
            val renamed = withContext(Dispatchers.IO) {
                PdfStorage.renamePdf(appContext, uri, newName)
            }
            if (renamed) {
                _messages.emit(R.string.toast_renamed)
                refresh()
            } else {
                _messages.emit(R.string.toast_rename_failed)
            }
        }
    }

    fun delete(uri: Uri) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                PdfStorage.deletePdf(appContext, uri)
            }
            if (deleted) {
                uiState = uiState.copy(
                    allItems = uiState.allItems.filterNot { it.uri == uri },
                    selectedUris = uiState.selectedUris - uri.toString()
                ).withVisibleItems()
                _messages.emit(R.string.toast_deleted)
            } else {
                _messages.emit(R.string.toast_delete_failed)
            }
        }
    }

    fun onReordered(success: Boolean) {
        viewModelScope.launch {
            if (success) {
                _messages.emit(R.string.toast_pages_updated)
                uiState = uiState.copy(thumbnailSeed = uiState.thumbnailSeed + 1)
                refresh()
            } else {
                _messages.emit(R.string.toast_pages_update_failed)
            }
        }
    }

    private fun HistoryUiState.withVisibleItems(): HistoryUiState {
        val candidates = if (selectionMode) allItems + externalItems else allItems
        return copy(items = filterPdfsByQuery(candidates, searchQuery))
    }
}
