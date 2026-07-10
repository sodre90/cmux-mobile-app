package com.sodre90.cmuxremote.ui.sessions

import com.sodre90.cmuxremote.model.PendingFeedItem
import com.sodre90.cmuxremote.model.Workspace
import com.sodre90.cmuxremote.ui.yoloModeLabel

/**
 * The surface id to open directly when a workspace card is tapped, or null when
 * the card should expand to show its panes instead. Exactly one pane → open it
 * directly; zero or many panes → null (zero has nothing to open, many expands).
 */
fun singlePaneTarget(ws: Workspace): String? =
    if (ws.terminals.size == 1) ws.terminals[0].id else null

/**
 * The surface id to open when a push notification for [ws] is tapped. Unlike
 * [singlePaneTarget] (a manual workspace-card tap, where ambiguity should
 * expand the card for the user to pick), a notification tap has no better
 * fallback than the undifferentiated sessions list -- so with several panes
 * this still resolves directly when exactly one is cmux's own focused pane.
 * cmux never reports which pane raised a given event, so this is a
 * best-effort heuristic, not a guarantee: "focused" reflects whichever pane
 * was last selected in cmux's own UI, which can be stale if several agents
 * run in parallel and attention has since moved to a different one.
 */
fun notificationTarget(ws: Workspace): String? =
    singlePaneTarget(ws) ?: ws.terminals.singleOrNull { it.focused }?.id

/**
 * The surface id to open for [item]'s originating terminal, resolved against
 * the live [workspaces] list, or null if none matches. [PendingFeedItem]
 * carries no workspace/surface id of its own -- its `workstream_id` is the
 * agent's own session id, a different id space than cmux's workspace id (see
 * bridge yolo.go's `resolvePendingPermission` doc comment) -- so [cwd] is the
 * only field it shares with a [Workspace]; matching on it mirrors the same
 * correlation the bridge already relies on server-side for YOLO auto-resolve.
 * Once a workspace is found, pane selection goes one step further than
 * [notificationTarget]: falling back to the first pane rather than giving up,
 * since -- unlike a notification tap, which can fall back to the whole
 * sessions list -- an inbox row's whole point is jumping straight to a
 * terminal.
 */
fun pendingItemTarget(item: PendingFeedItem, workspaces: List<Workspace>): String? {
    if (item.cwd.isBlank()) return null
    val ws = workspaces.firstOrNull { it.cwd.isNotBlank() && it.cwd == item.cwd } ?: return null
    return notificationTarget(ws) ?: ws.terminals.firstOrNull()?.id
}

/** Trailing pane-count label, e.g. "0 panes" / "1 pane" / "3 panes". */
fun paneCountLabel(count: Int): String =
    if (count == 1) "1 pane" else "$count panes"

/** True when the workspace's agent is waiting on the user: blocked on a
 *  permission prompt or idle for input. Mirrors [attentionAccent]'s cases. */
fun needsAttention(ws: Workspace): Boolean = ws.attention.isNotEmpty()

/** Stable-sorts [workspaces] so attention-needing ones come first, preserving
 *  each group's original relative order. */
fun sortedByAttention(workspaces: List<Workspace>): List<Workspace> =
    workspaces.sortedByDescending { needsAttention(it) }

/** Number of [workspaces] with a pending unread prompt -- the same signal
 *  behind each card's red dot, aggregated for the top-bar Inbox badge. Not an
 *  exact unread-item count: the bridge only reports hasUnread per workspace,
 *  not how many items are pending within one. */
fun unreadWorkspaceCount(workspaces: List<Workspace>): Int = workspaces.count { it.hasUnread }

/** A compact "N workspaces on autopilot: foo, bar" summary of every workspace
 *  in an auto-reply YOLO mode (anything [yoloModeLabel] would badge on its
 *  card: Always/All tools/Bypass), or null when none are. Meant for a banner
 *  the user can scan before stepping away from the phone. */
fun autopilotSummaryLabel(workspaces: List<Workspace>): String? {
    val autopilot = workspaces.filter { yoloModeLabel(it.yoloMode) != null }
    if (autopilot.isEmpty()) return null
    val names = autopilot.joinToString(", ") { it.title.ifBlank { it.preview.ifBlank { it.cwd } } }
    val noun = if (autopilot.size == 1) "workspace" else "workspaces"
    return "${autopilot.size} $noun on autopilot: $names"
}

/** Reorders [workspaces] by a phone-local, persisted custom id [order]: ids
 *  present in [order] come first in that sequence; any other workspace (new,
 *  or from before an order existed) keeps its relative position, appended
 *  after all known ids. Stable sort, so ties resolve to [workspaces]'s own
 *  order. */
fun applyCustomOrder(workspaces: List<Workspace>, order: List<String>): List<Workspace> {
    if (order.isEmpty()) return workspaces
    val rank = order.withIndex().associate { (i, id) -> id to i }
    return workspaces.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
}
