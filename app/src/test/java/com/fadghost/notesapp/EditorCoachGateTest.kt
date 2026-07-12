package com.fadghost.notesapp

import com.fadghost.notesapp.ui.editor.EditorCoachGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorCoachGateTest {

    @Test fun `shows on first open when unseen`() {
        assertTrue(EditorCoachGate.shouldShow(seen = false))
    }

    @Test fun `never shows again once seen`() {
        assertFalse(EditorCoachGate.shouldShow(seen = true))
    }
}
