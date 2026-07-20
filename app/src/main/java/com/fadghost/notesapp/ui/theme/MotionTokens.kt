package com.fadghost.notesapp.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Central spring/tween vocabulary (PLAN.md §10). Every custom surface pulls its
 * motion from here instead of hand-rolling `spring()` params, so the whole app
 * shares one feel and the reduce-motion pass has a single choke point.
 *
 * Each preset has a spring form (normal) and a fast-tween/snap form (reduce-motion).
 * Callers pick via [spec] passing the ambient [LocalReduceMotion] value.
 */
object MotionTokens {

    /** Snappy, minimal overshoot — menus, chips, selection highlights. */
    fun <T> fast(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) tween(90)
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Balanced default — content swaps, sheet settle, card transitions. */
    fun <T> medium(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) tween(120)
        else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

    /** Playful overshoot — nav tab pop, capture sheet spring-up. */
    fun <T> bouncy(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) tween(120)
        else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

    /** Finite variants for transition APIs (AnimatedVisibility/AnimatedContent). */
    fun <T> fastFinite(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else tween(160)

    fun <T> mediumFinite(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else tween(240)

    /** Springy finite transition for bottom sheets and pop-in surfaces. */
    fun <T> bouncyFinite(reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) snap()
        else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

    // --- v2.0.0 shell motion (see council/motion.md) --------------------------------

    /**
     * M3 "emphasized decelerate" — strong ease-out for the shared-axis tab slide
     * (motion.md §3). Never ease-in, never linear on content transitions.
     */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /**
     * Nav bubble travel (motion.md §1.3 Channel A): near-critically-damped so it
     * gets to the new tab fast and basically stops — the sway is a separate channel.
     * Reduce-motion: a 100ms linear slide (keeps the "which tab" read, no bounce).
     */
    fun navTravel(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) tween(100, easing = LinearEasing)
        else spring(dampingRatio = 0.9f, stiffness = 550f, visibilityThreshold = 0.5f)

    /**
     * Nav bubble arrival sway (motion.md §1.3 Channel B): a fixed-amplitude decaying
     * oscillation seeded with a velocity impulse — the sideways trampoline rebound.
     */
    val NavSway: SpringSpec<Float> = spring(dampingRatio = 0.4f, stiffness = 700f)

    /**
     * Capture panel scale (motion.md §2.4): the ONLY channel allowed to overshoot
     * (~+3%, absorbed by the card's internal padding — no clipped edge possible).
     */
    val PanelScale: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 420f)

    /** Capture panel rise/alpha: critically damped, cannot overshoot position. */
    val PanelRise: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 500f)

    /** Per-item stagger rise inside the capture panel (motion.md §2.7). */
    val PanelItem: SpringSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 600f)

    /** Compact rebound for FAB/button press and release; quick without feeling linear. */
    fun <T> press(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) snap()
        else spring(dampingRatio = 0.62f, stiffness = 650f)

    /** Critically-damped settle for bounds-anchored surfaces (never overshoots). */
    fun <T> settle(reduceMotion: Boolean): AnimationSpec<T> =
        if (reduceMotion) tween(120)
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    // --- Named finite timings (council audit: no magic numbers at call sites) -------
    /** Shared-axis tab transition (AppShell). Values preserved from the tuned v2 shell. */
    const val TabSlideInMs = 220
    const val TabSlideOutMs = 200
    const val TabFadeInMs = 180
    const val TabFadeOutMs = 140
    const val TabReducedFadeMs = 120

    /** Theme morph + circular reveal (ThemeSwitch). */
    const val ThemeMorphMs = 320
    const val ThemeRevealMs = 420
    const val ThemeRevealFadeMs = 120

    /** Bottom-sheet scrim/drop idiom shared by the voice and What's-new sheets. */
    const val SheetScrimInMs = 220
    const val SheetScrimOutMs = 160
    const val SheetDropMs = 200
}

/**
 * Ambient "reduce motion" flag (PLAN.md §10). True when the system animator
 * duration scale is 0 OR the in-app "Reduce motion" toggle is on. Defaults false.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }
