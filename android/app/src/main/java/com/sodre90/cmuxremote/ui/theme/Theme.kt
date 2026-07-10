package com.sodre90.cmuxremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme()
private val LightColors = lightColorScheme()

/**
 * Theme-level semantic colors that don't map to a Material color-scheme role
 * (e.g. the sessions list's attention stripe). A plain object rather than a
 * per-theme CompositionLocal: both colors are solid accent bars painted
 * directly on a card, not text needing contrast tuning against a variable
 * background, so one saturated value already reads fine in both light and
 * dark mode -- add a CompositionLocal if a future semantic color actually
 * needs distinct light/dark variants.
 */
object AppColors {
    val PermissionAccent = Color(0xFFE53935) // red -- agent blocked on a prompt
    val WaitingAccent = Color(0xFFFFB300) // amber -- agent idle, waiting for input
}

@Composable
fun CmuxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
