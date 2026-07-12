package com.fadghost.notesapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Curated accent palette (PLAN.md §9 — "8–10 curated accents") reused as the tag
 * colour choices. Stored on [com.fadghost.notesapp.data.db.entity.Tag.color] as
 * an ARGB int; index 0 (transparent) means "no colour picked yet".
 */
object AuraAccents {
    val palette: List<Color> = listOf(
        Color(0xFFC0653E), // terracotta (brand default)
        Color(0xFFC1912F), // ochre
        Color(0xFF7E8E68), // sage
        Color(0xFF6E6A33), // olive
        Color(0xFF8C3A3A), // oxblood
        Color(0xFF46566C), // slate ink-blue (desaturated, not periwinkle)
        Color(0xFF8A7B66), // warm taupe
        Color(0xFF3F5E48), // forest
        Color(0xFF9E5A2E), // rust
        Color(0xFF6E4A5B)  // plum
    )

    /** Resolve a stored ARGB int to a display colour, falling back to [fallback]. */
    fun resolve(argb: Int, fallback: Color): Color =
        if (argb == 0) fallback else Color(argb)

    /**
     * The 8 curated accents offered in the Settings accent picker (PLAN.md §9).
     * Index [THEME_DEFAULT] (-1) means "use the theme's own accent" — i.e. no override.
     */
    val themeAccents: List<Color> = listOf(
        Color(0xFFC0653E), // terracotta (default)
        Color(0xFFC1912F), // ochre
        Color(0xFF7E8E68), // sage
        Color(0xFF6E6A33), // olive
        Color(0xFF8C3A3A), // oxblood
        Color(0xFF46566C), // slate ink-blue (desaturated, not periwinkle)
        Color(0xFF8A7B66), // warm taupe
        Color(0xFF3F5E48)  // forest
    )

    const val THEME_DEFAULT: Int = -1

    /**
     * Map a persisted accent index to a colour override, or null for "theme default".
     * Out-of-range indices clamp to null so a corrupt/stale pref never crashes.
     */
    fun accentForIndex(index: Int): Color? =
        themeAccents.getOrNull(index)
}
