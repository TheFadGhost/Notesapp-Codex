package com.fadghost.notesapp

import com.fadghost.notesapp.data.ai.parse.RambleNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RambleNoteTest {

    @Test fun liftsLeadingH1AsTitle() {
        val md = "# My Monday\n\n- Buy milk\n- Call mum"
        assertEquals("My Monday", RambleNote.titleFrom(md))
        assertEquals("- Buy milk\n- Call mum", RambleNote.bodyWithoutTitle(md))
    }

    @Test fun leadingBlankLinesBeforeTitleAreOk() {
        val md = "   \n\n# Spaced Out\nbody line"
        assertEquals("Spaced Out", RambleNote.titleFrom(md))
        assertEquals("body line", RambleNote.bodyWithoutTitle(md))
    }

    @Test fun subheadingIsNotATitle() {
        val md = "## Section\nsome text"
        assertNull(RambleNote.titleFrom(md))
        // No H1 → whole document is the body.
        assertEquals("## Section\nsome text", RambleNote.bodyWithoutTitle(md))
    }

    @Test fun hashWithoutSpaceIsNotATitle() {
        val md = "#NoSpace tag-like line\nbody"
        assertNull(RambleNote.titleFrom(md))
    }

    @Test fun plainProseHasNoTitle() {
        val md = "Just some sentences.\nA second line."
        assertNull(RambleNote.titleFrom(md))
        assertEquals("Just some sentences.\nA second line.", RambleNote.bodyWithoutTitle(md))
    }

    @Test fun titleOnlyDocumentHasEmptyBody() {
        val md = "# Only a title"
        assertEquals("Only a title", RambleNote.titleFrom(md))
        assertEquals("", RambleNote.bodyWithoutTitle(md))
    }

    @Test fun blankInputHasNoTitle() {
        assertNull(RambleNote.titleFrom("   \n  "))
    }
}
