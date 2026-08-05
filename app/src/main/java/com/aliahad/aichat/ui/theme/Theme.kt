package com.aliahad.aichat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Ink100,
    onPrimary = Ink900,
    primaryContainer = Ink800,
    onPrimaryContainer = Ink100,
    secondary = Ink400,
    onSecondary = Ink950,
    secondaryContainer = Ink800,
    onSecondaryContainer = Ink100,
    tertiary = Ink400,
    onTertiary = Ink950,
    tertiaryContainer = Ink700,
    onTertiaryContainer = Ink100,
    error = RoseDark,
    onError = Ink950,
    errorContainer = RoseContainerDark,
    onErrorContainer = RoseDark,
    background = Ink950,
    onBackground = Ink100,
    surface = Ink950,
    onSurface = Ink100,
    surfaceVariant = Ink900,
    onSurfaceVariant = Ink400,
    surfaceContainerLowest = Ink950,
    surfaceContainerLow = Ink900,
    surfaceContainer = Ink900,
    surfaceContainerHigh = Ink800,
    surfaceContainerHighest = Ink700,
    outline = Ink700,
    outlineVariant = Ink800,
    inverseSurface = Ink100,
    inverseOnSurface = Ink900,
    inversePrimary = Ink900,
)

private val LightColorScheme = lightColorScheme(
    primary = Ink900,
    onPrimary = Ink50,
    primaryContainer = Ink100,
    onPrimaryContainer = Ink900,
    secondary = Ink700,
    onSecondary = White,
    secondaryContainer = Ink100,
    onSecondaryContainer = Ink900,
    tertiary = Ink700,
    onTertiary = White,
    tertiaryContainer = Ink100,
    onTertiaryContainer = Ink900,
    error = RoseLight,
    onError = White,
    errorContainer = RoseContainerLight,
    onErrorContainer = RoseLight,
    background = Ink50,
    onBackground = Ink900,
    surface = White,
    onSurface = Ink900,
    surfaceVariant = Ink100,
    onSurfaceVariant = Ink500,
    surfaceContainerLowest = White,
    surfaceContainerLow = Ink50,
    surfaceContainer = Ink100,
    surfaceContainerHigh = Ink100,
    surfaceContainerHighest = Ink200,
    outline = Ink200,
    outlineVariant = Ink200,
    inverseSurface = Ink900,
    inverseOnSurface = Ink50,
    inversePrimary = Ink100,
)

@Composable
fun AichatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // The explicit neutral system is intentional: dynamic wallpaper color would make
    // private-work surfaces visually inconsistent from one device to the next.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AichatShapes,
        content = content,
    )
}
