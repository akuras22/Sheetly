package com.akuras.sheetly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = OneBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8FE),
    onPrimaryContainer = Color(0xFF00316B),
    secondary = OneBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE9FB),
    onSecondaryContainer = Color(0xFF0B3B75),
    tertiary = Color(0xFF34A853),
    onTertiary = Color.White,
    background = LightCanvas,
    onBackground = LightOnSurface,
    surface = LightCanvas,
    onSurface = LightOnSurface,
    surfaceVariant = LightGroupHigh,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFE3E3E8),
    error = ErrorRedLight,
    onError = Color.White,
    errorContainer = Color(0xFFFDE4E1),
    onErrorContainer = Color(0xFF7A1712),
    inverseSurface = Color(0xFF2C2C2E),
    inverseOnSurface = Color(0xFFF2F2F5),
    inversePrimary = OneBlueDark,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightGroup,
    surfaceContainer = LightGroup,
    surfaceContainerHigh = LightGroup,
    surfaceContainerHighest = LightGroupHigh,
)

private val DarkColors = darkColorScheme(
    primary = OneBlueDark,
    onPrimary = Color(0xFF00244F),
    primaryContainer = Color(0xFF12375F),
    onPrimaryContainer = Color(0xFFD6E8FE),
    secondary = OneBlueDark,
    onSecondary = Color(0xFF00244F),
    secondaryContainer = Color(0xFF1B3556),
    onSecondaryContainer = Color(0xFFD6E8FE),
    tertiary = Color(0xFF5FD27E),
    onTertiary = Color(0xFF00391A),
    background = DarkCanvas,
    onBackground = DarkOnSurface,
    surface = DarkCanvas,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkGroupHigh,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = Color(0xFF58585E),
    outlineVariant = Color(0xFF333336),
    error = ErrorRedDark,
    onError = Color(0xFF5C0A05),
    errorContainer = Color(0xFF7A1712),
    onErrorContainer = Color(0xFFFDE4E1),
    inverseSurface = Color(0xFFE9E9EE),
    inverseOnSurface = Color(0xFF1C1C1E),
    inversePrimary = OneBlue,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = DarkGroup,
    surfaceContainer = DarkGroup,
    surfaceContainerHigh = DarkGroup,
    surfaceContainerHighest = DarkGroupHigh,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** The theme this app resolved to, which can differ from the system's when the user overrides it. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun SheetlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
