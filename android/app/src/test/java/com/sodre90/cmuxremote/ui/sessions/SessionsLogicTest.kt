package com.sodre90.cmuxremote.ui.sessions

import com.sodre90.cmuxremote.model.PendingFeedItem
import com.sodre90.cmuxremote.model.TerminalPane
import com.sodre90.cmuxremote.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionsLogicTest {

    private fun ws(vararg paneIds: String) =
        Workspace(id = "w", terminals = paneIds.map { TerminalPane(id = it) })

    @Test
    fun singlePaneWorkspaceTargetsThatPane() {
        assertEquals("p1", singlePaneTarget(ws("p1")))
    }

    @Test
    fun multiPaneWorkspaceTargetsNull() {
        assertNull(singlePaneTarget(ws("p1", "p2")))
    }

    @Test
    fun zeroPaneWorkspaceTargetsNull() {
        assertNull(singlePaneTarget(ws()))
    }

    private fun wsWithFocus(vararg panes: Pair<String, Boolean>) =
        Workspace(id = "w", terminals = panes.map { (id, focused) -> TerminalPane(id = id, focused = focused) })

    @Test
    fun notificationTargetUsesSinglePaneDirectly() {
        assertEquals("p1", notificationTarget(ws("p1")))
    }

    @Test
    fun notificationTargetFallsBackToTheSoleFocusedPane() {
        val target = notificationTarget(wsWithFocus("p1" to false, "p2" to true, "p3" to false))
        assertEquals("p2", target)
    }

    @Test
    fun notificationTargetIsNullWhenNoPaneIsFocused() {
        assertNull(notificationTarget(wsWithFocus("p1" to false, "p2" to false)))
    }

    @Test
    fun notificationTargetIsNullWhenMultiplePanesAreFocused() {
        assertNull(notificationTarget(wsWithFocus("p1" to true, "p2" to true)))
    }

    @Test
    fun notificationTargetIsNullForZeroPanes() {
        assertNull(notificationTarget(ws()))
    }

    @Test
    fun pendingItemTargetMatchesWorkspaceByCwdThenUsesNotificationTargetHeuristic() {
        val item = PendingFeedItem(id = "i1", cwd = "/home/dev/proj")
        val workspaces = listOf(
            Workspace(id = "other", cwd = "/home/dev/other", terminals = listOf(TerminalPane(id = "op1"))),
            ws("p1").copy(cwd = "/home/dev/proj"),
        )
        assertEquals(TerminalMatch.Direct("p1"), pendingItemTarget(item, workspaces))
    }

    @Test
    fun pendingItemTargetFallsBackToFirstPaneWhenNoPaneIsFocused() {
        val item = PendingFeedItem(id = "i1", cwd = "/home/dev/proj")
        val matching = wsWithFocus("p1" to false, "p2" to false).copy(cwd = "/home/dev/proj")
        assertEquals(TerminalMatch.Direct("p1"), pendingItemTarget(item, listOf(matching)))
    }

    @Test
    fun pendingItemTargetIsNullWhenNoWorkspaceCwdMatches() {
        val item = PendingFeedItem(id = "i1", cwd = "/home/dev/proj")
        assertNull(pendingItemTarget(item, listOf(ws("p1").copy(cwd = "/home/dev/other"))))
    }

    @Test
    fun pendingItemTargetIsNullWhenItemHasNoCwd() {
        val item = PendingFeedItem(id = "i1", cwd = "")
        assertNull(pendingItemTarget(item, listOf(ws("p1").copy(cwd = ""))))
    }

    @Test
    fun pendingItemTargetIsAmbiguousWhenSeveralWorkspacesShareTheCwd() {
        // Live-observed: several parallel agent sessions commonly sit in the
        // same repo, all reporting the identical cwd -- firstOrNull would
        // silently route to an arbitrary one of them.
        val item = PendingFeedItem(id = "i1", cwd = "/home/dev/proj")
        val a = ws("p1").copy(id = "a", cwd = "/home/dev/proj")
        val b = ws("p2").copy(id = "b", cwd = "/home/dev/proj")
        assertEquals(TerminalMatch.Ambiguous(listOf(a, b)), pendingItemTarget(item, listOf(a, b)))
    }

    @Test
    fun needsAttentionIsTrueForPermissionOrInput() {
        assertEquals(true, needsAttention(Workspace(id = "w", attention = "permission")))
        assertEquals(true, needsAttention(Workspace(id = "w", attention = "input")))
        assertEquals(false, needsAttention(Workspace(id = "w", attention = "")))
    }

    @Test
    fun sortedByAttentionMovesWaitingWorkspacesFirstStably() {
        val calm1 = Workspace(id = "calm1", attention = "")
        val waiting1 = Workspace(id = "waiting1", attention = "input")
        val calm2 = Workspace(id = "calm2", attention = "")
        val waiting2 = Workspace(id = "waiting2", attention = "permission")

        val sorted = sortedByAttention(listOf(calm1, waiting1, calm2, waiting2))

        assertEquals(listOf("waiting1", "waiting2", "calm1", "calm2"), sorted.map { it.id })
    }

    @Test
    fun sortedByAttentionIsNoOpWhenNoneNeedIt() {
        val a = Workspace(id = "a", attention = "")
        val b = Workspace(id = "b", attention = "")
        assertEquals(listOf("a", "b"), sortedByAttention(listOf(a, b)).map { it.id })
    }

    @Test
    fun applyCustomOrderIsNoOpWhenOrderIsEmpty() {
        val a = Workspace(id = "a")
        val b = Workspace(id = "b")
        assertEquals(listOf("a", "b"), applyCustomOrder(listOf(a, b), emptyList()).map { it.id })
    }

    @Test
    fun applyCustomOrderReordersByPersistedIds() {
        val a = Workspace(id = "a")
        val b = Workspace(id = "b")
        val c = Workspace(id = "c")
        val reordered = applyCustomOrder(listOf(a, b, c), listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), reordered.map { it.id })
    }

    @Test
    fun applyCustomOrderAppendsUnknownIdsAfterKnownOnesStably() {
        val a = Workspace(id = "a")
        val b = Workspace(id = "b") // not in the saved order
        val c = Workspace(id = "c")
        val d = Workspace(id = "d") // not in the saved order
        val reordered = applyCustomOrder(listOf(a, b, c, d), listOf("c", "a"))
        assertEquals(listOf("c", "a", "b", "d"), reordered.map { it.id })
    }

    private fun statusDescription(ws: Workspace) =
        workspaceStatusDescription(ws, "needs permission", "waiting for input", "unread")

    @Test
    fun workspaceStatusDescriptionIsNullForACalmReadWorkspace() {
        assertNull(statusDescription(Workspace(id = "w")))
    }

    @Test
    fun workspaceStatusDescriptionNamesThePermissionState() {
        assertEquals(
            "needs permission",
            statusDescription(Workspace(id = "w", attention = "permission")),
        )
    }

    @Test
    fun workspaceStatusDescriptionNamesTheInputWaitState() {
        assertEquals(
            "waiting for input",
            statusDescription(Workspace(id = "w", attention = "input")),
        )
    }

    @Test
    fun workspaceStatusDescriptionNamesUnread() {
        assertEquals("unread", statusDescription(Workspace(id = "w", hasUnread = true)))
    }

    @Test
    fun workspaceStatusDescriptionCombinesAttentionAndUnread() {
        assertEquals(
            "needs permission, unread",
            statusDescription(Workspace(id = "w", attention = "permission", hasUnread = true)),
        )
    }
}
