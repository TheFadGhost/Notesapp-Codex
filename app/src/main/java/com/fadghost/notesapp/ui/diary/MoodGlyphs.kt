package com.fadghost.notesapp.ui.diary

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.LocalReduceMotion

/**
 * Hand-drawn mood faces (PLAN.md §7 — "custom mood glyphs, no Material icons").
 * One [Canvas], switched on the [Mood]; a circle outline with eyes and a mouth
 * curve whose curvature tracks the mood, so the five read as a clear scale.
 */
@Composable
fun MoodGlyph(mood: Mood, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        // Regular icon tier (visual.md §3): fixed 1.75 dp pen, Round cap + join.
        val st = Stroke(
            width = 1.75.dp.toPx(),
            cap = StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round
        )
        // Face ring.
        drawCircle(color, radius = s * 0.42f, center = Offset(s / 2f, s / 2f), style = st)
        // Eyes.
        drawCircle(color, radius = s * 0.045f, center = Offset(s * 0.37f, s * 0.42f))
        drawCircle(color, radius = s * 0.045f, center = Offset(s * 0.63f, s * 0.42f))
        // Mouth: curvature -1 (frown) .. +1 (smile), scaled by mood.
        val curve = when (mood) {
            Mood.AWFUL -> -0.9f
            Mood.LOW -> -0.45f
            Mood.OKAY -> 0f
            Mood.GOOD -> 0.5f
            Mood.GREAT -> 0.95f
        }
        drawMouth(color, s, curve, st)
    }
}

private fun DrawScope.drawMouth(c: Color, s: Float, curve: Float, st: Stroke) {
    val left = s * 0.36f
    val right = s * 0.64f
    val baseY = s * 0.62f
    if (curve == 0f) {
        drawLine(c, Offset(left, baseY), Offset(right, baseY), st.width, st.cap)
        return
    }
    // Control point pulls the mouth up (smile) or down (frown).
    val ctrlY = baseY - curve * s * 0.20f
    val path = Path().apply {
        moveTo(left, baseY - curve * s * 0.03f)
        quadraticTo(s * 0.5f, ctrlY, right, baseY - curve * s * 0.03f)
    }
    drawPath(path, c, style = st)
}

/**
 * Row of five tappable mood faces. [selected] highlights the current mood; tapping
 * the already-selected face clears it (emits null). Springs the chosen face.
 */
@Composable
fun MoodPicker(
    selected: Mood?,
    onSelect: (Mood?) -> Unit,
    modifier: Modifier = Modifier,
    glyphSize: androidx.compose.ui.unit.Dp = 34.dp
) {
    val tokens = Aura.tokens
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Mood.entries.forEach { mood ->
            val isSel = mood == selected
            val scale by animateFloatAsState(
                if (isSel) 1.12f else 1f,
                MotionTokens.bouncy(LocalReduceMotion.current),
                label = "mood-scale"
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(maxOf(glyphSize + 14.dp, 48.dp))
                    .clip(CircleShape)
                    .auraPress(interaction)
                    .background(if (isSel) tokens.colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(if (isSel) null else mood) }
                    )
                    .semantics {
                        this.selected = isSel
                        contentDescription = "Mood: ${mood.label}"
                    },
                contentAlignment = Alignment.Center
            ) {
                MoodGlyph(
                    mood = mood,
                    color = if (isSel) tokens.colors.accent else tokens.colors.textSecondary,
                    modifier = Modifier.size(glyphSize).scale(scale)
                )
            }
        }
    }
}

/** Small static face used on timeline cards (no interaction). */
@Composable
fun MoodBadge(mood: Mood, modifier: Modifier = Modifier) {
    val tokens = Aura.tokens
    MoodGlyph(
        mood = mood,
        color = tokens.colors.accent,
        modifier = modifier.semantics { contentDescription = "Mood: ${mood.label}" }
    )
}
