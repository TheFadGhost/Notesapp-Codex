package com.fadghost.notesapp.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Hand-drawn line icons so the visible UI never pulls in Material icon components. */
enum class AuraIcon { NOTES, DIARY, CALENDAR, SETTINGS }

@Composable
fun TabGlyph(icon: AuraIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val stroke = Stroke(width = s * 0.075f, cap = StrokeCap.Round)
        when (icon) {
            AuraIcon.NOTES -> drawNotes(color, s, stroke)
            AuraIcon.DIARY -> drawDiary(color, s, stroke)
            AuraIcon.CALENDAR -> drawCalendar(color, s, stroke)
            AuraIcon.SETTINGS -> drawSettings(color, s, stroke)
        }
    }
}

private fun DrawScope.drawNotes(color: Color, s: Float, stroke: Stroke) {
    val left = s * 0.24f
    val right = s * 0.76f
    val top = s * 0.20f
    val bottom = s * 0.80f
    drawRoundRectStroke(color, left, top, right, bottom, s * 0.08f, stroke)
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
    drawRoundRectStroke(color, left, top, right, bottom, s * 0.06f, stroke)
    drawLine(color, Offset(left + s * 0.14f, top), Offset(left + s * 0.14f, bottom), stroke.width, StrokeCap.Butt)
    // bookmark
    drawLine(color, Offset(right - s * 0.12f, top), Offset(right - s * 0.12f, top + s * 0.22f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawCalendar(color: Color, s: Float, stroke: Stroke) {
    val left = s * 0.22f
    val right = s * 0.78f
    val top = s * 0.24f
    val bottom = s * 0.80f
    drawRoundRectStroke(color, left, top, right, bottom, s * 0.06f, stroke)
    drawLine(color, Offset(left, top + s * 0.16f), Offset(right, top + s * 0.16f), stroke.width, StrokeCap.Butt)
    drawLine(color, Offset(left + s * 0.16f, top - s * 0.02f), Offset(left + s * 0.16f, top + s * 0.10f), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(right - s * 0.16f, top - s * 0.02f), Offset(right - s * 0.16f, top + s * 0.10f), stroke.width, StrokeCap.Round)
}

private fun DrawScope.drawSettings(color: Color, s: Float, stroke: Stroke) {
    // Three sliders.
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
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        style = stroke
    )
}

@Composable
fun PlusGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val c = center
        val half = s * 0.22f
        val w = s * 0.09f
        drawLine(color, Offset(c.x - half, c.y), Offset(c.x + half, c.y), w, StrokeCap.Round)
        drawLine(color, Offset(c.x, c.y - half), Offset(c.x, c.y + half), w, StrokeCap.Round)
    }
}
