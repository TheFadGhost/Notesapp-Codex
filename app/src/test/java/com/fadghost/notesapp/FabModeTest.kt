package com.fadghost.notesapp

import com.fadghost.notesapp.ui.shell.FabMode
import com.fadghost.notesapp.ui.shell.NavTab
import com.fadghost.notesapp.ui.shell.fabModeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contextual-FAB mode mapping (V2-SPEC item 4). */
class FabModeTest {

    @Test fun `notes opens the capture panel`() {
        assertEquals(FabMode.CAPTURE_PANEL, fabModeFor(NavTab.NOTES))
    }

    @Test fun `diary jumps to today`() {
        assertEquals(FabMode.DIARY_TODAY, fabModeFor(NavTab.DIARY))
    }

    @Test fun `calendar starts a new event`() {
        assertEquals(FabMode.CALENDAR_NEW, fabModeFor(NavTab.CALENDAR))
    }

    @Test fun `settings has no fab`() {
        assertEquals(FabMode.HIDDEN, fabModeFor(NavTab.SETTINGS))
    }

    @Test fun `ask has no fab`() {
        assertEquals(FabMode.HIDDEN, fabModeFor(NavTab.ASK))
    }

    @Test fun `ask and settings hide the fab`() {
        assertFalse(FabMode.HIDDEN.visible)
        assertTrue(FabMode.CAPTURE_PANEL.visible)
        assertTrue(FabMode.DIARY_TODAY.visible)
        assertTrue(FabMode.CALENDAR_NEW.visible)
        assertEquals(2, NavTab.entries.count { !fabModeFor(it).visible })
    }
}
