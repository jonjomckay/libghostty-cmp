package io.github.xchacha20_poly1305.libghostty.demo

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalTypeTest {
    private val candidates = listOf("xterm-ghostty", "xterm-256color", "xterm", "vt100", "dumb")

    @Test
    fun selectsFirstInstalledEntryOnEitherPlatform() {
        for (windows in listOf(false, true)) {
            for (firstInstalled in candidates.indices) {
                val visited = mutableListOf<String>()
                val selected = TerminalType.detect(windows) { name ->
                    visited += name
                    candidates.indexOf(name) >= firstInstalled
                }
                assertEquals(candidates[firstInstalled], selected)
                assertEquals(candidates.take(firstInstalled + 1), visited)
            }
        }
    }

    @Test
    fun fallsBackWhenAllEntriesAreMissing() {
        assertEquals("dumb", TerminalType.detect(false) { false })
        assertEquals("xterm-256color", TerminalType.detect(true) { false })
    }

    @Test
    fun stopsProbingWhenToolIsUnavailableOrTimesOut() {
        for (windows in listOf(false, true)) {
            val visited = mutableListOf<String>()
            val selected = TerminalType.detect(windows) { name ->
                visited += name
                if (name == "xterm-ghostty") false else null
            }
            assertEquals(if (windows) "xterm-256color" else "dumb", selected)
            assertEquals(listOf("xterm-ghostty", "xterm-256color"), visited)
        }
    }
}
