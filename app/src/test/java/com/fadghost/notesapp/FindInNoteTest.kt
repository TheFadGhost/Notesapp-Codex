package com.fadghost.notesapp

import com.fadghost.notesapp.ui.editor.FindInNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit coverage for the pure find-in-note matcher (IDEAS #15). */
class FindInNoteTest {

    @Test fun matches_findsAllOccurrences() {
        val m = FindInNote.matches("the cat and the hat", "the")
        assertEquals(listOf(0..2, 12..14), m)
    }

    @Test fun matches_isCaseInsensitive() {
        val m = FindInNote.matches("Milk MILK milk", "milk")
        assertEquals(3, m.size)
        assertEquals(0..3, m[0])
        assertEquals(5..8, m[1])
        assertEquals(10..13, m[2])
    }

    @Test fun matches_blankQueryOrText_returnsEmpty() {
        assertTrue(FindInNote.matches("some text", "").isEmpty())
        assertTrue(FindInNote.matches("some text", "   ").isEmpty())
        assertTrue(FindInNote.matches("", "x").isEmpty())
    }

    @Test fun matches_trimsQueryWhitespace() {
        val m = FindInNote.matches("call mum", " mum ")
        assertEquals(listOf(5..7), m)
    }

    @Test fun matches_noOverlappingMatches() {
        // "aaa" in "aaaa" matches at 0 only (next search starts after the match).
        val m = FindInNote.matches("aaaa", "aaa")
        assertEquals(listOf(0..2), m)
    }

    @Test fun matches_unicodeText() {
        val m = FindInNote.matches("café visit — café again", "café")
        assertEquals(2, m.size)
    }

    @Test fun wrapIndex_cyclesBothDirections() {
        assertEquals(0, FindInNote.wrapIndex(3, 3))   // next past the end wraps to 0
        assertEquals(2, FindInNote.wrapIndex(-1, 3))  // prev before the start wraps to last
        assertEquals(1, FindInNote.wrapIndex(1, 3))
    }

    @Test fun wrapIndex_zeroCount_isMinusOne() {
        assertEquals(-1, FindInNote.wrapIndex(0, 0))
    }
}
