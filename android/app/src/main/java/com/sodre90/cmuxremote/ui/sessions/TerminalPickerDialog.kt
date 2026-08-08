package com.sodre90.cmuxremote.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.model.TerminalPane
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.ui.theme.CmuxTheme

/**
 * Lets the user pick a pane directly instead of landing on the plain
 * Sessions list, for the two places cmux gives no per-pane id to resolve
 * one automatically:
 * - a notification tap whose workspace has more than one pane and no
 *   uniquely-focused one (see ui.sessions.notificationTarget);
 * - an Inbox "Open terminal" tap whose item's cwd matches more than one
 *   workspace (see pendingItemTarget) -- [workspaces] then holds every
 *   candidate, each pane grouped under its own workspace's title so
 *   same-repo sibling sessions stay distinguishable.
 */
@Composable
fun TerminalPickerDialog(
    workspaces: List<Workspace>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_picker_title)) },
        text = {
            Column {
                workspaces.forEach { ws ->
                    if (workspaces.size > 1) {
                        Text(
                            text = ws.title.ifBlank { ws.cwd },
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    ws.terminals.forEach { pane -> PaneRow(pane, onSelect) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun TerminalPickerDialogSinglePreview() {
    CmuxTheme {
        TerminalPickerDialog(
            workspaces = listOf(
                Workspace(
                    id = "w1",
                    title = "trading app",
                    terminals = listOf(
                        TerminalPane(id = "t-1", title = "main", ready = true, kind = "claude"),
                        TerminalPane(id = "t-2", title = "shell", ready = true, kind = "shell"),
                    ),
                ),
            ),
            onSelect = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, name = "Multiple same-cwd workspaces")
@Composable
private fun TerminalPickerDialogMultiWorkspacePreview() {
    CmuxTheme {
        TerminalPickerDialog(
            workspaces = listOf(
                Workspace(
                    id = "w1",
                    title = "Review PR comments",
                    terminals = listOf(
                        TerminalPane(
                            id = "t-1",
                            title = "Review PR comments",
                            ready = true,
                            kind = "claude",
                        ),
                    ),
                ),
                Workspace(
                    id = "w2",
                    title = "OpenStack security group",
                    terminals = listOf(
                        TerminalPane(
                            id = "t-2",
                            title = "OpenStack security group",
                            ready = true,
                            kind = "claude",
                        ),
                    ),
                ),
            ),
            onSelect = {},
            onDismiss = {},
        )
    }
}
