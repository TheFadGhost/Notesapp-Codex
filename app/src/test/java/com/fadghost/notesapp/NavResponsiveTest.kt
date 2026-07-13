package com.fadghost.notesapp

import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.shell.NavFabGap
import com.fadghost.notesapp.ui.shell.NavFabSize
import com.fadghost.notesapp.ui.shell.NavPillHPadding
import com.fadghost.notesapp.ui.shell.NavTab
import com.fadghost.notesapp.ui.shell.NavTabSlotMax
import com.fadghost.notesapp.ui.shell.navShowFab
import com.fadghost.notesapp.ui.shell.navTabSlotWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responsive floating-nav geometry (P0-2). The catastrophic failure was at ≈122dp
 * effective width (`wm size 320x640` on a 2.625× device): a hard 46dp slot MIN forced a
 * 200dp pill that overflowed the screen, clipping the last tab and the FAB off the right
 * edge. These assert the cluster (pill + optional FAB) ALWAYS fits and all tabs stay
 * equal from the narrowest real repro up to wide screens.
 */
class NavResponsiveTest {

    private val tabs = NavTab.entries.size

    /** pill + (gap + FAB when reserved) never exceeds the screen — no tab is ever clipped. */
    private fun assertFits(screenDp: Int) {
        val w = screenDp.dp
        val reserveFab = navShowFab(w)
        val slot = navTabSlotWidth(w, reserveFab)
        val pill = NavPillHPadding * 2 + slot * tabs
        val cluster = pill + if (reserveFab) NavFabGap + NavFabSize else 0.dp
        assertTrue(
            "cluster $cluster must fit screen $w (reserveFab=$reserveFab, slot=$slot)",
            cluster.value <= screenDp.toFloat() + 0.5f
        )
        // Slot never exceeds the design cap, and stays positive (equal, tappable tabs).
        assertTrue("slot $slot > 0", slot.value > 0f)
        assertTrue("slot $slot <= max", slot.value <= NavTabSlotMax.value + 0.01f)
    }

    @Test fun `fits at the 320px repro width (approx 122dp)`() = assertFits(122)

    @Test fun `fits across the width range`() {
        listOf(122, 140, 160, 200, 240, 280, 320, 360, 411, 480, 600, 840).forEach(::assertFits)
    }

    @Test fun `fab dropped at ultra-narrow, kept at normal widths`() {
        assertFalse("no FAB room at 122dp", navShowFab(122.dp))
        assertFalse("five tabs take priority over the FAB at 320dp", navShowFab(320.dp))
        assertTrue("FAB seats at normal 411dp", navShowFab(411.dp))
    }

    @Test fun `wide screens cap the slot at the max envelope`() {
        assertEquals(NavTabSlotMax.value, navTabSlotWidth(840.dp).value, 0.01f)
    }
}
