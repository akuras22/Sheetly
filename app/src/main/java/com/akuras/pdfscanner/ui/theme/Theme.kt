package com.akuras.pdfscanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = TealBlue,
    onPrimary = Cream,
    primaryContainer = Color(0xFFCDE9F0),
    onPrimaryContainer = Color(0xFF00323E),
    secondary = Mint,
    onSecondary = Slate,
    secondaryContainer = Color(0xFFD5F2E5),
    onSecondaryContainer = Color(0xFF0B4F35),
    tertiary = SkyBlue,
    onTertiary = Cream,
    background = Cream,
    onBackground = Slate,
    surface = Cream,
    onSurface = Slate,
    surfaceVariant = Color(0xFFEDE8DD),
    onSurfaceVariant = Color(0xFF4A4A4A),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0DCD3),
    error = ErrorRed,
    onError = Color.White,
    errorContainer = Color(0xFFFCE1DF),
    onErrorContainer = Color(0xFF7A1712),
    inverseSurface = Slate,
    inverseOnSurface = Cream,
    inversePrimary = Color(0xFF80D8EA),
    surfaceContainerLowest = Color(0xFFFFFBF5),
    surfaceContainerLow = Color(0xFFF9F5ED),
    surfaceContainer = Color(0xFFF5F0E8),
    surfaceContainerHigh = Color(0xFFEFEAE2),
    surfaceContainerHighest = Color(0xFFE9E4DB),
)

private val DarkColors = darkColorScheme(
    primary = SkyBlue,
    onPrimary = Color(0xFF003548),
    primaryContainer = Color(0xFF004D68),
    onPrimaryContainer = Color(0xFFB8E9FF),
    secondary = Mint,
    onSecondary = Color(0xFF003825),
    secondaryContainer = Color(0xFF005234),
    onSecondaryContainer = Color(0xFF8FF7C6),
    tertiary = Color(0xFFA9C7FF),
    onTertiary = Color(0xFF1A3175),
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = Color(0xFF6B6B75),
    outlineVariant = Color(0xFF3B3B44),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),
    inversePrimary = Color(0xFF006879),
    surfaceContainerLowest = Color(0xFF0D0D12),
    surfaceContainerLow = Color(0xFF16161B),
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = Color(0xFF1E1E26),
    surfaceContainerHighest = Color(0xFF27272F),
)

@Composable
fun PDFScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
