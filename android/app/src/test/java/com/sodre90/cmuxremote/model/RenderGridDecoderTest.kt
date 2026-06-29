package com.sodre90.cmuxremote.model

import org.junit.Assert.assertEquals
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
}
