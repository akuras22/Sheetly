package com.akuras.sheetly.scan

import android.app.Application
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.akuras.sheetly.PdfStorage
import com.akuras.sheetly.R
import com.akuras.sheetly.ScanMode
import com.akuras.sheetly.loadFileNamePattern
import com.akuras.sheetly.loadScanMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ScannerViewModel"

internal class ScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val sessionDirectory = File(appContext.cacheDir, "scan_${UUID.randomUUID()}").apply { mkdirs() }
    private val defaultMode = loadScanMode(appContext)

    val pages = mutableStateListOf<ScanPage>()
    var isBusy by mutableStateOf(false)
        private set

    fun newCaptureFile(): File = File(sessionDirectory, "${UUID.randomUUID()}.jpg")

    /** Adds a finished photo. [liveQuad] from the preview is the fallback if the photo shows nothing. */
    fun addPhoto(file: File, liveQuad: DocumentQuad?) {
        viewModelScope.launch {
            val quad = withContext(Dispatchers.Default) {
                runCatching { ScanImaging.detect(file) }
                    .onFailure { Log.w(TAG, "Detection on photo failed", it) }
                    .getOrNull()
            } ?: liveQuad ?: defaultQuad()
            pages += ScanPage(file = file, quad = quad, mode = defaultMode)
        }
    }

    fun importImages(uris: List<Uri>) {
        viewModelScope.launch {
            isBusy = true
            uris.forEach { uri ->
                val page = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = ScanImaging.importImage(appContext, uri, sessionDirectory)
                        ScanPage(file = file, quad = ScanImaging.detect(file) ?: defaultQuad(), mode = defaultMode)
                    }.onFailure { Log.w(TAG, "Failed to import $uri", it) }.getOrNull()
                }
                if (page != null) pages += page
            }
            isBusy = false
        }
    }

    fun updateQuad(pageId: String, quad: DocumentQuad) = updatePage(pageId) { it.copy(quad = quad) }

    fun setMode(pageId: String, mode: ScanMode) = updatePage(pageId) { it.copy(mode = mode) }

    fun rotate(pageId: String) = updatePage(pageId) { it.copy(quarterTurns = it.quarterTurns + 1) }

    fun delete(pageId: String) {
        val page = pages.firstOrNull { it.id == pageId } ?: return
        pages.remove(page)
        page.file.delete()
    }

    fun save(onSaved: () -> Unit) {
        if (pages.isEmpty() || isBusy) return
        val snapshot = pages.toList()
        viewModelScope.launch {
            isBusy = true
            val saved = withContext(Dispatchers.Default) {
                PdfStorage.saveBitmapPagesToDownloads(
                    appContext,
                    snapshot.size,
                    loadFileNamePattern(appContext)
                ) { index -> ScanImaging.render(snapshot[index]) }
            }
            isBusy = false
            Toast.makeText(
                appContext,
                if (saved != null) R.string.saved_to_downloads else R.string.unable_to_save_pdf,
                Toast.LENGTH_LONG
            ).show()
            if (saved != null) onSaved()
        }
    }

    private fun updatePage(pageId: String, change: (ScanPage) -> ScanPage) {
        val index = pages.indexOfFirst { it.id == pageId }
        if (index >= 0) pages[index] = change(pages[index])
    }

    override fun onCleared() {
        sessionDirectory.deleteRecursively()
    }
}
