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
        assertEquals("p1", pendingItemTarget(item, workspaces))
    }

    @Test
    fun pendingItemTargetFallsBackToFirstPaneWhenNoPaneIsFocused() {
        val item = PendingFeedItem(id = "i1", cwd = "/home/dev/proj")
        val matching = wsWithFocus("p1" to false, "p2" to false).copy(cwd = "/home/dev/proj")
        assertEquals("p1", pendingItemTarget(item, listOf(matching)))
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
    fun paneCountLabelIsSingularOrPlural() {
        assertEquals("0 panes", paneCountLabel(0))
        assertEquals("1 pane", paneCountLabel(1))
        assertEquals("3 panes", paneCountLabel(3))
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
}
