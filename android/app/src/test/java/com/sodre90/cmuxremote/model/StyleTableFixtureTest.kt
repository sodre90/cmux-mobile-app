package com.sodre90.cmuxremote.model

import com.sodre90.cmuxremote.ui.terminal.defaultBackgroundOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The bridge renumbers style ids so that a frame whose styles did not change
 * comes out byte-identical (its styleTable, cmux-app-bly). The ids the app
 * receives are therefore the bridge's, not cmux's, and this is the test that
 * the difference is invisible: the same frame, as cmux sent it and as the
 * bridge rewrote it, must render cell for cell the same.
 *
 * Both constants are copied from the Go side's
 * TestTheCrossLanguageStyleFixtureIsUnchanged. If that test fails after a
 * bridge change, regenerate these from what it prints.
 */
class StyleTableFixtureTest {

    private val asCmuxSentIt =
        """{"render_epoch":"E1","scrollback_rows":240,"cleared_rows":[],"scrolled_rows":0,""" +
            """"anchor":"viewport","active_screen":"primary","styles":[""" +
            """{"id":0,"foreground":"#fff","background":"#000","bold":false},""" +
            """{"id":1,"foreground":"#bbb","bold":false},{"id":2,"foreground":"#bold","bold":true},""" +
            """{"id":3,"foreground":"#aaa","bold":false}],"row_spans":[""" +
            """{"row":0,"column":0,"style_id":3,"text":"ab","cell_width":1},""" +
            """{"row":1,"column":0,"style_id":2,"text":"cd","cell_width":1}],"scrollback_spans":[]}"""

    private val asTheBridgeRewroteIt =
        """{"active_screen":"primary","anchor":"viewport","cleared_rows":[],"render_epoch":"E1",""" +
            """"row_spans":[{"cell_width":1,"column":0,"row":0,"style_id":1,"text":"ab"},""" +
            """{"cell_width":1,"column":0,"row":1,"style_id":3,"text":"cd"}],""" +
            """"scrollback_rows":240,"scrollback_spans":[],"scrolled_rows":0,""" +
            """"styles":[{"background":"#000","bold":false,"foreground":"#fff","id":0},""" +
            """{"bold":false,"foreground":"#aaa","id":1},{"bold":false,"foreground":"#bbb","id":2},""" +
            """{"bold":true,"foreground":"#bold","id":3}]}"""

    private fun grid(json: String): RenderGrid =
        BridgeJson.decodeFromString(RenderGrid.serializer(), json).copy(columns = 4, rows = 2)

    /** What a cell looks like once its id has been looked up: the only thing
     *  the two frames are allowed to agree on, since the ids themselves differ. */
    private fun rendered(grid: RenderGrid): List<List<Pair<Char, Style?>>> {
        val styles = grid.styles.associateBy { it.id }
        return RenderGridDecoder.decode(grid).lines.map { line ->
            line.cells.map { it.char to styles[it.styleId]?.copy(id = 0) }
        }
    }

    @Test
    fun rendersTheRewrittenFrameExactlyLikeTheOriginal() {
        val original = grid(asCmuxSentIt)
        val rewritten = grid(asTheBridgeRewroteIt)
        assertEquals(rendered(original), rendered(rewritten))
    }

    /** The test above would pass vacuously if the bridge had not renumbered
     *  anything; this pins that it did. */
    @Test
    fun theIdsReallyDoDiffer() {
        val original = grid(asCmuxSentIt)
        val rewritten = grid(asTheBridgeRewroteIt)
        assertNotEquals(original.rowSpans.map { it.styleId }, rewritten.rowSpans.map { it.styleId })
    }

    /** Blank cells take style 0 and the grid's default background comes from it,
     *  so 0 is the one id whose meaning the bridge must not move. */
    @Test
    fun theDefaultBackgroundSurvivesRenumbering() {
        val original = grid(asCmuxSentIt)
        val rewritten = grid(asTheBridgeRewroteIt)
        assertEquals("#000", defaultBackgroundOf(original.styles.associateBy { it.id }))
        assertEquals(
            defaultBackgroundOf(original.styles.associateBy { it.id }),
            defaultBackgroundOf(rewritten.styles.associateBy { it.id }),
        )
    }
}
