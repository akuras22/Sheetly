package com.akuras.pdfscanner

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_ASPECT_RATIO = 0.707f
private const val PAGE_ROW_PREVIEW_WIDTH_PX = 160
private const val VIEWER_PAGE_WIDTH_PX = 1400
private const val VIEWER_MAX_ZOOM = 5f
private const val VIEWER_DOUBLE_TAP_ZOOM = 2.5f
private const val PREVIEW_CACHE_ENTRIES = 60

@OptIn(ExperimentalCoroutinesApi::class)
private val previewDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
private val previewCache = LruCache<String, PdfPreview>(PREVIEW_CACHE_ENTRIES)

/** Previews survive scrolling and recomposition, [seed] invalidates them after a PDF changes. */
@Composable
private fun rememberPdfPreview(uri: Uri, seed: Int): PdfPreview? {
    val context = LocalContext.current.applicationContext
    val key = remember(uri, seed) { "$uri#$seed" }
    val preview by produceState<PdfPreview?>(initialValue = previewCache.get(key), key) {
        if (value == null) {
            value = withContext(previewDispatcher) {
                PdfStorage.loadPreview(context, uri).also { previewCache.put(key, it) }
            }
        }
    }
    return preview
}

@Composable
fun ScannerScreen(
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    historyViewModel: HistoryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState = historyViewModel.uiState
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showDeleteSelected by remember { mutableStateOf(false) }
    var showMergePreview by remember { mutableStateOf(false) }
    val selectedItems = uiState.selectedItems
    val deletableCount = selectedItems.count { !it.isExternal }

    val closeSearch = {
        showSearch = false
        historyViewModel.setSearchQuery("")
    }

    val externalPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val added = uris.mapIndexed { index, uri ->
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                android.util.Log.w("ScannerScreen", "Failed to persist read permission for $uri", e)
            }
            SavedPdf(
                name = queryPdfDisplayName(context, uri)
                    ?: context.getString(R.string.external_pdf_fallback_name, index + 1),
                uri = uri,
                isExternal = true,
            )
        }
        historyViewModel.addExternal(added)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { historyViewModel.refresh() }
    LaunchedEffect(historyViewModel) {
        historyViewModel.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(enabled = uiState.selectionMode) { historyViewModel.exitSelectionMode() }
    BackHandler(enabled = showSearch && !uiState.selectionMode, onBack = closeSearch)

    if (showDeleteSelected) {
        AlertDialog(
            onDismissRequest = { showDeleteSelected = false },
            title = { Text(pluralStringResource(R.plurals.delete_selected_title, deletableCount, deletableCount)) },
            text = { Text(stringResource(R.string.delete_selected_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSelected = false
                    historyViewModel.deleteSelected()
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelected = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showMergePreview) {
        MergePreviewDialog(
            orderedItems = selectedItems,
            thumbnailSeed = uiState.thumbnailSeed,
            onDismiss = { showMergePreview = false },
            onConfirm = { confirmedItems ->
                showMergePreview = false
                historyViewModel.merge(confirmedItems)
            }
        )
    }

    Scaffold(
        topBar = {
            if (uiState.selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedItems.size)) },
                    navigationIcon = {
                        IconButton(onClick = historyViewModel::exitSelectionMode) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(onClick = historyViewModel::selectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.select_all))
                        }
                    }
                )
            } else {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (showSearch) closeSearch() else showSearch = true }) {
                            Icon(
                                if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(R.string.search_documents)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (uiState.selectionMode) {
                SelectionActionsBar(
                    canMerge = selectedItems.size >= 2 && !uiState.isMerging,
                    canShare = selectedItems.isNotEmpty(),
                    canDelete = deletableCount > 0,
                    onMerge = { showMergePreview = true },
                    onAddExternal = { externalPicker.launch(arrayOf("application/pdf")) },
                    onShare = { PdfStorage.sharePdfs(context, selectedItems) },
                    onDelete = { showDeleteSelected = true }
                )
            }
        },
        floatingActionButton = {
            if (!uiState.selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onScanClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    Icon(
                        Icons.Default.DocumentScanner,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.scan_document), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            HistoryPanel(
                uiState = uiState,
                showSearch = showSearch && !uiState.selectionMode,
                onSetSearch = historyViewModel::setSearchQuery,
                onSetSort = historyViewModel::setSortOrder,
                onStartSelection = historyViewModel::enterSelectionMode,
                onToggleSelection = historyViewModel::toggleSelection,
                onDelete = historyViewModel::delete,
                onRename = historyViewModel::rename,
                onReordered = historyViewModel::onReordered,
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun SelectionActionsBar(
    canMerge: Boolean,
    canShare: Boolean,
    canDelete: Boolean,
    onMerge: () -> Unit,
    onAddExternal: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 4.dp)
        ) {
            SelectionAction(Icons.Default.Merge, stringResource(R.string.merge), canMerge, onMerge)
            SelectionAction(Icons.Default.UploadFile, stringResource(R.string.add_external_pdfs), true, onAddExternal)
            SelectionAction(Icons.Default.Share, stringResource(R.string.share), canShare, onShare)
            SelectionAction(Icons.Default.Delete, stringResource(R.string.delete), canDelete, onDelete)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HistoryPanel(
    uiState: HistoryUiState,
    showSearch: Boolean,
    onSetSearch: (String) -> Unit,
    onSetSort: (PdfSortOrder) -> Unit,
    onStartSelection: (Uri?) -> Unit,
    onToggleSelection: (Uri) -> Unit,
    onDelete: (Uri) -> Unit,
    onRename: (Uri, String) -> Unit,
    onReordered: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renameTarget by remember { mutableStateOf<SavedPdf?>(null) }
    var viewerTarget by remember { mutableStateOf<SavedPdf?>(null) }
    val selectedOrder = remember(uiState.selectedUris) { uiState.selectedUris.withIndex().associate { (index, key) -> key to index + 1 } }

    renameTarget?.let { target ->
        RenamePdfDialog(
            item = target,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                onRename(target.uri, newName)
                renameTarget = null
            }
        )
    }
    viewerTarget?.let { target ->
        PdfViewerDialog(item = target, onDismiss = { viewerTarget = null })
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.saved_pdfs),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (uiState.allItems.isNotEmpty()) {
                    Text(
                        text = pluralStringResource(R.plurals.documents_count, uiState.items.size, uiState.items.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!uiState.selectionMode && uiState.allItems.isNotEmpty()) {
                IconButton(onClick = { onStartSelection(null) }) {
                    Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.select))
                }
                SortDropdown(currentSort = uiState.sortOrder, onSortSelected = onSetSort)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (showSearch) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSetSearch,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.search_documents)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (uiState.isSavingScan || uiState.isMerging) {
            Text(
                stringResource(if (uiState.isMerging) R.string.merging else R.string.processing_scan),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
        }

        when {
            uiState.items.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(uiState.items, key = { it.uri.toString() }, contentType = { "pdf" }) { item ->
                    val selectionIndex = selectedOrder[item.uri.toString()]
                    PdfHistoryCard(
                        item = item,
                        selectionMode = uiState.selectionMode,
                        selectionIndex = selectionIndex,
                        thumbnailSeed = uiState.thumbnailSeed,
                        onClick = {
                            if (uiState.selectionMode) onToggleSelection(item.uri) else viewerTarget = item
                        },
                        onLongClick = {
                            if (uiState.selectionMode) onToggleSelection(item.uri) else onStartSelection(item.uri)
                        },
                        onDeleted = onDelete,
                        onRename = { renameTarget = item },
                        onReordered = onReordered
                    )
                }
            }
            uiState.allItems.isNotEmpty() -> EmptyState(
                icon = Icons.Default.SearchOff,
                title = stringResource(R.string.no_search_results),
                text = stringResource(R.string.no_search_results_hint, uiState.searchQuery.trim())
            )
            else -> EmptyState(
                icon = Icons.Default.Description,
                title = stringResource(R.string.no_scanned_documents),
                text = stringResource(R.string.tap_scan_to_start)
            )
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SortDropdown(
    currentSort: PdfSortOrder,
    onSortSelected: (PdfSortOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        PdfSortOrder.DATE_DESC to R.string.sort_date_desc,
        PdfSortOrder.DATE_ASC to R.string.sort_date_asc,
        PdfSortOrder.NAME_ASC to R.string.sort_name_asc,
        PdfSortOrder.NAME_DESC to R.string.sort_name_desc,
        PdfSortOrder.SIZE_DESC to R.string.sort_size_desc,
        PdfSortOrder.SIZE_ASC to R.string.sort_size_asc,
    )
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort_by))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (order, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(label),
                            fontWeight = if (order == currentSort) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onSortSelected(order)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PdfHistoryCard(
    item: SavedPdf,
    selectionMode: Boolean,
    selectionIndex: Int?,
    thumbnailSeed: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleted: (Uri) -> Unit,
    onRename: () -> Unit,
    onReordered: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val preview = rememberPdfPreview(item.uri, thumbnailSeed)
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showReorderDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) showDeleteDialog = true
            // Always snap back, the dialog decides whether the document goes away.
            false
        }
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_document_title)) },
            text = { Text(stringResource(R.string.delete_document_text, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleted(item.uri)
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showReorderDialog) {
        ReorderPagesDialog(
            item = item,
            onDismiss = { showReorderDialog = false },
            onSaved = {
                showReorderDialog = false
                onReordered(true)
            },
            onFailed = { onReordered(false) }
        )
    }

    if (showExportDialog) {
        ScannerExportDialog(
            item = item,
            onDismiss = { showExportDialog = false }
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !selectionMode,
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(16.dp))
                        .padding(end = 24.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            colors = CardDefaults.cardColors(
                containerColor = if (selectionIndex != null) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .aspectRatio(PAGE_ASPECT_RATIO)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    val thumbnail = preview?.thumbnail
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = stringResource(R.string.pdf_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                    if (selectionIndex != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(20.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$selectionIndex",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (item.isExternal) stringResource(R.string.external_pdf_label) else pdfMeta(item, preview),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (selectionMode) {
                    Checkbox(checked = selectionIndex != null, onCheckedChange = { onClick() })
                } else {
                    IconButton(onClick = { PdfStorage.sharePdf(context, item.uri, item.name) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_actions))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            PdfMenuItem(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open)) {
                                showMenu = false
                                PdfStorage.openPdf(context, item.uri)
                            }
                            PdfMenuItem(Icons.Default.FileCopy, stringResource(R.string.pages)) {
                                showMenu = false
                                showReorderDialog = true
                            }
                            PdfMenuItem(Icons.Default.Edit, stringResource(R.string.rename)) {
                                showMenu = false
                                onRename()
                            }
                            PdfMenuItem(Icons.Default.FileDownload, stringResource(R.string.export_as)) {
                                showMenu = false
                                showExportDialog = true
                            }
                            HorizontalDivider()
                            PdfMenuItem(Icons.Default.Delete, stringResource(R.string.delete)) {
                                showMenu = false
                                showDeleteDialog = true
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
private fun pdfMeta(item: SavedPdf, preview: PdfPreview?): String {
    val date = remember(item.dateAdded) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.dateAdded * 1000))
    }
    val pageCount = preview?.pageCount
    val pages = if (pageCount != null) pluralStringResource(R.plurals.pages_count, pageCount, pageCount) else null
    return listOfNotNull(formatSize(item.sizeBytes), pages, date).joinToString(" · ")
}

@Composable
private fun ScannerExportDialog(
    item: SavedPdf,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }
    val baseName = remember(item.name) { item.name.removeSuffix(".pdf") }

    fun export(successMessage: Int, block: () -> Boolean) {
        scope.launch {
            isExporting = true
            val ok = withContext(Dispatchers.IO) { block() }
            isExporting = false
            Toast.makeText(
                context,
                if (ok) successMessage else R.string.export_failed,
                Toast.LENGTH_LONG
            ).show()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        title = { Text(stringResource(R.string.export_as)) },
        text = {
            if (isExporting) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.exporting))
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScannerExportOption(label = stringResource(R.string.export_png)) {
                        export(R.string.export_png_success) {
                            PdfStorage.exportPdfAsPngs(context, item.uri, baseName) > 0
                        }
                    }
                    ScannerExportOption(label = stringResource(R.string.export_html)) {
                        export(R.string.export_html_success) {
                            PdfStorage.exportPdfAsHtml(context, item.uri, baseName)
                        }
                    }
                    ScannerExportOption(label = stringResource(R.string.export_docx)) {
                        export(R.string.export_docx_success) {
                            PdfStorage.exportPdfAsDocx(context, item.uri, baseName)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isExporting) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ScannerExportOption(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PageRowPreview(uri: Uri, page: Int) {
    val context = LocalContext.current.applicationContext
    val preview by produceState<Bitmap?>(initialValue = null, uri, page) {
        value = withContext(previewDispatcher) {
            PdfStorage.renderPdfPage(context, uri, page, PAGE_ROW_PREVIEW_WIDTH_PX)
        }
    }
    Box(
        modifier = Modifier
            .width(44.dp)
            .aspectRatio(PAGE_ASPECT_RATIO)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = preview
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun ReorderPagesDialog(
    item: SavedPdf,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var originalOrder by remember(item.uri) { mutableStateOf<List<Int>?>(null) }
    var pageOrder by remember(item.uri) { mutableStateOf<List<Int>?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(item.uri) {
        val order = withContext(Dispatchers.IO) { PdfStorage.getPdfPageOrder(context, item.uri) }
        originalOrder = order
        pageOrder = order
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.move_pages)) },
        text = {
            when (val order = pageOrder) {
                null -> Text(stringResource(R.string.loading_pages))
                else -> {
                    if (order.size <= 1) {
                        Text(stringResource(R.string.single_page_pdf))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(order, key = { _, page -> page }) { index, page ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PageRowPreview(item.uri, page)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.page_number, page + 1),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    IconButton(
                                        enabled = index > 0 && !isSaving,
                                        onClick = { pageOrder = order.swapped(index, index - 1) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.up), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        enabled = index < order.lastIndex && !isSaving,
                                        onClick = { pageOrder = order.swapped(index, index + 1) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.down), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        enabled = !isSaving,
                                        onClick = { pageOrder = order - page },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.delete_page),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val order = pageOrder
            TextButton(
                enabled = order != null && order != originalOrder && !isSaving,
                onClick = {
                    if (order == null) return@TextButton
                    scope.launch {
                        isSaving = true
                        val saved = withContext(Dispatchers.IO) {
                            PdfStorage.reorderPdfPages(context, item.uri, order)
                        }
                        isSaving = false
                        if (saved) onSaved() else onFailed()
                    }
                }
            ) {
                Text(stringResource(if (isSaving) R.string.saving else R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun RenamePdfDialog(
    item: SavedPdf,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var value by remember(item.uri) { mutableStateOf(item.name.removeSuffix(".pdf")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.file_name)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PdfViewerDialog(
    item: SavedPdf,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val pageCount by produceState<Int?>(initialValue = null, item.uri) {
        value = withContext(previewDispatcher) { PdfStorage.countPdfPages(context, item.uri) ?: 0 }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        IconButton(onClick = { PdfStorage.sharePdf(context, item.uri, item.name) }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                        }
                        IconButton(onClick = { PdfStorage.openPdf(context, item.uri) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.open))
                        }
                    }
                )
                val count = pageCount
                when {
                    count == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    count == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.could_not_load_pdf))
                    }
                    else -> {
                        val pagerState = rememberPagerState { count }
                        var isZoomed by remember { mutableStateOf(false) }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            userScrollEnabled = !isZoomed,
                            pageSpacing = 8.dp,
                            key = { it }
                        ) { page ->
                            ZoomablePdfPage(
                                uri = item.uri,
                                page = page,
                                onZoomChanged = { isZoomed = it }
                            )
                        }
                        Text(
                            text = stringResource(R.string.page_x_of_y, pagerState.currentPage + 1, count),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ZoomablePdfPage(
    uri: Uri,
    page: Int,
    onZoomChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(initialValue = null, uri, page) {
        value = withContext(previewDispatcher) {
            PdfStorage.renderPdfPage(context, uri, page, VIEWER_PAGE_WIDTH_PX)
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(value: Offset): Offset {
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, VIEWER_MAX_ZOOM)
        offset = clampOffset(offset + panChange)
    }
    val isZoomed = scale > 1f
    LaunchedEffect(isZoomed) { onZoomChanged(isZoomed) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else VIEWER_DOUBLE_TAP_ZOOM
                    offset = Offset.Zero
                })
            }
            .transformable(transformState, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center
    ) {
        val pageBitmap = bitmap
        if (pageBitmap != null) {
            Image(
                bitmap = pageBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.page_number, page + 1),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun MergePreviewDialog(
    orderedItems: List<SavedPdf>,
    thumbnailSeed: Int,
    onDismiss: () -> Unit,
    onConfirm: (List<SavedPdf>) -> Unit,
) {
    var items by remember { mutableStateOf(orderedItems) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.merge_order_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.merge_order_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(items, key = { _, pdf -> pdf.uri.toString() }) { index, pdf ->
                        MergePreviewRow(
                            pdf = pdf,
                            position = index + 1,
                            thumbnailSeed = thumbnailSeed,
                            canMoveUp = index > 0,
                            canMoveDown = index < items.lastIndex,
                            onMoveUp = { items = items.swapped(index, index - 1) },
                            onMoveDown = { items = items.swapped(index, index + 1) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(items) }) {
                Text(stringResource(R.string.merge))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun MergePreviewRow(
    pdf: SavedPdf,
    position: Int,
    thumbnailSeed: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val preview = rememberPdfPreview(pdf.uri, thumbnailSeed)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$position",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(PAGE_ASPECT_RATIO)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val thumbnail = preview?.thumbnail
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = pdf.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.up), modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.down), modifier = Modifier.size(18.dp))
        }
    }
}

private fun <T> List<T>.swapped(first: Int, second: Int): List<T> {
    return toMutableList().apply {
        val moved = this[first]
        this[first] = this[second]
        this[second] = moved
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
