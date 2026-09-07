package com.sodre90.cmuxremote.ui.terminal

import com.sodre90.cmuxremote.model.DecodedGrid
import com.sodre90.cmuxremote.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The socket-down signal (cmux-app-bx1). The terminal keeps the last grid on
 * screen through a reconnect rather than showing an error page, so without this
 * flag a frozen frame is pixel-identical to a live but idle agent.
 */
class StaleScreenTest {
    private val content = TerminalContent(
        grid = DecodedGrid(columns = 4, rows = 1, lines = emptyList(), cursor = null),
    )

    @Test fun aRenderedScreenBecomesStale() {
        val marked = staleMarked(UiState.Ready(content))
        assertTrue((marked as UiState.Ready).data.stale)
        // The grid itself must survive -- the whole point is to keep showing it.
        assertEquals(content.grid, marked.data.grid)
    }

    @Test fun markingIsIdempotent() {
        val alreadyStale = UiState.Ready(content.copy(stale = true))
        assertSame(alreadyStale, staleMarked(alreadyStale))
    }

    @Test fun loadingAndErrorAreLeftAlone() {
        // Loading already says "nothing to trust yet"; Error has no grid to caveat.
        val loading: UiState<TerminalContent> = UiState.Loading
        val error: UiState<TerminalContent> = UiState.Error("bridge not configured")
        assertSame(loading, staleMarked(loading))
        assertSame(error, staleMarked(error))
    }

    @Test fun aFreshFrameIsLiveByDefault() {
        // onFrame builds TerminalContent without naming `stale`, which is what
        // clears the banner when output resumes.
        assertFalse(content.stale)
    }
}
