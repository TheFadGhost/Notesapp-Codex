package com.fadghost.notesapp.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * "Aura" design tokens. Every custom component consumes these — there is no
 * MaterialTheme dependence in visible UI (PLAN.md §3/§9). Retrofitting later is
 * painful, so the full token surface exists from M0.
 */
@Immutable
data class ThemeColors(
    val background: Color,
    val surface: Color,
    /** Frosted/translucent surface for nav pill, sheets, menus. */
    val surfaceTranslucent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val danger: Color,
    /** Hairline stroke used on translucent surfaces. */
    val outline: Color
)

@Immutable
data class ThemeRadii(
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val pill: Dp = 999.dp
)

@Immutable
data class ThemeBlur(
    /** Blur radius for the nav pill (kept small per PLAN.md §10 blur budget). */
    val navPill: Dp = 24.dp,
    val sheet: Dp = 18.dp
)

@Immutable
data class ThemeElevationAlphas(
    /** Alpha of the tint layer behind translucent surfaces. */
    val translucentTint: Float,
    val scrim: Float,
    val pressed: Float
)

/**
 * A single soft, tinted paper shadow (V2-SPEC #9, visual.md §2). [color] is already
 * alpha-baked and is a warm ink pull, never `#000` on paper themes. Paper does not
 * throw hard black shadows — it throws low-contrast warm ones.
 */
@Immutable
data class ThemeShadow(
    val color: Color,
    val blur: Dp,
    val y: Dp,
    val spread: Dp = 0.dp
)

/**
 * The 3-plane depth model. Ground (screen bg) casts nothing. [sheet] = cards, search
 * bar, section cards (soft contact shadow). [float] = nav pill, capture popup, dialogs
 * (ambient lift shadow, paired with [ThemeTokens.innerHighlight]).
 */
@Immutable
data class ThemeShadows(
    val sheet: ThemeShadow,
    val float: ThemeShadow
)

@Immutable
data class ThemeTokens(
    val colors: ThemeColors,
    val radii: ThemeRadii = ThemeRadii(),
    val blur: ThemeBlur = ThemeBlur(),
    val elevation: ThemeElevationAlphas,
    val shadows: ThemeShadows,
    /**
     * A 1-dp top-edge highlight for Float surfaces — the paper analogue of a glass
     * bezel (a light source from above catching the sheet's top edge). Carries most of
     * the lift on dark themes where a drop shadow reads faintly (visual.md §2.3).
     */
    val innerHighlight: Color
)

/**
 * Light — warm "paper & ink" (PLAN.md §9). Cream paper background, a brighter fresh
 * sheet for cards, deep charcoal ink text, terracotta accent. No blue-tinted neutrals.
 * Accent is tuned dark enough to clear 4.5:1 on the paper for text-sized use.
 */
val LightTokens = ThemeTokens(
    colors = ThemeColors(
        background = Color(0xFFF7F1E6),
        surface = Color(0xFFFDFAF2),
        surfaceTranslucent = Color(0xCCFDFAF2),
        textPrimary = Color(0xFF23272E),
        textSecondary = Color(0xFF6A6152),
        accent = Color(0xFFAD5430),
        danger = Color(0xFFB3261E),
        outline = Color(0x1A23272E)
    ),
    elevation = ThemeElevationAlphas(
        translucentTint = 0.72f,
        scrim = 0.32f,
        pressed = 0.10f
    ),
    // Warm espresso shadow (#3A2E1F), never grey/black — it must belong to paper.
    shadows = ThemeShadows(
        sheet = ThemeShadow(Color(0x1A3A2E1F), blur = 10.dp, y = 3.dp),
        float = ThemeShadow(Color(0x243A2E1F), blur = 22.dp, y = 8.dp)
    ),
    innerHighlight = Color(0x0F23272E) // textPrimary @ ~6%
)

/**
 * Dark — warm charcoal ink (PLAN.md §9), not blue-black. Espresso-charcoal surfaces,
 * cream text, warmer/brighter terracotta accent so it glows on the dark ground.
 * Hairlines are warm cream at low alpha rather than cold white.
 */
