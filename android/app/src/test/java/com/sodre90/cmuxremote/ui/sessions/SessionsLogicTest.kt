package com.sodre90.cmuxremote.ui.sessions

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

    @Test
    fun paneCountLabelIsSingularOrPlural() {
        assertEquals("0 panes", paneCountLabel(0))
        assertEquals("1 pane", paneCountLabel(1))
        assertEquals("3 panes", paneCountLabel(3))
    }
}
