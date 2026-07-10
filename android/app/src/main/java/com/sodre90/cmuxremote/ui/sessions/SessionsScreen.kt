package com.sodre90.cmuxremote.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.model.TerminalPane
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.model.YoloMode
import com.sodre90.cmuxremote.ui.UiState
import com.sodre90.cmuxremote.ui.YoloBadge
import com.sodre90.cmuxremote.ui.yoloModeLabel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
                    TextButton(onClick = { vm.silentRefresh() }) { Text("Refresh") }
                    TextButton(onClick = onSettings) { Text("Re-pair device") }
                },
            )
        },
    ) { inner ->
        val isRefreshing by vm.isRefreshing.collectAsState()
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            when (val s = state) {
                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is UiState.Error -> Text(
                    text = s.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is UiState.Ready -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.silentRefresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    WorkspaceList(vm, s.data, onOpenTerminal)
                }
            }
        }
    }
}

/** Saves the card-expanded map across rotation -- a SnapshotStateMap isn't
 *  itself Bundleable, so round-trip it through a plain Map. */
private val ExpandedMapSaver = mapSaver(
    save = { it.toMap() },
    restore = { saved ->
        mutableStateMapOf<String, Boolean>().apply {
            putAll(saved.mapValues { (_, v) -> v as Boolean })
        }
    },
)

@Composable
private fun WorkspaceList(vm: SessionsViewModel, workspaces: List<Workspace>, onOpen: (String) -> Unit) {
    if (workspaces.isEmpty()) {
        Box(Modifier.fillMaxSize()) { Text("No sessions", Modifier.align(Alignment.Center)) }
        return
    }
    val expanded = rememberSaveable(saver = ExpandedMapSaver) { mutableStateMapOf() }
    var sortByAttention by remember { mutableStateOf(false) }

    // The phone-local drag order (see WorkspaceOrderStore); read once, then
    // kept in sync locally so drags feel instant without waiting on a
    // SharedPreferences round trip through the view model.
    var customOrder by rememberSaveable { mutableStateOf(vm.loadOrder()) }
    val baseOrdered = remember(workspaces, customOrder) { applyCustomOrder(workspaces, customOrder) }
    val ordered = if (sortByAttention) sortedByAttention(baseOrdered) else baseOrdered

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val reordered = ordered.toMutableList().apply { add(to.index, removeAt(from.index)) }
        customOrder = reordered.map { it.id }
        vm.saveOrder(customOrder)
    }

    var renamingWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var yoloPickerWorkspace by remember { mutableStateOf<Workspace?>(null) }

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
            state = lazyListState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ordered, key = { it.id }) { ws ->
                ReorderableItem(reorderableLazyListState, key = ws.id) {
                    WorkspaceCard(
                        ws = ws,
                        expanded = expanded[ws.id] == true,
                        onToggle = { expanded[ws.id] = !(expanded[ws.id] ?: false) },
                        onOpen = onOpen,
                        onRename = { renamingWorkspace = ws },
                        onYoloMode = { yoloPickerWorkspace = ws },
                        dragHandle = {
                            // Custom order is only meaningful when it's the
                            // visible order -- disabled while "Waiting first"
                            // is on so a drag can't silently scramble it.
                            if (sortByAttention) {
                                IconButton(onClick = {}, enabled = false) {
                                    Icon(
                                        Icons.Filled.Menu,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    )
                                }
                            } else {
                                IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Drag to reorder")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    renamingWorkspace?.let { ws ->
        RenameDialog(
            initial = ws.title.ifBlank { ws.preview.ifBlank { ws.cwd } },
            onDismiss = { renamingWorkspace = null },
            onConfirm = { newTitle ->
                vm.renameWorkspace(ws.id, newTitle)
                renamingWorkspace = null
            },
        )
    }

    yoloPickerWorkspace?.let { ws ->
        YoloModeDialog(
            current = ws.yoloMode,
            onDismiss = { yoloPickerWorkspace = null },
            onSelect = { mode ->
                vm.setYoloMode(ws.id, mode)
                yoloPickerWorkspace = null
            },
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename workspace") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private data class YoloModeOption(val mode: String, val label: String, val description: String)

// Picker order/labels for the YOLO mode dialog. Off first (the common,
// unblocked-from-the-agent-inbox case). Descriptions mirror cmux's own Feed
// permission-mode semantics (docs/feed.md's decision-semantics table).
private val YoloModeOptions = listOf(
    YoloModeOption(YoloMode.OFF, "Off", "You answer each permission prompt from the phone."),
    YoloModeOption(
        YoloMode.ALWAYS,
        "Always",
        "Auto-approves, applying the agent's suggested rule to future similar requests.",
    ),
    YoloModeOption(
        YoloMode.ALL_TOOLS,
        "All tools",
        "Auto-approves any tool the agent asks to use, not just this one.",
    ),
    YoloModeOption(
        YoloMode.BYPASS,
        "Bypass",
        "Skips permission checks for the rest of this session (Claude Code's --dangerously-skip-permissions).",
    ),
)

@Composable
private fun YoloModeDialog(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("YOLO mode") },
        text = {
            Column {
                YoloModeOptions.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onSelect(option.mode) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option.mode == current, onClick = { onSelect(option.mode) })
                        Column {
                            Text(option.label)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceCard(
    ws: Workspace,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: () -> Unit,
    onYoloMode: () -> Unit,
    dragHandle: @Composable () -> Unit,
) {
    var showActionMenu by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        // A left accent stripe flags agents that want attention: red when blocked
        // on a permission prompt, amber when idle waiting for input. IntrinsicSize
        // lets the stripe span the card's full height (header + expanded panes).
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            attentionAccent(ws.attention)?.let { accent ->
                Box(Modifier.fillMaxHeight().width(5.dp).background(accent))
            }
            Column(modifier = Modifier.weight(1f)) {
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (ws.terminals.isEmpty()) return@combinedClickable
                                    val direct = singlePaneTarget(ws)
                                    if (direct != null) onOpen(direct) else onToggle()
                                },
                                onLongClick = { showActionMenu = true },
                            )
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    // The workspace name, not the agent-status preview — the
                                    // attention stripe already conveys waiting/permission state.
                                    text = ws.title.ifBlank { ws.preview.ifBlank { ws.cwd } },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                yoloModeLabel(ws.yoloMode)?.let { YoloBadge(it) }
                            }
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
                        dragHandle()
                    }
                    DropdownMenu(expanded = showActionMenu, onDismissRequest = { showActionMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showActionMenu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("YOLO mode…") },
                            onClick = {
                                showActionMenu = false
                                onYoloMode()
                            },
                        )
                    }
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
private val WaitingAccent = Color(0xFFFFB300) // amber — agent idle, waiting for input

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
