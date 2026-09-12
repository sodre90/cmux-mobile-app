package com.sodre90.cmuxremote.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtosTest {

    @Test
    fun parsesWorkspaceWithInlinePanesAndUnknownKeys() {
        val js = """
            {"id":"ws-1","cwd":"/Users/p/proj","title":"build","preview":"Claude is waiting",
             "has_unread":true,"custom_color":"#6A1B9A","future_field":42,
             "terminals":[
               {"id":"t-1","cwd":"/Users/p/proj","title":"build","focused":true,"ready":true,"kind":"agent"},
               {"id":"t-2","cwd":"/Users/p/proj","title":"~/proj","focused":false,"ready":false,"kind":"terminal"}
             ]}
        """.trimIndent()
        val w = BridgeJson.decodeFromString(Workspace.serializer(), js)
        assertEquals("ws-1", w.id)
        assertEquals("/Users/p/proj", w.cwd)
        assertEquals("Claude is waiting", w.preview)
        assertTrue(w.hasUnread)
        assertEquals("#6A1B9A", w.customColor)
        assertEquals(2, w.terminals.size)
        assertEquals("t-1", w.terminals[0].id)
        assertTrue(w.terminals[0].focused)
        assertEquals("terminal", w.terminals[1].kind)
    }

    @Test
    fun parsesWorkspaceWithMissingOptionalFields() {
        val w = BridgeJson.decodeFromString(Workspace.serializer(), """{"id":"ws-2"}""")
        assertEquals("ws-2", w.id)
        assertEquals("", w.preview)
        assertFalse(w.hasUnread)
        // cmux omits custom_color for workspaces the user never colored.
        assertEquals("", w.customColor)
        assertTrue(w.terminals.isEmpty())
    }

    @Test
    fun parsesEventFrameWithMissingOptionalFields() {
        val js = """{"type":"event","name":"feed.updated","needs_attention":false}"""
        val e = BridgeJson.decodeFromString(EventFrame.serializer(), js)
        assertEquals("event", e.type)
        assertEquals("feed.updated", e.name)
        assertFalse(e.needsAttention)
        assertNull(e.feedId)
    }

    @Test
    fun parsesTerminalDownWithEmbeddedGrid() {
        val js = """
            {"type":"snapshot","columns":3,"rows":1,"seq":7,
             "grid":{"columns":3,"rows":1,"row_spans":[{"row":0,"column":0,"text":"hi"}]}}
        """.trimIndent()
        val d = BridgeJson.decodeFromString(TerminalDown.serializer(), js)
        assertEquals("snapshot", d.type)
        assertEquals(7L, d.seq)
        assertEquals("hi", d.grid?.rowSpans?.first()?.text)
    }

    @Test
    fun encodesTerminalUpInputOmittingNulls() {
        val up = TerminalUp(type = "input", text = "ls\n")
        val out = BridgeJson.encodeToString(TerminalUp.serializer(), up)
        assertTrue(out.contains("\"type\":\"input\""))
        assertTrue(out.contains("\"text\":\"ls\\n\""))
        assertFalse(out.contains("columns"))
    }

    /** The attach fields must not change what every existing frame looks
     *  like on the wire: an input still encodes exactly as it did. */
    @Test
    fun anInputFrameIsByteIdenticalToBeforeAttachExisted() {
        val out = BridgeJson.encodeToString(TerminalUp.serializer(), TerminalUp(type = "input", text = "ls", seq = 3))
        assertEquals("""{"type":"input","text":"ls","seq":3}""", out)
    }

    @Test
    fun encodesAnAttachWithItsImageAndNameHint() {
        val up = TerminalUp(type = TerminalUpType.ATTACH, seq = 4, image = "iVBORw0KGgo=", name = "IMG_2041")
        val out = BridgeJson.encodeToString(TerminalUp.serializer(), up)
        assertEquals("""{"type":"attach","seq":4,"image":"iVBORw0KGgo=","name":"IMG_2041"}""", out)
    }

    @Test
    fun encodesFeedReplyWithRequestIdAndParams() {
        val params = buildJsonObject { put("decision", "approve") }
        val reply = FeedReply(kind = "permissionRequest", requestId = "req-9", params = params)
        val out = BridgeJson.encodeToString(FeedReply.serializer(), reply)
        assertTrue(out.contains("\"request_id\":\"req-9\""))
        assertTrue(out.contains("\"decision\":\"approve\""))
    }
}
