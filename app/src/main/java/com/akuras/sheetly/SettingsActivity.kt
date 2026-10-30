package com.akuras.sheetly

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.akuras.sheetly.ui.CollapsingTopBar
import com.akuras.sheetly.ui.LargeHeader
import com.akuras.sheetly.ui.OneUiChoicePill
import com.akuras.sheetly.ui.OneUiSwitch
import com.akuras.sheetly.ui.SettingsGroup
import com.akuras.sheetly.ui.largeHeaderHeight
import com.akuras.sheetly.ui.theme.SheetlyTheme
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
            SheetlyTheme(darkTheme = isDark) {
                SettingsScreen(
                    initialPattern = loadFileNamePattern(this),
                    onPatternChanged = { saveFileNamePattern(this, it) },
                    initialScanMode = loadScanMode(this),
                    onScanModeChanged = { saveScanMode(this, it) },
                    initialEngine = loadScannerEngine(this),
                    onEngineChanged = { saveScannerEngine(this, it) },
                    initialOcr = loadScanOcr(this),
                    onOcrChanged = { saveScanOcr(this, it) },
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

private fun ScannerEngine.labelRes(): Int = when (this) {
    ScannerEngine.BUILT_IN -> R.string.scanner_engine_built_in
    ScannerEngine.GOOGLE -> R.string.scanner_engine_google
}

fun ScanMode.labelRes(): Int = when (this) {
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
    initialEngine: ScannerEngine,
    onEngineChanged: (ScannerEngine) -> Unit,
    initialOcr: Boolean,
    onOcrChanged: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    var textFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialPattern, TextRange(initialPattern.length)))
    }
    var scanMode by rememberSaveable { mutableStateOf(initialScanMode) }
    var engine by rememberSaveable { mutableStateOf(initialEngine) }
    var ocrEnabled by rememberSaveable { mutableStateOf(initialOcr) }
    val insertToken: (String) -> Unit = { token ->
        textFieldValue = textFieldValue.withInserted(token)
        onPatternChanged(textFieldValue.text)
    }

    val scrollState = rememberScrollState()
    val headerPx = with(LocalDensity.current) { largeHeaderHeight().toPx() }
    val collapsed by remember { derivedStateOf { scrollState.value > headerPx * 0.55f } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LargeHeader(stringResource(R.string.settings), subtitle = null)

            SettingsGroup {
                Text(stringResource(R.string.scanner_engine), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(10.dp))
                OneUiChoicePill(
                    options = ScannerEngine.entries,
                    selected = engine,
                    label = { stringResource(it.labelRes()) },
                    onSelected = {
                        engine = it
                        onEngineChanged(it)
                    }
                )
            }

            SettingsGroup {
                Text(stringResource(R.string.scan_mode), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(10.dp))
                OneUiChoicePill(
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
            }

            SettingsGroup {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.scan_ocr), style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.scan_ocr_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    OneUiSwitch(
                        checked = ocrEnabled,
                        onCheckedChange = {
                            ocrEnabled = it
                            onOcrChanged(it)
                        }
                    )
                }
            }

            SettingsGroup {
                Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(10.dp))
                OneUiChoicePill(
                    options = ThemeMode.entries,
                    selected = themeMode,
                    label = { stringResource(it.labelRes()) },
                    onSelected = onThemeModeChanged
                )
            }

            SettingsGroup {
                Text(stringResource(R.string.file_name_template), style = MaterialTheme.typography.titleSmall)
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
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.date_tokens), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                TokenChips(dateTokens, insertToken)
                Spacer(modifier = Modifier.height(14.dp))
                Text(stringResource(R.string.time_tokens), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                TokenChips(timeTokens, insertToken)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        textFieldValue = TextFieldValue(DEFAULT_PATTERN, TextRange(DEFAULT_PATTERN.length))
                        onPatternChanged(DEFAULT_PATTERN)
                    }) {
                        Text(stringResource(R.string.reset_to_default))
                    }
                }
            }
        }

        CollapsingTopBar(
            title = stringResource(R.string.settings),
            showTitle = collapsed,
            navigation = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        )
      }
    }
}

const val PREFS_NAME = "sheetly_prefs"
const val KEY_FILE_PATTERN = "file_name_pattern"
const val KEY_THEME_MODE = "theme_mode"
const val KEY_SCAN_MODE = "scan_mode"
const val KEY_SCANNER_ENGINE = "scanner_engine"
const val KEY_SCAN_OCR = "scan_ocr"
const val DEFAULT_PATTERN = "scan_{yyyy}{MM}{dd}_{HH}{mm}{ss}"

enum class ScannerEngine {
    BUILT_IN,
    GOOGLE,
}

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

fun loadScannerEngine(context: Context): ScannerEngine {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SCANNER_ENGINE, ScannerEngine.BUILT_IN.name)
    return ScannerEngine.entries.firstOrNull { it.name == raw } ?: ScannerEngine.BUILT_IN
}

fun saveScannerEngine(context: Context, engine: ScannerEngine) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SCANNER_ENGINE, engine.name)
        .apply()
}

fun loadScanOcr(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SCAN_OCR, true)
}

fun saveScanOcr(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_SCAN_OCR, enabled)
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
