package com.sodre90.cmuxremote.ui.sessions

import com.sodre90.cmuxremote.model.Workspace

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
