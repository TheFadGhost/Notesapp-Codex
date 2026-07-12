package com.fadghost.notesapp.ui.shell

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Floating nav pill geometry (single source of truth for the shell + its clearance). */
val NavPillHeight: Dp = 64.dp
val NavPillBottomMargin: Dp = 16.dp

/**
 * Bottom clearance every scrollable tab must reserve so its last rows (and any bottom
 * chrome — the calendar week strip, the diary empty-state, the settings tail) clear the
 * floating nav pill instead of rendering through it (systemic collision bug). The shell
 * provides the resolved value (nav inset + pill height + its margin + a breathing gap);
 * screens add it as bottom `contentPadding`. Defaults to 0 outside the shell.
 */
val LocalNavPillClearance = compositionLocalOf { 0.dp }
