package com.sodre90.cmuxremote.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.model.TerminalPane
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    vm: SessionsViewModel,
    onOpenTerminal: (String) -> Unit,
    onOpenInbox: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("cmux sessions") },
                actions = {
                    TextButton(onClick = onOpenInbox) { Text("Inbox") }
                    TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                    TextButton(onClick = onSettings) { Text("Re-pair device") }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Error -> Text(
                    text = s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is UiState.Ready -> WorkspaceList(s.data, onOpenTerminal)
            }
        }
    }
}

@Composable
private fun WorkspaceList(workspaces: List<Workspace>, onOpen: (String) -> Unit) {
    if (workspaces.isEmpty()) {
        Box(Modifier.fillMaxSize()) { Text("No sessions", Modifier.align(Alignment.Center)) }
        return
    }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var sortByAttention by remember { mutableStateOf(false) }
    val ordered = if (sortByAttention) sortedByAttention(workspaces) else workspaces
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Waiting first",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = sortByAttention, onCheckedChange = { sortByAttention = it })
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ordered, key = { it.id }) { ws ->
                WorkspaceCard(
                    ws = ws,
                    expanded = expanded[ws.id] == true,
                    onToggle = { expanded[ws.id] = !(expanded[ws.id] ?: false) },
                    onOpen = onOpen,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceCard(
    ws: Workspace,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        // A left accent stripe flags agents that want attention: red when blocked
        // on a permission prompt, amber when idle waiting for input. IntrinsicSize
        // lets the stripe span the card's full height (header + expanded panes).
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            attentionAccent(ws.attention)?.let { accent ->
                Box(Modifier.fillMaxHeight().width(5.dp).background(accent))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = ws.terminals.isNotEmpty()) {
                            val direct = singlePaneTarget(ws)
                            if (direct != null) onOpen(direct) else onToggle()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            // The workspace name, not the agent-status preview — the
                            // attention stripe already conveys waiting/permission state.
                            text = ws.title.ifBlank { ws.preview.ifBlank { ws.cwd } },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = ws.cwd,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (ws.hasUnread) {
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape,
                            modifier = Modifier.size(10.dp),
                        ) {}
                    }
                    Text(
                        text = paneCountLabel(ws.terminals.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    ws.terminals.forEach { pane -> PaneRow(pane, onOpen) }
                }
            }
        }
    }
}

// Accent colors for the attention stripe. Null = no stripe (normal workspace).
private val PermissionAccent = Color(0xFFE53935) // red — agent blocked on a prompt
private val WaitingAccent = Color(0xFFFFB300)    // amber — agent idle, waiting for input

private fun attentionAccent(attention: String): Color? = when (attention) {
    "permission" -> PermissionAccent
    "input" -> WaitingAccent
    else -> null
}

@Composable
private fun PaneRow(pane: TerminalPane, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onOpen(pane.id) }
            .padding(start = 28.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = pane.title.ifBlank { pane.cwd },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!pane.ready) {
            Text(
                text = "starting…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KindBadge(pane.kind)
        if (pane.focused) {
            Text(
                text = "focus",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun KindBadge(kind: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = kind.ifBlank { "?" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
