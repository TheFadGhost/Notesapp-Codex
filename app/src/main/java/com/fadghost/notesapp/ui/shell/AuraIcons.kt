package com.fadghost.notesapp.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn nav line icons — visible UI never pulls in Material icon components.
 *
 * Unified Aura icon grammar (V2-SPEC #10, visual.md §3): authored on a 24-unit grid
 * with a 20-unit live area, StrokeCap/Join.Round ALWAYS (no Butt), 2-unit corner
 * radius on every rectangle, and a stroke clamped to a fixed dp per size tier
 * (regular = 1.75 dp) so a 24-dp and a 96-dp glyph look drawn by one pen.
 */
enum class AuraIcon { NOTES, DIARY, CALENDAR, ASK, SETTINGS }

/** Regular icon-tier stroke: fixed 1.75 dp regardless of the box size. */
private fun DrawScope.regularStroke() =
    Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
fun TabGlyph(icon: AuraIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val stroke = regularStroke()
        when (icon) {
            AuraIcon.NOTES -> drawNotes(color, s, stroke)
            AuraIcon.DIARY -> drawDiary(color, s, stroke)
            AuraIcon.CALENDAR -> drawCalendar(color, s, stroke)
            AuraIcon.ASK -> drawAsk(color, s, stroke)
            AuraIcon.SETTINGS -> drawSettings(color, s, stroke)
        }
    }
}

/** 2-unit corner radius on the 24-unit grid. */
private fun radius(s: Float) = s * (2f / 24f)

private fun DrawScope.drawNotes(color: Color, s: Float, stroke: Stroke) {
    val left = s * 0.24f
    val right = s * 0.76f
    val top = s * 0.20f
    val bottom = s * 0.80f
    drawRoundRectStroke(color, left, top, right, bottom, radius(s), stroke)
    for (i in 0..2) {
        val y = top + s * 0.16f + i * s * 0.14f
        drawLine(color, Offset(left + s * 0.10f, y), Offset(right - s * 0.10f, y), stroke.width, StrokeCap.Round)
    }
}

private fun DrawScope.drawDiary(color: Color, s: Float, stroke: Stroke) {
    val left = s * 0.26f
    val right = s * 0.74f
    val top = s * 0.20f
    val bottom = s * 0.80f
    drawRoundRectStroke(color, left, top, right, bottom, radius(s), stroke)
    // Spine + bookmark — Round caps, consistent with the grammar (no Butt).
    drawLine(color, Offset(left + s * 0.14f, top + s * 0.06f), Offset(left + s * 0.14f, bottom - s * 0.06f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(right - s * 0.12f, top), Offset(right - s * 0.12f, top + s * 0.22f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawCalendar(color: Color, s: Float, stroke: Stroke) {
    val left = s * 0.22f
    val right = s * 0.78f
    val top = s * 0.24f
    val bottom = s * 0.80f
    drawRoundRectStroke(color, left, top, right, bottom, radius(s), stroke)
    drawLine(color, Offset(left + s * 0.04f, top + s * 0.16f), Offset(right - s * 0.04f, top + s * 0.16f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(left + s * 0.16f, top - s * 0.02f), Offset(left + s * 0.16f, top + s * 0.10f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(right - s * 0.16f, top - s * 0.02f), Offset(right - s * 0.16f, top + s * 0.10f), stroke.width, StrokeCap.Round)
}

/** Three hand-drawn sparkles: Folio's Ask mark without a Material icon dependency. */
private fun DrawScope.drawAsk(color: Color, s: Float, stroke: Stroke) {
    fun sparkle(cx: Float, cy: Float, radius: Float) {
        drawLine(
            color,
            Offset(s * cx, s * (cy - radius)),
            Offset(s * cx, s * (cy + radius)),
            stroke.width,
            StrokeCap.Round
        )
        drawLine(
            color,
            Offset(s * (cx - radius), s * cy),
            Offset(s * (cx + radius), s * cy),
            stroke.width,
            StrokeCap.Round
        )
    }
    sparkle(cx = 0.44f, cy = 0.50f, radius = 0.20f)
    sparkle(cx = 0.72f, cy = 0.29f, radius = 0.075f)
    sparkle(cx = 0.72f, cy = 0.70f, radius = 0.06f)
}

private fun DrawScope.drawSettings(color: Color, s: Float, stroke: Stroke) {
    // Three sliders with knobs.
    val left = s * 0.24f
    val right = s * 0.76f
    val rows = listOf(0.32f, 0.50f, 0.68f)
    val knobs = listOf(0.62f, 0.38f, 0.56f)
    rows.forEachIndexed { i, ry ->
        val y = s * ry
        drawLine(color, Offset(left, y), Offset(right, y), stroke.width, StrokeCap.Round)
        val kx = left + (right - left) * knobs[i]
        drawCircle(color, s * 0.055f, Offset(kx, y))
    }
}

private fun DrawScope.drawRoundRectStroke(
    color: Color,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
    stroke: Stroke
) {
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(radius, radius),
        style = stroke
    )
}

@Composable
fun PlusGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val c = center
        val half = s * 0.22f
        val stroke = regularStroke()
        drawLine(color, Offset(c.x - half, c.y), Offset(c.x + half, c.y), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(c.x, c.y - half), Offset(c.x, c.y + half), stroke.width, StrokeCap.Round)
    }
}
