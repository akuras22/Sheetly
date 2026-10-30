package com.akuras.sheetly

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akuras.sheetly.ui.CollapsingTopBar
import com.akuras.sheetly.ui.LargeHeader
import com.akuras.sheetly.ui.OneUiAlertDialog
import com.akuras.sheetly.ui.ToolColors
import com.akuras.sheetly.ui.groupItemShape
import com.akuras.sheetly.ui.rememberHeaderCollapsed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private enum class Tool(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    val icon: ImageVector,
    val colour: Color,
) {
    MERGE(R.string.tool_merge, R.string.tool_merge_hint, Icons.Default.Merge, ToolColors.Blue),
    IMAGES(R.string.tool_images, R.string.tool_images_hint, Icons.Default.Image, ToolColors.Green),
    WORD(R.string.tool_word, R.string.tool_word_hint, Icons.Default.Article, ToolColors.Blue),
    IMPORT(R.string.tool_import, R.string.tool_import_hint, Icons.Default.FileUpload, ToolColors.Teal),
    OCR(R.string.tool_ocr, R.string.tool_ocr_hint, Icons.Default.DocumentScanner, ToolColors.Purple),
    COMPRESS(R.string.tool_compress, R.string.tool_compress_hint, Icons.Default.Compress, ToolColors.Orange),
    PROTECT(R.string.tool_protect, R.string.tool_protect_hint, Icons.Default.Lock, ToolColors.Red),
    SPLIT(R.string.tool_split, R.string.tool_split_hint, Icons.Default.ViewAgenda, ToolColors.Teal),
    NUMBERS(R.string.tool_numbers, R.string.tool_numbers_hint, Icons.Default.FormatListNumbered, ToolColors.Blue),
    WATERMARK(R.string.tool_watermark, R.string.tool_watermark_hint, Icons.Default.WaterDrop, ToolColors.Purple),
    PRINT(R.string.tool_print, R.string.tool_print_hint, Icons.Default.Print, ToolColors.Green),
}

