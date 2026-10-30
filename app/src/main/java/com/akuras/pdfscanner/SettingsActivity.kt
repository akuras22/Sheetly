package com.akuras.pdfscanner

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.akuras.pdfscanner.ui.theme.PDFScannerTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var themeMode by rememberSaveable { mutableStateOf(loadThemeMode(this)) }
            val isDark = resolveDarkTheme(this, themeMode)
            LaunchedEffect(isDark) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
            PDFScannerTheme(darkTheme = isDark) {
                SettingsScreen(
                    initialPattern = loadFileNamePattern(this),
                    onPatternChanged = { saveFileNamePattern(this, it) },
                    initialScanMode = loadScanMode(this),
                    onScanModeChanged = { saveScanMode(this, it) },
                    themeMode = themeMode,
                    onThemeModeChanged = { mode ->
                        saveThemeMode(this, mode)
                        themeMode = mode
                        setResult(RESULT_THEME_CHANGED)
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

private data class Token(@StringRes val label: Int, val value: String)

private val dateTokens = listOf(
    Token(R.string.token_year_long, "{yyyy}"),
    Token(R.string.token_year_short, "{yy}"),
    Token(R.string.token_month_number, "{MM}"),
    Token(R.string.token_month_short, "{MMM}"),
    Token(R.string.token_month_long, "{MMMM}"),
    Token(R.string.token_day, "{dd}"),
)

private val timeTokens = listOf(
    Token(R.string.token_hour, "{HH}"),
    Token(R.string.token_minute, "{mm}"),
    Token(R.string.token_second, "{ss}"),
)

private fun ScanMode.labelRes(): Int = when (this) {
    ScanMode.ORIGINAL -> R.string.scan_mode_original
    ScanMode.DOCUMENT -> R.string.scan_mode_document
    ScanMode.BLACK_WHITE -> R.string.scan_mode_black_white
}

private fun ScanMode.descriptionRes(): Int = when (this) {
    ScanMode.ORIGINAL -> R.string.scan_mode_original_description
    ScanMode.DOCUMENT -> R.string.scan_mode_document_description
    ScanMode.BLACK_WHITE -> R.string.scan_mode_black_white_description
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun TextFieldValue.withInserted(token: String): TextFieldValue {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    return TextFieldValue(text.replaceRange(start, end, token), TextRange(start + token.length))
}

@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label(option), maxLines = 1) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenChips(tokens: List<Token>, onInsert: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tokens.forEach { token ->
            AssistChip(
                onClick = { onInsert(token.value) },
                label = { Text(stringResource(token.label)) }
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    initialPattern: String,
    onPatternChanged: (String) -> Unit,
    initialScanMode: ScanMode,
    onScanModeChanged: (ScanMode) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialPattern, TextRange(initialPattern.length)))
    }
    var scanMode by rememberSaveable { mutableStateOf(initialScanMode) }
    val insertToken: (String) -> Unit = { token ->
        textFieldValue = textFieldValue.withInserted(token)
        onPatternChanged(textFieldValue.text)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.scan_mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                ChoiceRow(
                    options = ScanMode.entries,
                    selected = scanMode,
                    label = { stringResource(it.labelRes()) },
                    onSelected = {
                        scanMode = it
                        onScanModeChanged(it)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(scanMode.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                ChoiceRow(
                    options = ThemeMode.entries,
                    selected = themeMode,
                    label = { stringResource(it.labelRes()) },
                    onSelected = onThemeModeChanged
                )

                Spacer(modifier = Modifier.height(24.dp))

                // File name template
                Text(stringResource(R.string.file_name_template), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.file_name_template_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = {
                        textFieldValue = it
                        onPatternChanged(it.text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.file_name_pattern)) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                val preview = sanitizeFileName(resolveFileName(textFieldValue.text))
                Text(
                    text = stringResource(R.string.preview_file_name, preview),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Date tokens group
                Text(stringResource(R.string.date_tokens), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                TokenChips(dateTokens, insertToken)

                Spacer(modifier = Modifier.height(16.dp))

                // Time tokens group
                Text(stringResource(R.string.time_tokens), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                TokenChips(timeTokens, insertToken)

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        textFieldValue = TextFieldValue(DEFAULT_PATTERN, TextRange(DEFAULT_PATTERN.length))
                        onPatternChanged(DEFAULT_PATTERN)
                    }) {
                        Text(stringResource(R.string.reset_to_default))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

const val PREFS_NAME = "pdfscanner_prefs"
const val KEY_FILE_PATTERN = "file_name_pattern"
const val KEY_THEME_MODE = "theme_mode"
const val KEY_SCAN_MODE = "scan_mode"
const val DEFAULT_PATTERN = "scan_{yyyy}{MM}{dd}_{HH}{mm}{ss}"

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

fun loadFileNamePattern(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_FILE_PATTERN, DEFAULT_PATTERN) ?: DEFAULT_PATTERN
}

fun saveFileNamePattern(context: Context, pattern: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_FILE_PATTERN, pattern)
        .apply()
}

fun loadThemeMode(context: Context): ThemeMode {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
    return ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
}

fun saveThemeMode(context: Context, mode: ThemeMode) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_THEME_MODE, mode.name)
        .apply()
}

fun loadScanMode(context: Context): ScanMode {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SCAN_MODE, ScanMode.DOCUMENT.name)
    return ScanMode.entries.firstOrNull { it.name == raw } ?: ScanMode.DOCUMENT
}

fun saveScanMode(context: Context, mode: ScanMode) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SCAN_MODE, mode.name)
        .apply()
}

fun resolveDarkTheme(context: Context, mode: ThemeMode): Boolean {
    return when (mode) {
        ThemeMode.SYSTEM -> {
            val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

fun resolveFileName(pattern: String): String {
    val now = LocalDateTime.now()
    return pattern
        .replace("{yyyy}", now.format(DateTimeFormatter.ofPattern("yyyy")))
        .replace("{yy}", now.format(DateTimeFormatter.ofPattern("yy")))
        .replace("{MMMM}", now.format(DateTimeFormatter.ofPattern("MMMM")))
        .replace("{MMM}", now.format(DateTimeFormatter.ofPattern("MMM")))
        .replace("{MM}", now.format(DateTimeFormatter.ofPattern("MM")))
        .replace("{dd}", now.format(DateTimeFormatter.ofPattern("dd")))
        .replace("{HH}", now.format(DateTimeFormatter.ofPattern("HH")))
        .replace("{mm}", now.format(DateTimeFormatter.ofPattern("mm")))
        .replace("{ss}", now.format(DateTimeFormatter.ofPattern("ss")))
}

/** Makes [raw] safe to use as a file name, or returns [fallback] if nothing usable remains. */
fun sanitizeFileName(raw: String, fallback: String = "scan"): String {
    val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .trim('.', ' ')
    return cleaned.ifEmpty { fallback }
}

const val RESULT_THEME_CHANGED = 100
