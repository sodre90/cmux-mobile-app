package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color

/** Colors for the terminal canvas, independent of the app's Material theme. */
data class TerminalColors(
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val selection: Color,
    val faintAlpha: Float = 0.6f,
)

/** Catppuccin-Mocha-ish dark canvas; tweak freely. */
val DefaultTerminalColors = TerminalColors(
    background = Color(0xFF1E1E2E),
    foreground = Color(0xFFCDD6F4),
    cursor = Color(0xFFF5E0DC),
    selection = Color(0x553B4261),
)