@Composable
internal fun ToolsPanel(
    uiState: HistoryUiState,
    viewModel: HistoryViewModel,
    onStartMerge: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val collapsed by rememberHeaderCollapsed(listState)
    var pendingTool by remember { mutableStateOf<Tool?>(null) }
    var externalTool by remember { mutableStateOf<Tool?>(null) }
    var passwordTarget by remember { mutableStateOf<SavedPdf?>(null) }
    var watermarkTarget by remember { mutableStateOf<SavedPdf?>(null) }
    var recognizedText by remember { mutableStateOf<String?>(null) }
    var isRecognizing by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.runTool(R.string.tool_done) {
                PdfTools.imagesToPdf(context, uris, loadFileNamePattern(context)) != null
            }
        }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPdf(uri)
    }
    val wordPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.runTool(R.string.tool_done) {
                PdfTools.wordToPdf(context, uri, queryPdfDisplayName(context, uri)) != null
            }
        }
    }

    fun runOn(tool: Tool, item: SavedPdf) {
        when (tool) {
            Tool.COMPRESS -> viewModel.runTool(R.string.tool_done) { PdfTools.compress(context, item) != null }
            Tool.SPLIT -> viewModel.runTool(R.string.tool_done) { PdfTools.splitIntoPages(context, item) > 0 }
            Tool.NUMBERS -> viewModel.runTool(R.string.tool_done) { PdfTools.addPageNumbers(context, item) != null }
            Tool.PROTECT -> passwordTarget = item
            Tool.WATERMARK -> watermarkTarget = item
            Tool.PRINT -> PdfTools.print(context, item)
            Tool.OCR -> scope.launch {
                isRecognizing = true
                recognizedText = runCatching {
                    withContext(Dispatchers.IO) { PdfTools.recognizeText(context, item) }
                }.getOrNull()
                isRecognizing = false
                if (recognizedText == null) Toast.makeText(context, R.string.tool_failed, Toast.LENGTH_SHORT).show()
            }
            else -> Unit
        }
    }

    val externalPdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tool = externalTool
        externalTool = null
        if (uri != null && tool != null) {
            val name = queryPdfDisplayName(context, uri) ?: context.getString(R.string.external_pdf_fallback_name, 1)
            runOn(tool, SavedPdf(name = name, uri = uri, isExternal = true))
        }
    }

    fun open(tool: Tool) {
        when (tool) {
            Tool.MERGE -> onStartMerge()
            Tool.IMAGES -> imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            Tool.IMPORT -> documentPicker.launch(arrayOf("application/pdf"))
            Tool.WORD -> wordPicker.launch(arrayOf(DOCX_MIME_TYPE))
            else -> pendingTool = tool
        }
    }

    pendingTool?.let { tool ->
        DocumentPickerSheet(
            title = stringResource(tool.title),
            items = uiState.allItems,
            onDismiss = { pendingTool = null },
            onPick = { item ->
                pendingTool = null
                runOn(tool, item)
            },
            onPickExternal = {
                pendingTool = null
                externalTool = tool
                externalPdfPicker.launch(arrayOf("application/pdf"))
            }
        )
    }
    passwordTarget?.let { item ->
        PasswordDialog(
            onDismiss = { passwordTarget = null },
            onConfirm = { password ->
                passwordTarget = null
                viewModel.runTool(R.string.tool_done) {
                    PdfTools.protectWithPassword(context, item, password) != null
                }
            }
        )
    }
    watermarkTarget?.let { item ->
        WatermarkDialog(
            onDismiss = { watermarkTarget = null },
            onConfirm = { text ->
                watermarkTarget = null
                viewModel.runTool(R.string.tool_done) { PdfTools.addWatermark(context, item, text) != null }
            }
        )
    }
    recognizedText?.let { text ->
        RecognizedTextDialog(text = text, onDismiss = { recognizedText = null })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 124.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "header") {
                LargeHeader(stringResource(R.string.tools_title), stringResource(R.string.tools_subtitle))
            }
            if (uiState.isToolRunning || isRecognizing) {
                item(key = "progress") {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.tool_running),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            val tools = Tool.entries
            tools.forEachIndexed { index, tool ->
                item(key = tool.name) {
                    ToolRow(tool, index, tools.size) { open(tool) }
                }
            }
        }
        CollapsingTopBar(
            title = stringResource(R.string.tools_title),
            showTitle = collapsed,
            modifier = Modifier.align(Alignment.TopCenter),
            actions = {
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
        )
    }
}

@Composable
private fun ToolRow(tool: Tool, index: Int, count: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(groupItemShape(index, count))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = groupItemShape(index, count)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(tool.colour, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(tool.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(stringResource(tool.title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(tool.hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DocumentPickerSheet(
    title: String,
    items: List<SavedPdf>,
    onDismiss: () -> Unit,
    onPick: (SavedPdf) -> Unit,
    onPickExternal: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.tool_pick_document),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onPickExternal)
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.tool_pick_external), color = MaterialTheme.colorScheme.primary)
            }
            if (items.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(items, key = { it.uri.toString() }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onPick(item) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    OneUiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_password_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.tool_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.tool_password_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun WatermarkDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val defaultText = stringResource(R.string.tool_watermark_default)
    var text by remember { mutableStateOf(defaultText) }
    OneUiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_watermark_title)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun RecognizedTextDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    OneUiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tool_ocr_title)) },
        text = {
            Box(modifier = Modifier.heightIn(max = 420.dp)) {
                if (text.isBlank()) {
                    Text(stringResource(R.string.tool_ocr_empty))
                } else {
                    SelectionContainer {
                        Text(text, modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { copyToClipboard(context, text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.copy))
            }
        },
        dismissButton = {
            Row {
                IconButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    },
                    enabled = text.isNotBlank()
                ) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        }
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}