val DarkTokens = ThemeTokens(
    colors = ThemeColors(
        background = Color(0xFF1C1A17),
        surface = Color(0xFF26231E),
        surfaceTranslucent = Color(0xB326231E),
        textPrimary = Color(0xFFF2E7D5),
        textSecondary = Color(0xFFADA491),
        accent = Color(0xFFD2764E),
        danger = Color(0xFFEF6B60),
        outline = Color(0x22F2E7D5)
    ),
    elevation = ThemeElevationAlphas(
        translucentTint = 0.60f,
        scrim = 0.48f,
        pressed = 0.14f
    ),
    // On dark, a light shadow reads wrong — lean on near-black-but-warm ambient +
    // the inner top highlight (below) for the lift signal.
    shadows = ThemeShadows(
        sheet = ThemeShadow(Color(0x47000000), blur = 12.dp, y = 4.dp),
        float = ThemeShadow(Color(0x66000000), blur = 26.dp, y = 10.dp)
    ),
    innerHighlight = Color(0x14F2E7D5) // textPrimary @ ~8%
)

/**
 * Pure Black AMOLED (PLAN.md §9): true #000 backgrounds so OLED pixels power off,
 * with warm near-black surfaces lifted just enough to read as cards. Translucent
 * surfaces stay near-opaque warm black so the frosted pill/sheet never washes out.
 */
val AmoledTokens = ThemeTokens(
    colors = ThemeColors(
        background = Color(0xFF000000),
        surface = Color(0xFF141210),
        surfaceTranslucent = Color(0xE6141210),
        textPrimary = Color(0xFFF5ECDD),
        textSecondary = Color(0xFF9E968A),
        accent = Color(0xFFD67C52),
        danger = Color(0xFFF0655A),
        outline = Color(0x2EF2E7D5)
    ),
    elevation = ThemeElevationAlphas(
        translucentTint = 0.66f,
        scrim = 0.58f,
        pressed = 0.16f
    ),
    shadows = ThemeShadows(
        sheet = ThemeShadow(Color(0x73000000), blur = 10.dp, y = 3.dp),
        float = ThemeShadow(Color(0x8C000000), blur = 22.dp, y = 8.dp)
    ),
    innerHighlight = Color(0x14F5ECDD) // textPrimary @ ~8%
)

/**
 * Grey / graphite with a warm undertone (PLAN.md §9): a genuine mid graphite, clearly
 * lighter than Dark's espresso-charcoal (RGB 28,26,23) so the two themes read as
 * distinct — background is RGB 60,56,51 (still warm, R>G>B), softer than AMOLED's black.
 */
val GreyTokens = ThemeTokens(
    colors = ThemeColors(
        background = Color(0xFF3C3833),
        surface = Color(0xFF4A453F),
        surfaceTranslucent = Color(0xB34A453F),
        textPrimary = Color(0xFFECE5D9),
        textSecondary = Color(0xFFB4AB9A),
        accent = Color(0xFFD2764E),
        danger = Color(0xFFEE6F62),
        outline = Color(0x24F2E7D5)
    ),
    elevation = ThemeElevationAlphas(
        translucentTint = 0.62f,
        scrim = 0.50f,
        pressed = 0.14f
    ),
    shadows = ThemeShadows(
        sheet = ThemeShadow(Color(0x3D000000), blur = 12.dp, y = 4.dp),
        float = ThemeShadow(Color(0x5C000000), blur = 24.dp, y = 9.dp)
    ),
    innerHighlight = Color(0x14ECE5D9) // textPrimary @ ~8%
)

/**
 * Return a copy of these tokens with the accent colour overridden (accent picker,
 * PLAN.md §9). Passing null (the "theme default" sentinel) leaves the accent as-is.
 */
fun ThemeTokens.withAccent(accent: Color?): ThemeTokens =
    if (accent == null) this else copy(colors = colors.copy(accent = accent))
