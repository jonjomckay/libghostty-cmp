package io.github.xchacha20_poly1305.libghostty

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GhosttyRenderStateTest {

    private val session = GhosttyTerminalSession(GhosttyTerminalSession.Options(columns = 20, rows = 5))

    @AfterTest
    fun close() {
        session.close()
    }

    private fun write(text: String) = session.feedOutput(text.encodeToByteArray())

    private fun GhosttyRenderState.text(row: Int): String = buildString {
        for (col in 0 until columns) {
            val length = textLength(col, row)
            append(if (length == 0) ' ' else String(rowText(row), textOffset(col, row), length))
        }
    }.trimEnd()

    private fun update(state: GhosttyRenderState) = session.withTerminal { state.update(it) }!!

    @Test
    fun readsGridCursorAndColors() {
        write("\u001b[1;31mred\u001b[0m plain\r\n\u001b[4:3mwavy\u001b[0m")
        val state = GhosttyRenderState()
        assertTrue(update(state))
        assertEquals(20, state.columns)
        assertEquals(5, state.rows)
        assertEquals("red plain", state.text(0))
        assertEquals("wavy", state.text(1))
        // Bold base colors are drawn bright.
        assertEquals(GhosttyCellFlags.BOLD, state.flags(0, 0) and GhosttyCellFlags.BOLD)
        assertTrue(state.foreground(0, 0) != state.defaultForeground)
        assertEquals(state.defaultForeground, state.foreground(4, 0))
        assertEquals(GhosttyCellFlags.UNDERLINE_CURLY, state.underline(0, 1))
        assertEquals(4, state.cursorColumn)
        assertEquals(1, state.cursorRow)
        assertEquals(GhosttyCursorStyle.BLOCK, state.cursorStyle)
        assertTrue(state.cursorVisible)
        assertFalse(update(state), "nothing changed")
    }

    @Test
    fun graphemeClusterJoinsZwjSequenceIntoOneCell() {
        val family = "👨‍👩‍👧"
        val clustering = GhosttyTerminalSession(
            GhosttyTerminalSession.Options(columns = 20, rows = 5, defaultGraphemeCluster = true),
        )
        try {
            clustering.feedOutput("${family}x".encodeToByteArray())
            val state = GhosttyRenderState()
            assertTrue(clustering.withTerminal { state.update(it) }!!)
            assertEquals(family, String(state.rowText(0), state.textOffset(0, 0), state.textLength(0, 0)))
            assertEquals(GhosttyCellFlags.WIDE, state.flags(0, 0) and GhosttyCellFlags.WIDE)
            assertEquals("x", String(state.rowText(0), state.textOffset(2, 0), state.textLength(2, 0)))
        } finally {
            clustering.close()
        }
    }

    @Test
    fun newStateReadsTheWholeScreen() {
        write("first\r\nsecond")
        assertTrue(update(GhosttyRenderState()))
        // The terminal reported every row to the first state; a second one still sees them all.
        val state = GhosttyRenderState()
        assertTrue(update(state))
        assertEquals("first", state.text(0))
        assertEquals("second", state.text(1))
    }

    @Test
    fun selectionInvertsColors() {
        write("hello world")
        val state = GhosttyRenderState()
        update(state)
        session.withTerminal { assertTrue(it.selectWord(1, 0)) }
        update(state)
        assertEquals(0..4, state.selection(0))
        assertNull(state.selection(1))
        assertEquals(state.defaultBackground, state.foreground(0, 0))
        assertEquals(state.defaultForeground, state.background(0, 0))
        assertEquals(state.defaultForeground, state.foreground(6, 0))
    }

    @Test
    fun wideCharactersAndScrollback() {
        write("中文\r\n")
        repeat(10) { write("line $it\r\n") }
        val state = GhosttyRenderState()
        update(state)
        assertTrue(state.scrollbar.total > state.scrollbar.length)
        assertTrue(state.scrollbar.isAtBottom)
        session.withTerminal { it.scroll(-100) }
        update(state)
        assertEquals("中文", state.text(0).replace(" ", ""))
        assertEquals(GhosttyCellFlags.WIDE, state.flags(0, 0) and GhosttyCellFlags.WIDE)
        assertEquals(GhosttyCellFlags.SPACER, state.flags(1, 0) and GhosttyCellFlags.SPACER)
        assertFalse(state.scrollbar.isAtBottom)
    }

    @Test
    fun largeScreenGrowsTheBuffer() {
        val big = GhosttyTerminalSession(GhosttyTerminalSession.Options(columns = 300, rows = 100))
        try {
            big.feedOutput("x".repeat(30_000).encodeToByteArray())
            val state = GhosttyRenderState()
            assertTrue(big.withTerminal { state.update(it) }!!)
            assertEquals(100, state.rows)
            assertEquals("x".repeat(300), String(state.rowText(50), 0, 300))
        } finally {
            big.close()
        }
    }

    @Test
    fun backgroundsCoverImagesEvenWhenTheyMatchTheDefaultColor() {
        write("a\u001b[48;2;0;0;0mb\u001b[0m\u001b[7mc\u001b[0md")
        val state = GhosttyRenderState()
        update(state)
        assertEquals(state.defaultBackground, state.background(1, 0))
        assertFalse(state.hasBackground(0, 0))
        assertTrue(state.hasBackground(1, 0), "explicit default-colored background")
        assertTrue(state.hasBackground(2, 0), "inverse text")
        assertFalse(state.hasBackground(3, 0))
        session.withTerminal { assertTrue(it.select(0, 0, 0, 0)) }
        update(state)
        assertTrue(state.hasBackground(0, 0), "selected cell")
    }

    private fun kitty(command: String, data: String = "") = write("\u001b_G$command;$data\u001b\\")

    private fun prepareImages() {
        session.resize(20, 5, 10, 20)
    }

    private fun image(id: Int, z: Int = 0) =
        kitty("a=T,f=24,s=1,v=1,i=$id,p=1,c=1,r=1,C=1,z=$z,q=2", "////")

    @Test
    fun kittyPlacementsHaveAllThreeLayersWithoutDuplicates() {
        prepareImages()
        image(3)
        image(1, Int.MIN_VALUE)
        image(2, -1)
        val state = GhosttyRenderState()
        assertTrue(update(state))
        assertEquals(listOf(1, 2, 3), state.kittyPlacements.map { it.imageId })
        assertEquals(GhosttyImageLayer.entries, state.kittyPlacements.map { it.layer })
        assertFalse(update(state), "unchanged image list")

        // A new renderer must receive the list even after an earlier one consumed the generation change.
        val next = GhosttyRenderState()
        assertTrue(update(next))
        assertEquals(state.kittyPlacements, next.kittyPlacements)
        val placements = state.kittyPlacements
        write("text")
        assertTrue(update(state))
        assertSame(placements, state.kittyPlacements, "text changes reuse the unchanged image list")

        kitty("a=d,d=I,i=2,q=2")
        assertTrue(update(next), "another renderer can observe the same change first")
        assertTrue(update(state), "deleting an image updates the list without changing text")
        assertEquals(listOf(1, 3), state.kittyPlacements.map { it.imageId })
        assertFalse(update(state))
    }

    @Test
    fun kittyPixelsAndScreenSwitchesInvalidateTheImageList() {
        prepareImages()
        image(1)
        val state = GhosttyRenderState()
        update(state)
        val original = state.kittyPlacements.single()
        kitty("a=T,f=24,s=1,v=1,i=1,p=1,c=1,r=1,C=1,q=2", "AAAA")
        assertTrue(update(state))
        val replacement = state.kittyPlacements.single()
        assertNotEquals(original.imageGeneration, replacement.imageGeneration)
        session.withTerminal {
            assertEquals(0xFF000000.toInt(), it.kittyImage(1)!!.pixels.single())
        }
        write("\u001b[?1049h")
        assertTrue(update(state))
        assertTrue(state.kittyPlacements.isEmpty())
        write("\u001b[?1049l")
        assertTrue(update(state))
        assertEquals(listOf(replacement), state.kittyPlacements)
        kitty("a=d,d=I,i=1,q=2")
        assertTrue(update(state))
        assertTrue(state.kittyPlacements.isEmpty(), "deleting the last image clears the cached list")
    }

    @Test
    fun kittyPlacementsFollowScrollAndResize() {
        prepareImages()
        write("\u001b[3;4H")
        image(1)
        val state = GhosttyRenderState()
        update(state)
        assertEquals(3, state.kittyPlacements.single().column)
        assertEquals(2, state.kittyPlacements.single().row)
        write("\u001b[5;1H\r\n")
        assertTrue(update(state))
        assertEquals(1, state.kittyPlacements.single().row)
        session.withTerminal { it.scroll(-1) }
        assertTrue(update(state))
        assertEquals(2, state.kittyPlacements.single().row)
        session.resize(20, 5, 15, 25)
        assertTrue(update(state))
        assertEquals(15, state.kittyPlacements.single().width)
        assertEquals(25, state.kittyPlacements.single().height)
    }

    @Test
    fun kittyVirtualAndRelativePlacementsShareTheRenderList() {
        prepareImages()
        kitty("a=T,f=24,s=1,v=1,i=1,p=1,U=1,c=1,r=1,q=2", "////")
        // The placeholder's foreground and underline colors encode image and placement IDs.
        write("\u001b[2;3H\u001b[38;5;1;58;5;1m\uDBFB\uDEEE\u0305\u0305\u001b[0m")
        kitty("a=p,i=1,p=2,P=1,Q=1,H=1,V=1,c=1,r=1,z=5,q=2")
        val state = GhosttyRenderState()
        assertTrue(update(state))
        assertEquals(2, state.kittyPlacements.size)
        val (virtual, relative) = state.kittyPlacements
        assertEquals(GhosttyImageLayer.BELOW_TEXT, virtual.layer)
        assertEquals(2, virtual.column)
        assertEquals(1, virtual.row)
        assertEquals(GhosttyImageLayer.ABOVE_TEXT, relative.layer)
        assertEquals(3, relative.column)
        assertEquals(2, relative.row)
        write("\u001b[2;3Hx")
        assertTrue(update(state))
        assertTrue(state.kittyPlacements.isEmpty(), "overwriting the virtual anchor removes both placements")
    }

    @Test
    fun themeColorsReachTheState() {
        val state = GhosttyRenderState()
        update(state)
        session.setTheme(
            GhosttyTheme(0xFFD0D0D0.toInt(), 0xFF212121.toInt(), null, null, null, List(256) { null }),
        )
        assertTrue(update(state))
        assertEquals(0xFF212121.toInt(), state.defaultBackground)
        assertEquals(0xFFD0D0D0.toInt(), state.defaultForeground)
    }
}
