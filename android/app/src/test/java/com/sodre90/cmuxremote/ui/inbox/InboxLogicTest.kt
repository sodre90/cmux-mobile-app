package com.sodre90.cmuxremote.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

class InboxLogicTest {

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
