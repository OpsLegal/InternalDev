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

/** A cell not done on its day: it stays there, red, until the user ticks it done or sends it on. */
val MissedRed = Color(0xFFF6C4BE)
val MissedInk = Color(0xFF7A1E14)

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
fun TdaTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
