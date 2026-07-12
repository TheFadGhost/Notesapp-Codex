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
/** Comfortable tab width; also the with-FAB threshold below which the FAB is dropped. */
val NavTabSlotMin: Dp = 44.dp
/**
 * Absolute floor a tab slot may shrink to before the FAB is dropped. Low enough that even
 * a ~122dp effective width (320px on a 420dpi / 2.625× device — the `wm size 320x640`
 * repro) still seats four equal, on-screen, tappable tabs instead of clipping the last
 * tab and the FAB off the right edge (P0-2, the real width-math bug).
 */
private val NavTabSlotFloor: Dp = 18.dp
private val NavClusterSideMargin: Dp = 12.dp

/**
 * The screen is wide enough to seat the capture FAB beside the pill AND still give every
 * tab at least a comfortable [NavTabSlotMin]. Below this the FAB's slot is NOT reserved,
 * so the four tabs keep equal, tappable widths at ultra-narrow widths (task: the FAB is
 * "intentionally + cleanly handled at ultra-narrow width"). Width-only so the pill's
 * horizontal anchor is identical on every tab at a given width (P2-6).
 */
fun navShowFab(screenWidth: Dp, tabCount: Int = NavTab.entries.size): Boolean {
    val available = screenWidth - NavClusterSideMargin * 2 - NavFabGap - NavFabSize - NavPillHPadding * 2
    return available / tabCount >= NavTabSlotMin
}

/**
 * Responsive tab-slot width. All four tabs ALWAYS fit the ACTUAL screen width — they
 * shrink evenly, never clip, never drop (P0-2). When [reserveFab] the FAB column stays in
 * the budget (wide screens); the shell passes [navShowFab] so the pill anchor matches on
 * every tab. Coerced to [NavTabSlotFloor]..[NavTabSlotMax]; the floor is small enough that
 * a ~122dp width still fits without overflow. Examples: 411dp → 56dp (capped, FAB on);
 * true 320dp → 52dp (FAB on); ~122dp → ~20dp (FAB off, all four still on-screen).
 */
fun navTabSlotWidth(
    screenWidth: Dp,
    reserveFab: Boolean = navShowFab(screenWidth),
    tabCount: Int = NavTab.entries.size
): Dp {
    val fabSpace = if (reserveFab) NavFabGap + NavFabSize else 0.dp
    val available = screenWidth - NavClusterSideMargin * 2 - NavPillHPadding * 2 - fabSpace
    return (available / tabCount).coerceIn(NavTabSlotFloor, NavTabSlotMax)
}

/**
 * Bottom clearance every scrollable tab must reserve so its last rows (and any bottom
 * chrome — the calendar week strip, the diary empty-state, the settings tail) clear the
 * floating nav pill instead of rendering through it (systemic collision bug). The shell
 * provides the resolved value (nav inset + pill height + its margin + a breathing gap);
 * screens add it as bottom `contentPadding`. Defaults to 0 outside the shell.
 */
val LocalNavPillClearance = compositionLocalOf { 0.dp }
