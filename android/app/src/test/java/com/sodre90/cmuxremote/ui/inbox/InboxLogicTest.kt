package com.sodre90.cmuxremote.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxLogicTest {

    // The regression this pins: the Inbox used to refetch only on frames
    // flagged needsAttention, i.e. only when a NEW prompt arrived. Nothing
    // refetched when one went away, so answered prompts stayed on screen.
    // A resolved prompt's frame is a plain feed frame with no attention flag,
    // so type alone -- not the flag -- has to be what triggers the refetch.
    @Test
    fun everyFeedFrameSignalsAPossiblePendingSetChange() {
        assertTrue(isPendingSetChangeSignal("feed"))
    }

    @Test
    fun nonFeedFramesDoNotSignalAPendingSetChange() {
        assertFalse(isPendingSetChangeSignal("heartbeat"))
        assertFalse(isPendingSetChangeSignal("notification"))
        assertFalse(isPendingSetChangeSignal(""))
    }

    @Test
    fun parseToolInputEntriesReadsAFlatObjectsFields() {
        val raw = """{"command":"rm -f .git/index.lock","description":"Remove stale lock file"}"""
        assertEquals(
            listOf("command" to "rm -f .git/index.lock", "description" to "Remove stale lock file"),
            parseToolInputEntries(raw),
        )
    }

    @Test
    fun parseToolInputEntriesSkipsNestedValues() {
        val raw = """{"command":"ls","flags":["--all"],"env":{"PATH":"/bin"}}"""
        assertEquals(listOf("command" to "ls"), parseToolInputEntries(raw))
    }

    @Test
    fun parseToolInputEntriesIsEmptyForInvalidJson() {
        assertEquals(emptyList<Pair<String, String>>(), parseToolInputEntries("not json"))
    }

    @Test
    fun parseToolInputEntriesIsEmptyForNonObjectJson() {
        assertEquals(emptyList<Pair<String, String>>(), parseToolInputEntries("""["a","b"]"""))
    }

    @Test
    fun parseToolInputEntriesIsEmptyForBlankInput() {
        assertEquals(emptyList<Pair<String, String>>(), parseToolInputEntries(""))
    }
}
