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
    fun parsesSessionWithSnakeCaseAndUnknownKeys() {
        val js = """
            {"id":"ws-1","cwd":"/Users/p/proj","title":"build","kind":"agent",
             "needs_attention":true,"extra_future_field":42}
        """.trimIndent()
        val s = BridgeJson.decodeFromString(Session.serializer(), js)
        assertEquals("ws-1", s.id)
        assertEquals("/Users/p/proj", s.cwd)
        assertEquals("agent", s.kind)
        assertTrue(s.needsAttention)
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

    @Test
    fun encodesFeedReplyWithRequestIdAndParams() {
        val params = buildJsonObject { put("decision", "approve") }
        val reply = FeedReply(kind = "permission", requestId = "req-9", params = params)
        val out = BridgeJson.encodeToString(FeedReply.serializer(), reply)
        assertTrue(out.contains("\"request_id\":\"req-9\""))
        assertTrue(out.contains("\"decision\":\"approve\""))
    }
}
