package com.sodre90.cmuxremote.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.model.YoloMode

// Distinct from the sessions list's red/amber attention stripe: this badge
// reflects a standing workspace setting, not a transient agent state, so it
// gets its own color and is shown wherever a workspace's YOLO mode matters
// (the sessions list row, that workspace's terminal pane header, and the
// sessions screen's autopilot summary banner).
val YoloAccent = Color(0xFF8E24AA) // purple

/** The badge label for [mode], or null when it's off (the common case, shown as no badge). */
fun yoloModeLabel(mode: String): String? = when (mode) {
    YoloMode.ALWAYS -> "ALWAYS"
    YoloMode.ALL_TOOLS -> "ALL TOOLS"
    YoloMode.BYPASS -> "BYPASS"
    else -> null
}

@Composable
fun YoloBadge(label: String) {
    Surface(
        color = YoloAccent,
        shape = MaterialTheme.shapes.small,
        // clearAndSetSemantics replaces (rather than adds to) the Text child's
        // own semantics -- otherwise TalkBack would read the bare label
        // ("ALWAYS") with nothing indicating it's this workspace's auto-reply
        // mode, which is the whole point of the badge.
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Auto-reply mode: $label" },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
