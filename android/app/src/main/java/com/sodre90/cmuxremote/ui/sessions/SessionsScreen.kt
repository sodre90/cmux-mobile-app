package com.sodre90.cmuxremote.ui.sessions

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.model.TerminalPane
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.model.YoloMode
import com.sodre90.cmuxremote.ui.UiState
import com.sodre90.cmuxremote.ui.YoloAccent
import com.sodre90.cmuxremote.ui.YoloBadge
import com.sodre90.cmuxremote.ui.theme.AppColors
import com.sodre90.cmuxremote.ui.theme.CmuxTheme
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
    val unreadCount = (state as? UiState.Ready)?.data?.let { unreadWorkspaceCount(it) } ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sessions_title)) },
                actions = {
                    BadgedBox(
                        badge = { if (unreadCount > 0) Badge { Text(unreadCount.toString()) } },
                    ) {
                        TextButton(onClick = onOpenInbox) { Text(stringResource(R.string.sessions_inbox_button)) }
                    }
                    TextButton(onClick = { vm.userRefresh() }) { Text(stringResource(R.string.action_refresh)) }
                    TextButton(onClick = onSettings) { Text(stringResource(R.string.sessions_settings_button)) }
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
                    onRefresh = { vm.userRefresh() },
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
        Box(Modifier.fillMaxSize()) { Text(stringResource(R.string.sessions_empty), Modifier.align(Alignment.Center)) }
        return
    }
    val expanded = rememberSaveable(saver = ExpandedMapSaver) { mutableStateMapOf() }

    // Persisted the same way as customOrder below (see WorkspaceOrderStore) so
    // it survives an app restart, not just a rotation.
    var sortByAttention by rememberSaveable { mutableStateOf(vm.loadSortByAttention()) }

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

    // Computed off the unsorted list so it doesn't flicker as "Waiting first" toggles.
    val autopilot = remember(workspaces) { autopilotSummary(workspaces) }

    Column(modifier = Modifier.fillMaxSize()) {
        autopilot?.let {
            AutopilotBanner(
                pluralStringResource(R.plurals.autopilot_summary, it.count, it.count, it.names),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.sessions_waiting_first_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = sortByAttention,
                onCheckedChange = {
                    sortByAttention = it
                    vm.saveSortByAttention(it)
                },
            )
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
                                    val dragDescription =
                                        stringResource(R.string.sessions_drag_to_reorder_description)
                                    Icon(Icons.Filled.Menu, contentDescription = dragDescription)
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
private fun AutopilotBanner(label: String) {
    Surface(
        color = YoloAccent.copy(alpha = 0.15f),
        contentColor = YoloAccent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sessions_rename_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// Resource ids (not resolved strings) since this list is built outside any
// @Composable context -- resolved via stringResource() in YoloModeDialog below.
private data class YoloModeOption(val mode: String, @StringRes val labelRes: Int, @StringRes val descriptionRes: Int)

// Picker order/labels for the YOLO mode dialog. Off first (the common,
// unblocked-from-the-agent-inbox case). Descriptions mirror cmux's own Feed
// permission-mode semantics (docs/feed.md's decision-semantics table).
private val YoloModeOptions = listOf(
    YoloModeOption(YoloMode.OFF, R.string.yolo_mode_off_label, R.string.yolo_mode_off_description),
    YoloModeOption(YoloMode.ALWAYS, R.string.yolo_mode_always_label, R.string.yolo_mode_always_description),
    YoloModeOption(YoloMode.ALL_TOOLS, R.string.yolo_mode_all_tools_label, R.string.yolo_mode_all_tools_description),
    YoloModeOption(YoloMode.BYPASS, R.string.yolo_mode_bypass_label, R.string.yolo_mode_bypass_description),
)

@Composable
private fun YoloModeDialog(current: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    // Bypass removes the safety net entirely for the rest of the session (cmux's
    // --dangerously-skip-permissions equivalent), so unlike the other three modes
    // it gets error-colored styling AND a confirm step -- color alone doesn't stop
    // a mis-tap, and a confirm alone is easy to blow through without registering
    // what was just enabled. Leaving Bypass (selecting any other mode) is the safe
    // direction and stays a single tap, same as Off/Always/All tools.
    var pendingBypassConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.yolo_mode_dialog_title)) },
        text = {
            Column {
                YoloModeOptions.forEach { option ->
                    val isBypass = option.mode == YoloMode.BYPASS
                    val select = { if (isBypass) pendingBypassConfirm = true else onSelect(option.mode) }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(onClick = select)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = option.mode == current,
                            onClick = select,
                            colors = if (isBypass) {
                                RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.error)
                            } else {
                                RadioButtonDefaults.colors()
                            },
                        )
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (isBypass) {
                                    Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Text(
                                    stringResource(option.labelRes),
                                    color = if (isBypass) MaterialTheme.colorScheme.error else Color.Unspecified,
                                )
                            }
                            Text(
                                stringResource(option.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isBypass) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )

    if (pendingBypassConfirm) {
        AlertDialog(
            onDismissRequest = { pendingBypassConfirm = false },
            title = { Text(stringResource(R.string.yolo_bypass_confirm_title)) },
            text = { Text(stringResource(R.string.yolo_bypass_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingBypassConfirm = false
                        onSelect(YoloMode.BYPASS)
                    },
                ) {
                    Text(stringResource(R.string.action_enable), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBypassConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
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
                    // The attention stripe and unread dot below are color/shape-only
                    // (see attentionAccent) -- fold what they mean into this row's own
                    // merged announcement instead, rather than putting semantics
                    // directly on either: the stripe sits outside this clickable Row
                    // entirely (a sibling in the outer accent Row), so a contentDescription
                    // on it would surface as its own, title-less TalkBack stop instead of
                    // part of this card's summary.
                    val statusDescription = workspaceStatusDescription(
                        ws,
                        permissionLabel = stringResource(R.string.status_needs_permission),
                        waitingLabel = stringResource(R.string.status_waiting_input),
                        unreadLabel = stringResource(R.string.status_unread),
                    )
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
                            .then(
                                if (statusDescription != null) {
                                    Modifier.semantics { contentDescription = statusDescription }
                                } else {
                                    Modifier
                                },
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
                            text = pluralStringResource(R.plurals.pane_count, ws.terminals.size, ws.terminals.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Long-press already opens this same menu (power-user shortcut);
                        // this icon is the discoverable affordance for everyone else.
                        IconButton(onClick = { showActionMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.sessions_workspace_actions_description),
                            )
                        }
                        dragHandle()
                    }
                    DropdownMenu(expanded = showActionMenu, onDismissRequest = { showActionMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = {
                                showActionMenu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.yolo_mode_menu_item)) },
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

@Preview(showBackground = true)
@Composable
private fun WorkspaceCardPreview() {
    CmuxTheme {
        WorkspaceCard(
            ws = Workspace(
                id = "ws-1",
                cwd = "/Users/dev/projects/cmux-app",
                title = "cmux-app",
                terminals = listOf(TerminalPane(id = "t-1", title = "main", ready = true)),
            ),
            expanded = false,
            onToggle = {},
            onOpen = {},
            onRename = {},
            onYoloMode = {},
            dragHandle = {},
        )
    }
}

@Preview(showBackground = true, name = "Unread + attention + YOLO")
@Composable
private fun WorkspaceCardAttentionPreview() {
    CmuxTheme {
        WorkspaceCard(
            ws = Workspace(
                id = "ws-2",
                cwd = "/Users/dev/projects/other-repo",
                title = "other-repo",
                hasUnread = true,
                attention = "permission",
                yoloMode = YoloMode.ALWAYS,
                terminals = listOf(
                    TerminalPane(id = "t-2", title = "main", ready = true, focused = true, kind = "claude"),
                    TerminalPane(id = "t-3", title = "logs", ready = false, kind = "shell"),
                ),
            ),
            expanded = true,
            onToggle = {},
            onOpen = {},
            onRename = {},
            onYoloMode = {},
            dragHandle = {},
        )
    }
}

// Null = no stripe (normal workspace); the color values live in AppColors (theme-level).
private fun attentionAccent(attention: String): Color? = when (attention) {
    "permission" -> AppColors.PermissionAccent
    "input" -> AppColors.WaitingAccent
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
                text = stringResource(R.string.pane_starting),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KindBadge(pane.kind)
        if (pane.focused) {
            Text(
                text = stringResource(R.string.pane_focus),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PaneRowPreview() {
    CmuxTheme {
        PaneRow(pane = TerminalPane(id = "t-1", title = "main", ready = true, kind = "claude"), onOpen = {})
    }
}

@Preview(showBackground = true, name = "Starting + focused")
@Composable
private fun PaneRowStartingFocusedPreview() {
    CmuxTheme {
        PaneRow(
            pane = TerminalPane(id = "t-2", title = "build", ready = false, focused = true, kind = "shell"),
            onOpen = {},
        )
    }
}

@Composable
private fun KindBadge(kind: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = kind.ifBlank { stringResource(R.string.kind_badge_unknown) },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun KindBadgePreview() {
    CmuxTheme { KindBadge(kind = "claude") }
}

@Preview(showBackground = true, name = "Unknown kind")
@Composable
private fun KindBadgeUnknownPreview() {
    CmuxTheme { KindBadge(kind = "") }
}
