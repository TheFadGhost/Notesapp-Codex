package com.fadghost.notesapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/** Which hand-drawn glyph an empty state shows (PLAN.md §10 designed empty states). */
enum class EmptyGlyph { NOTES, SEARCH, ARCHIVE, TRASH, CALENDAR, DIARY }

/**
 * Unified Aura empty state (PLAN.md §10): a hand-drawn Canvas glyph, a one-liner, and
 * an optional action chip. Replaces the ad-hoc per-screen empties so every "nothing
 * here yet" reads as one system.
 */
@Composable
fun AuraEmptyState(
    glyph: EmptyGlyph,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val tokens = Aura.tokens
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .semantics { contentDescription = "$title. $subtitle" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(Modifier.size(96.dp)) {
            // Hero icon tier: fixed 2.5 dp stroke (visual.md §3 — same pen as the
            // 1.75 dp regular glyphs, not the old 4.3 dp that read as a heavier set).
            val st = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            when (glyph) {
                EmptyGlyph.NOTES -> drawNotesGlyph(tokens.colors.accent, tokens.colors.textSecondary, st)
                EmptyGlyph.SEARCH -> drawSearchGlyph(tokens.colors.accent, tokens.colors.textSecondary, st)
                EmptyGlyph.ARCHIVE -> drawArchiveGlyph(tokens.colors.accent, tokens.colors.textSecondary, st)
                EmptyGlyph.TRASH -> drawTrashGlyph(tokens.colors.accent, tokens.colors.textSecondary, st)
                EmptyGlyph.CALENDAR -> drawCalendarGlyph(tokens.colors.accent, tokens.colors.textSecondary, st)
                EmptyGlyph.DIARY -> drawDiaryGlyph(tokens.colors.accent, tokens.colors.textSecondary, st)
            }
        }
        Spacer(Modifier.height(20.dp))
        BasicText(
            title,
            style = AuraType.titleSm.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            subtitle,
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            val actionInteraction = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .auraPress(actionInteraction, tint = true)
                    .background(tokens.colors.accent.copy(alpha = 0.14f))
                    .border(1.dp, tokens.colors.accent.copy(alpha = 0.45f), RoundedCornerShape(tokens.radii.pill))
                    .clickable(
                        interactionSource = actionInteraction,
                        indication = null,
                        onClick = onAction
                    )
                    .semantics { contentDescription = actionLabel }
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                BasicText(actionLabel, style = AuraType.label.copy(color = tokens.colors.accent))
            }
        }
    }
}

// --- glyphs: two-tone (primary = textSecondary, one accent detail) --------------
// Unified grammar (visual.md §3): 2-unit rounded corners, one pen, one accent max.

private fun cr(s: Float) = s * (2f / 24f)

private fun DrawScope.roundRectStroke(c: Color, x: Float, y: Float, w: Float, h: Float, s: Float, st: Stroke) {
    drawRoundRect(
        color = c,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cr(s), cr(s)),
        style = st
    )
}

private fun DrawScope.drawNotesGlyph(accent: Color, dim: Color, st: Stroke) {
    val s = size.minDimension
    val page = Path().apply {
        moveTo(s * 0.28f, s * 0.20f)
        lineTo(s * 0.62f, s * 0.20f)
        lineTo(s * 0.74f, s * 0.32f)
        lineTo(s * 0.74f, s * 0.80f)
        lineTo(s * 0.28f, s * 0.80f)
        close()
    }
    drawPath(page, dim, style = st)
    drawLine(dim, Offset(s * 0.36f, s * 0.44f), Offset(s * 0.66f, s * 0.44f), st.width, st.cap)
    drawLine(dim, Offset(s * 0.36f, s * 0.56f), Offset(s * 0.60f, s * 0.56f), st.width, st.cap)
    // Accent spark, sitting tangent to the page's top-right corner.
    val star = Path().apply {
        val cx = s * 0.70f; val cy = s * 0.26f; val r = s * 0.10f
        moveTo(cx, cy - r); lineTo(cx + r * 0.32f, cy - r * 0.32f); lineTo(cx + r, cy)
        lineTo(cx + r * 0.32f, cy + r * 0.32f); lineTo(cx, cy + r); lineTo(cx - r * 0.32f, cy + r * 0.32f)
        lineTo(cx - r, cy); lineTo(cx - r * 0.32f, cy - r * 0.32f); close()
    }
    drawPath(star, accent, style = st)
}

private fun DrawScope.drawSearchGlyph(accent: Color, dim: Color, st: Stroke) {
    val s = size.minDimension
    drawCircle(dim, s * 0.22f, Offset(s * 0.44f, s * 0.44f), style = st)
    drawLine(accent, Offset(s * 0.60f, s * 0.60f), Offset(s * 0.78f, s * 0.78f), st.width, st.cap)
}

private fun DrawScope.drawArchiveGlyph(accent: Color, dim: Color, st: Stroke) {
    val s = size.minDimension
    roundRectStroke(dim, s * 0.24f, s * 0.28f, s * 0.52f, s * 0.14f, s, st)
    roundRectStroke(dim, s * 0.28f, s * 0.42f, s * 0.44f, s * 0.32f, s, st)
    drawLine(accent, Offset(s * 0.42f, s * 0.54f), Offset(s * 0.58f, s * 0.54f), st.width, st.cap)
}

private fun DrawScope.drawTrashGlyph(accent: Color, dim: Color, st: Stroke) {
    val s = size.minDimension
    drawLine(dim, Offset(s * 0.28f, s * 0.32f), Offset(s * 0.72f, s * 0.32f), st.width, st.cap)
    val body = Path().apply {
        moveTo(s * 0.33f, s * 0.32f); lineTo(s * 0.37f, s * 0.76f)
        lineTo(s * 0.63f, s * 0.76f); lineTo(s * 0.67f, s * 0.32f)
    }
    drawPath(body, dim, style = st)
    drawLine(accent, Offset(s * 0.44f, s * 0.24f), Offset(s * 0.56f, s * 0.24f), st.width, st.cap)
}

private fun DrawScope.drawCalendarGlyph(accent: Color, dim: Color, st: Stroke) {
    val s = size.minDimension
    roundRectStroke(dim, s * 0.24f, s * 0.28f, s * 0.52f, s * 0.48f, s, st)
    drawLine(dim, Offset(s * 0.24f, s * 0.42f), Offset(s * 0.76f, s * 0.42f), st.width, st.cap)
    drawCircle(accent, s * 0.04f, Offset(s * 0.42f, s * 0.58f))
    drawCircle(dim, s * 0.04f, Offset(s * 0.58f, s * 0.58f))
}

private fun DrawScope.drawDiaryGlyph(accent: Color, dim: Color, st: Stroke) {
    val s = size.minDimension
    roundRectStroke(dim, s * 0.28f, s * 0.22f, s * 0.44f, s * 0.56f, s, st)
    drawLine(accent, Offset(s * 0.28f, s * 0.22f), Offset(s * 0.28f, s * 0.78f), st.width, st.cap)
    drawLine(dim, Offset(s * 0.40f, s * 0.40f), Offset(s * 0.64f, s * 0.40f), st.width, st.cap)
    drawLine(dim, Offset(s * 0.40f, s * 0.52f), Offset(s * 0.58f, s * 0.52f), st.width, st.cap)
}
