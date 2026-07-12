package com.fadghost.notesapp.ui.shell

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Floating nav pill geometry (single source of truth for the shell + its clearance). */
val NavPillHeight: Dp = 64.dp
val NavPillBottomMargin: Dp = 16.dp

/**
 * Bottom-right capture FAB geometry + the gap to the pill. Shared so the shell can
 * reserve the FAB's slot even on tabs that hide it (keeps the pill's horizontal anchor
 * identical across tabs — P2-6) and so the responsive nav-row math below always fits.
 */
val NavFabSize: Dp = 60.dp
val NavFabGap: Dp = 12.dp

/** Nav-pill inner horizontal padding (per side) and the tab-slot size envelope. */
val NavPillHPadding: Dp = 8.dp
val NavTabSlotMax: Dp = 56.dp
val NavTabSlotMin: Dp = 46.dp
private val NavClusterSideMargin: Dp = 12.dp

/**
 * Responsive tab-slot width so the whole cluster (pill + gap + FAB) always fits, tabs
 * shrink evenly instead of a tab dropping off at narrow widths (P0-2). Verified to fit
 * from 320dp up: 320dp → 52dp slots; wide screens cap at [NavTabSlotMax].
 */
fun navTabSlotWidth(screenWidth: Dp, tabCount: Int = NavTab.entries.size): Dp {
    val available = screenWidth - NavClusterSideMargin * 2 - NavFabGap - NavFabSize - NavPillHPadding * 2
    return (available / tabCount).coerceIn(NavTabSlotMin, NavTabSlotMax)
}

/**
 * Bottom clearance every scrollable tab must reserve so its last rows (and any bottom
 * chrome — the calendar week strip, the diary empty-state, the settings tail) clear the
 * floating nav pill instead of rendering through it (systemic collision bug). The shell
 * provides the resolved value (nav inset + pill height + its margin + a breathing gap);
 * screens add it as bottom `contentPadding`. Defaults to 0 outside the shell.
 */
val LocalNavPillClearance = compositionLocalOf { 0.dp }
