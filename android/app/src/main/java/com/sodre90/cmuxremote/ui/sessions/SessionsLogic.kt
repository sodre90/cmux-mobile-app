package com.sodre90.cmuxremote.ui.sessions

import com.sodre90.cmuxremote.model.Workspace

/**
 * The surface id to open directly when a workspace card is tapped, or null when
 * the card should expand to show its panes instead. Exactly one pane → open it
 * directly; zero or many panes → null (zero has nothing to open, many expands).
 */
fun singlePaneTarget(ws: Workspace): String? =
    if (ws.terminals.size == 1) ws.terminals[0].id else null

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
