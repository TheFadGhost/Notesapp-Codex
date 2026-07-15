package com.fadghost.notesapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.debugInspectorInfo
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens

/**
 * The one shared press-feedback modifier (ux.md P1-1). Every clickable Aura surface —
 * buttons, FAB, nav tabs, note cards, chips, capture rows, banner/sheet actions,
 * toggles — pipes its [interactionSource] through here so a tap visibly presses in.
 *
 * Feedback is InteractionSource-driven: while the source is pressed we spring the node
 * down to [pressedScale] (~3%) over ~100ms and, when [tint] is on, wash a subtle
 * ink overlay (the theme's [ThemeElevationAlphas.pressed] alpha) across it. Both honour
 * reduce-motion — the scale collapses to an instant, so nothing animates while the
 * system (or the in-app toggle) has motion disabled.
 *
 * Placement: apply directly AFTER the element's own `.clip(shape)` (and before
 * `.background(...)`). That way the tint overlay is clipped to the same rounded shape,
 * and the scale wraps the whole visual. It replaces nothing else — the existing
 * `clickable(indication = null)` call stays, sharing the same [interactionSource].
 */
fun Modifier.auraPress(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
    tint: Boolean = false
): Modifier = composed(
    inspectorInfo = debugInspectorInfo {
        name = "auraPress"
        properties["pressedScale"] = pressedScale
        properties["tint"] = tint
    }
) {
    val reduceMotion = LocalReduceMotion.current
    val tokens = Aura.tokens
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) pressedScale else 1f,
        animationSpec = MotionTokens.press(reduceMotion),
        label = "auraPressScale"
    )
    val tintAlpha by animateFloatAsState(
        targetValue = if (pressed) tokens.elevation.pressed else 0f,
        animationSpec = MotionTokens.fast(reduceMotion),
        label = "auraPressTint"
    )

    val scaled = this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
    if (tint) {
        scaled.drawWithContent {
            drawContent()
            if (tintAlpha > 0f) drawRect(color = tokens.colors.textPrimary, alpha = tintAlpha)
        }
    } else {
        scaled
    }
}
