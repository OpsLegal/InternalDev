package com.opslegal.tda.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The highlighter yellow of a completed cell. Same in light and dark mode. */
val DoneYellow = Color(0xFFFFE14D)
val DoneInk = Color(0xFF1F2933)

private val Light = lightColorScheme(
    primary = Color(0xFF3D4A57),
    secondary = Color(0xFFB08900),
    background = Color(0xFFFAFAF7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F2F4),
    outline = Color(0xFFD0D4D9),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC6D0DA),
    secondary = Color(0xFFFFE14D),
    background = Color(0xFF15181B),
    surface = Color(0xFF1C2024),
    surfaceVariant = Color(0xFF262B30),
    outline = Color(0xFF3A4148),
)

@Composable
fun TdaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
