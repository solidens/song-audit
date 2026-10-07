package com.songaudit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Deliberately a single (light) scheme. Neo-brutalism is a high-contrast
 * ink-on-paper idiom; a dimmed variant of it reads as a bug, not a mode.
 */
private val GridColors = lightColorScheme(
    primary = Grid.Ink,
    onPrimary = Grid.Paper,
    secondary = Grid.Blue,
    onSecondary = Grid.Paper,
    tertiary = Grid.Yellow,
    onTertiary = Grid.Ink,
    background = Grid.Paper,
    onBackground = Grid.Ink,
    surface = Grid.Paper,
    onSurface = Grid.Ink,
    surfaceVariant = Grid.PaperDeep,
    onSurfaceVariant = Grid.InkSoft,
    error = Grid.Red,
    onError = Grid.Paper,
    outline = Grid.Ink,
)

@Composable
fun SongAuditTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GridColors,
        typography = GridTypography,
        shapes = GridShapes,
        content = content,
    )
}
