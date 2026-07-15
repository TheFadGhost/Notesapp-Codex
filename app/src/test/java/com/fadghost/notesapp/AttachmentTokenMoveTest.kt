package com.fadghost.notesapp

import com.fadghost.notesapp.ui.attach.AttachmentTokenMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentTokenMoveTest {
    @Test fun touchSlopSeparatesClickFromDrag() {
        assertFalse(AttachmentTokenMove.passedTouchSlop(2f, 2f, 8f))
        assertTrue(AttachmentTokenMove.passedTouchSlop(6f, 6f, 8f))
    }

    @Test fun movesTokenWithoutDuplicatingIt() {
        val moved = AttachmentTokenMove.move("Photo [[att:7]] then text", 7, 0)
        assertEquals("[[att:7]] Photo then text", moved)
        assertEquals(1, Regex("\\[\\[att:7]]").findAll(moved).count())
    }

    @Test fun missingTokenLeavesTextUntouched() {
        assertEquals("hello", AttachmentTokenMove.move("hello", 4, 2))
    }
}
