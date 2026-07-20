package com.fadghost.notesapp

import com.fadghost.notesapp.ui.whatsnew.ChangelogGate
import com.fadghost.notesapp.ui.whatsnew.ChangelogLine
import com.fadghost.notesapp.ui.whatsnew.parseChangelog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {

    @Test fun `shows once per version change`() {
        assertTrue(ChangelogGate.shouldShow(lastSeen = "0.9", current = "1.0"))
    }

    @Test fun `fresh install is welcomed, not changelogged`() {
        // Blank lastSeen = first launch — the WelcomeSheet owns that moment; the
        // What's-New sheet would be noise about a past the user never saw.
        assertFalse(ChangelogGate.shouldShow(lastSeen = "", current = "1.0"))
    }

    @Test fun `does not reshow same version`() {
        assertFalse(ChangelogGate.shouldShow(lastSeen = "1.0", current = "1.0"))
    }

    @Test fun `blank current never shows`() {
        assertFalse(ChangelogGate.shouldShow(lastSeen = "1.0", current = ""))
        assertFalse(ChangelogGate.shouldShow(lastSeen = "", current = ""))
    }

    @Test fun `parser classifies markdown-ish lines`() {
        val parsed = parseChangelog(
            """
            # What's new

            ## v1.0
            - First bullet
            Some body text
            """.trimIndent()
        )
        assertEquals(4, parsed.size)
        assertEquals(ChangelogLine.Kind.TITLE, parsed[0].kind)
        assertEquals("What's new", parsed[0].text)
        assertEquals(ChangelogLine.Kind.SECTION, parsed[1].kind)
        assertEquals("v1.0", parsed[1].text)
        assertEquals(ChangelogLine.Kind.BULLET, parsed[2].kind)
        assertEquals("First bullet", parsed[2].text)
        assertEquals(ChangelogLine.Kind.BODY, parsed[3].kind)
    }

    @Test fun `parser drops blank lines`() {
        assertTrue(parseChangelog("\n\n   \n").isEmpty())
    }
}
