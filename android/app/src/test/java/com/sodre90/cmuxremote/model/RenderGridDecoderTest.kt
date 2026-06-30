package com.sodre90.cmuxremote.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderGridDecoderTest {

    private val sample = """
        {
          "format":"cmux.render-grid.v1",
          "columns":10,"rows":2,
          "cursor":{"row":1,"column":3,"visible":true},
          "styles":[
            {"id":0},
            {"id":1,"bold":true,"foreground":"#ff8800","weird_future":true}
          ],
          "row_spans":[
            {"row":0,"column":0,"cell_width":5,"style_id":0,"text":"hello"},
            {"row":1,"column":2,"cell_width":3,"style_id":1,"text":"git"}
          ],
          "state_seq":42
        }
    """.trimIndent()

    private fun grid() = BridgeJson.decodeFromString(RenderGrid.serializer(), sample)

    @Test
    fun decodesTextAtCorrectColumns() {
        val decoded = RenderGridDecoder.decode(grid())
        assertEquals(2, decoded.rows)
        assertEquals(10, decoded.columns)
        assertEquals("hello     ", decoded.lines[0].text)
        assertEquals("  git     ", decoded.lines[1].text)
    }

    @Test
    fun carriesStyleIdsPerCell() {
        val decoded = RenderGridDecoder.decode(grid())
        assertEquals('g', decoded.lines[1].cells[2].char)
        assertEquals(1, decoded.lines[1].cells[2].styleId)
        assertEquals(0, decoded.lines[1].cells[0].styleId)
    }

    @Test
    fun parsesStyleFlagsAndColorIgnoringUnknownKeys() {
        val s1 = grid().styles.first { it.id == 1 }
        assertTrue(s1.bold)
        assertEquals("#ff8800", s1.foregroundString)
    }

    @Test
    fun preservesCursorPosition() {
        val decoded = RenderGridDecoder.decode(grid())
        assertEquals(1, decoded.cursor?.row)
        assertEquals(3, decoded.cursor?.column)
    }

    @Test
    fun clampsSpansThatOverflowColumns() {
        val js =
            """{"columns":4,"rows":1,"row_spans":[{"row":0,"column":2,"cell_width":5,"style_id":0,"text":"abcdef"}]}"""
        val decoded = RenderGridDecoder.decode(BridgeJson.decodeFromString(RenderGrid.serializer(), js))
        assertEquals("  ab", decoded.lines[0].text)
    }

    @Test
    fun decodesScrollbackLinesBeforeVisible() {
        val js = """
            {"columns":5,"rows":1,"scrollback_rows":2,
             "scrollback_spans":[
               {"row":0,"column":0,"style_id":0,"text":"old1"},
               {"row":1,"column":0,"style_id":0,"text":"old2"}],
             "row_spans":[{"row":0,"column":0,"style_id":0,"text":"live"}]}
        """.trimIndent()
        val d = RenderGridDecoder.decode(BridgeJson.decodeFromString(RenderGrid.serializer(), js))
        assertEquals(2, d.scrollbackLines.size)
        assertEquals("old1 ", d.scrollbackLines[0].text)
        assertEquals("old2 ", d.scrollbackLines[1].text)
        assertEquals("live ", d.lines[0].text)
    }

    @Test
    fun scrollbackEmptyWhenAbsent() {
        val d = RenderGridDecoder.decode(grid())
        assertTrue(d.scrollbackLines.isEmpty())
    }

    private fun decodeWithModes(modes: String) = RenderGridDecoder.decode(
        BridgeJson.decodeFromString(
            RenderGrid.serializer(),
            """{"columns":1,"rows":1,"modes":$modes}""",
        ),
    )

    @Test
    fun detectsApplicationCursorKeysFromDecckmOn() {
        assertTrue(decodeWithModes("""[{"ansi":false,"code":1,"on":true}]""").applicationCursorKeys)
    }

    @Test
    fun decckmResetMeansNormalCursorKeys() {
        assertFalse(decodeWithModes("""[{"ansi":false,"code":1,"on":false}]""").applicationCursorKeys)
    }

    @Test
    fun ansiModeOneIsNotDecckm() {
        // ANSI mode 1 is distinct from DEC-private mode 1 (DECCKM); only the latter counts.
        assertFalse(decodeWithModes("""[{"ansi":true,"code":1,"on":true}]""").applicationCursorKeys)
    }

    @Test
    fun otherDecModesDoNotEnableApplicationCursorKeys() {
        assertFalse(decodeWithModes("""[{"ansi":false,"code":25,"on":true}]""").applicationCursorKeys)
    }

    @Test
    fun noModesMeansNormalCursorKeys() {
        assertFalse(RenderGridDecoder.decode(grid()).applicationCursorKeys)
    }
}
