package com.sodre90.cmuxremote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaGridMergeTest {

    private val history = listOf(RowSpan(row = 0, text = "history"))

    private val previous = RenderGrid(
        columns = 8,
        rows = 1,
        rowSpans = listOf(RowSpan(row = 0, text = "before")),
        scrollbackSpans = history,
        scrollbackRows = 1,
    )

    /** The ordinary delta frame: new visible rows, scrollback left out. */
    @Test
    fun carriesAnOmittedScrollbackForwardFromThePreviousGrid() {
        val delta = RenderGrid(
            columns = 8,
            rows = 1,
            rowSpans = listOf(RowSpan(row = 0, text = "after")),
            scrollbackRows = 1,
        )
        val merged = delta.mergedOnto(previous, listOf(UnchangedBlock.SCROLLBACK_SPANS))
        assertEquals(history, merged.scrollbackSpans)
        assertEquals("after", merged.rowSpans.single().text)
    }

    /**
     * The distinction the explicit name list exists for. A pane whose
     * scrollback genuinely cleared sends an absent block too, and must not have
     * the old history put back.
     */
    @Test
    fun leavesAClearedScrollbackClearedWhenItIsNotNamedUnchanged() {
        val cleared = RenderGrid(columns = 8, rows = 1, scrollbackRows = 0)
        val merged = cleared.mergedOnto(previous, emptyList())
        assertTrue("a cleared scrollback must stay cleared", merged.scrollbackSpans.isEmpty())
    }

    /** A replay frame names nothing, so it replaces the base wholesale --
     *  which is what makes a reconnect a clean resync. */
    @Test
    fun aFrameThatNamesNothingIsUsedAsItArrived() {
        val replay = RenderGrid(columns = 8, rows = 1, scrollbackSpans = history, scrollbackRows = 1)
        assertSame(replay, replay.mergedOnto(previous, emptyList()))
    }

    /** Nothing to merge from: the first frame on a socket. */
    @Test
    fun returnsTheFrameUnchangedWithNoPreviousGrid() {
        val first = RenderGrid(columns = 8, rows = 1)
        assertSame(first, first.mergedOnto(null, listOf(UnchangedBlock.SCROLLBACK_SPANS)))
    }

    /** The bridge sends scrollback_rows on every frame, so this frame's own
     *  count wins rather than the carried-over one. */
    @Test
    fun takesTheRowCountFromTheArrivingFrame() {
        val delta = RenderGrid(columns = 8, rows = 1, scrollbackRows = 7)
        val merged = delta.mergedOnto(previous, listOf(UnchangedBlock.SCROLLBACK_SPANS))
        assertEquals(7, merged.scrollbackRows)
    }

    /** An unrecognised block name from a newer bridge must not be guessed at. */
    @Test
    fun ignoresABlockNameItDoesNotKnow() {
        val delta = RenderGrid(columns = 8, rows = 1)
        val merged = delta.mergedOnto(previous, listOf("something_new"))
        assertTrue(merged.scrollbackSpans.isEmpty())
    }
}
