@file:OptIn(ExperimentalTextApi::class)

package com.fadghost.notesapp.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fadghost.notesapp.R

/**
 * Aura typography (V2-SPEC #8, visual.md §1). Two bundled variable OFL fonts —
 * Fraunces ([AuraSerif], display/title) + Hanken Grotesk ([AuraSans], body/label) —
 * driven per-token through the `wght` axis via [FontVariation]. No platform default
 * (Roboto) ever reaches visible UI: every [AuraType] token carries a fontFamily.
 *
 * Full 8-token scale (sp / weight / line-height / tracking) exactly per visual.md §1.3.
 * Titles get negative tracking, small labels positive — optical correction, not decor.
 */
private fun serifFont(weight: Int) = Font(
    R.font.fraunces_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

private fun sansFont(weight: Int) = Font(
    R.font.hanken_grotesk_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

/** Fraunces — warm old-style display serif. Weights used: 460 / 480 / 500. */
val AuraSerif = FontFamily(serifFont(460), serifFont(480), serifFont(500))

/** Hanken Grotesk — humanist grotesque body/UI. Weights used: 400 / 420 / 520 / 560. */
val AuraSans = FontFamily(sansFont(400), sansFont(420), sansFont(520), sansFont(560))

/**
 * The type scale. Every visible `BasicText` funnels through one of these so the whole
 * app reads as one system. Call sites `.copy(color = …)` for theming but must not
 * re-override weight/size — the token owns those.
 */
object AuraType {
    /** Reserved: onboarding / What's-New hero + hero stats (streak count). */
    val display = TextStyle(
        fontFamily = AuraSerif, fontWeight = FontWeight(480),
        fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.015).em
    )

    /** Screen headers: "Notes", "Settings", "Calendar", "Diary". */
    val titleLg = TextStyle(
        fontFamily = AuraSerif, fontWeight = FontWeight(460),
        fontSize = 26.sp, lineHeight = 31.sp, letterSpacing = (-0.010).em
    )

    /** Empty-state titles, dialog titles. */
    val titleSm = TextStyle(
        fontFamily = AuraSerif, fontWeight = FontWeight(500),
        fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.006).em
    )

    /** Note title in card, primary rows. */
    val bodyLg = TextStyle(
        fontFamily = AuraSans, fontWeight = FontWeight(420),
        fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.em
    )

    /** Editor text, search field, action-row title. */
    val body = TextStyle(
        fontFamily = AuraSans, fontWeight = FontWeight(400),
        fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.002.em
    )

    /** Note preview, secondary metadata lines. */
    val bodySm = TextStyle(
        fontFamily = AuraSans, fontWeight = FontWeight(400),
        fontSize = 13.5.sp, lineHeight = 20.sp, letterSpacing = 0.004.em
    )

    /** Chips, subtitles, section labels. */
    val label = TextStyle(
        fontFamily = AuraSans, fontWeight = FontWeight(520),
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.02.em
    )

    /** Eyebrow tags / metadata (usually uppercase). */
    val labelSm = TextStyle(
        fontFamily = AuraSans, fontWeight = FontWeight(560),
        fontSize = 10.5.sp, lineHeight = 13.sp, letterSpacing = 0.06.em
    )

    /**
     * Compatibility alias = [titleLg]. The old scale had a single `title`; generic
     * dialog/sheet headers and the shell files (owned by the parallel shell agent)
     * still reference it. It carries the AuraSerif font like every token, so no
     * Roboto leaks. Prefer the explicit tokens (titleLg/titleSm) in new code.
     */
}
