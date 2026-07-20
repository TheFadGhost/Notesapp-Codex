package com.fadghost.notesapp.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import kotlin.math.hypot

/**
 * Process-wide relay for the theme-switch reveal origin (PLAN.md §9 — animated
 * circular reveal). The Settings swatch that was tapped records its centre here as a
 * fraction of the screen (0..1) so the reveal expands from under the user's finger.
 * Null == reveal from screen centre.
 */
object ThemeSwitchController {
    var origin: Offset? = null
        private set

    fun setOrigin(fractionX: Float, fractionY: Float) {
        origin = Offset(fractionX.coerceIn(0f, 1f), fractionY.coerceIn(0f, 1f))
    }
}

/**
 * Smoothly interpolate every token colour from the previously-shown set to [target]
 * whenever the theme changes. Under reduce-motion the morph collapses to a snap.
 * This is the always-on core of the animated theme switch; the circular reveal in
 * [ThemeRevealScaffold] rides on top for extra flair.
 */
@Composable
fun rememberAnimatedTokens(target: ThemeTokens, reduceMotion: Boolean): State<ThemeTokens> {
    val duration = if (reduceMotion) 0 else MotionTokens.ThemeMorphMs
    val c = target.colors
    val background by animateColorSafe(c.background, duration)
    val surface by animateColorSafe(c.surface, duration)
    val surfaceTranslucent by animateColorSafe(c.surfaceTranslucent, duration)
    val textPrimary by animateColorSafe(c.textPrimary, duration)
    val textSecondary by animateColorSafe(c.textSecondary, duration)
    val accent by animateColorSafe(c.accent, duration)
    val danger by animateColorSafe(c.danger, duration)
    val outline by animateColorSafe(c.outline, duration)

    return remember(
        background, surface, surfaceTranslucent, textPrimary,
        textSecondary, accent, danger, outline, target
    ) {
        mutableStateOf(
            target.copy(
                colors = c.copy(
                    background = background,
                    surface = surface,
                    surfaceTranslucent = surfaceTranslucent,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    accent = accent,
                    danger = danger,
                    outline = outline
                )
            )
        )
    }
}

@Composable
private fun animateColorSafe(target: Color, durationMs: Int): State<Color> =
    androidx.compose.animation.animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMs),
        label = "themeColor"
    )

/**
 * Circular-reveal overlay (PLAN.md §9): when [revealKey] changes, expand a disc of
 * the incoming background colour from [ThemeSwitchController.origin] over the content,
 * then fade it out — the morphing tokens underneath are fully settled by the time the
 * disc clears, so there is no flicker. Under [reduceMotion] the reveal is skipped
 * entirely (a plain crossfade of the morphing tokens remains).
 */
@Composable
fun ThemeRevealScaffold(
    revealKey: Any,
    revealColor: Color,
    reduceMotion: Boolean,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        content()

        if (!reduceMotion) {
            val radius = remember(revealKey) { Animatable(0f) }
            val alpha = remember(revealKey) { Animatable(1f) }
            var firstComposition by remember { mutableStateOf(true) }

            LaunchedEffect(revealKey) {
                if (firstComposition) {
                    firstComposition = false
                    return@LaunchedEffect
                }
                radius.snapTo(0f)
                alpha.snapTo(1f)
                radius.animateTo(1f, tween(MotionTokens.ThemeRevealMs))
                alpha.animateTo(0f, tween(MotionTokens.ThemeRevealFadeMs))
            }

            if (alpha.value > 0.001f && !firstComposition) {
                val origin = ThemeSwitchController.origin
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            val cx = (origin?.x ?: 0.5f) * size.width
                            val cy = (origin?.y ?: 0.5f) * size.height
                            val maxR = hypot(
                                maxOf(cx, size.width - cx).toDouble(),
                                maxOf(cy, size.height - cy).toDouble()
                            ).toFloat()
                            drawCircle(
                                color = revealColor,
                                radius = radius.value * maxR,
                                center = Offset(cx, cy),
                                alpha = alpha.value
                            )
                        }
                )
            }
        }
    }
}
