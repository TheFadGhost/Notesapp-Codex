package com.fadghost.notesapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn line glyphs for note actions and the editor toolbar. Keeps the visible
 * UI free of Material icon components (PLAN.md §3).
 *
 * Unified Aura icon grammar (V2-SPEC #10, visual.md §3): 24-unit grid, StrokeCap/Join
 * .Round ALWAYS, 2-unit corner radius on every rectangle (no hard `drawRect`), and a
 * stroke clamped to a fixed dp per tier — regular = 1.75 dp, heavy = 2.5 dp (bold /
 * heading emphasis only). One pen across every size.
 */
enum class Glyph {
    PIN, ARCHIVE, TRASH, DUPLICATE, FOLDER, TAG, SEARCH, CLOSE, GRID, LIST,
    BOLD, ITALIC, HEADING, CHECKLIST, BULLET, UNDO, REDO, RESTORE, CHECK, PLUS,
    CHEVRON, BACK, MORE, SPARKLE, CALENDAR, CLOCK, CHEVRON_UP, CHEVRON_DOWN,
    DOCUMENT, BOOK, MIC
}

@Composable
fun AuraGlyph(glyph: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = size.minDimension
        val st = Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val heavy = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            Glyph.PIN -> drawPin(color, s, st)
            Glyph.ARCHIVE -> drawArchive(color, s, st)
            Glyph.TRASH -> drawTrash(color, s, st)
            Glyph.DUPLICATE -> drawDuplicate(color, s, st)
            Glyph.FOLDER -> drawFolder(color, s, st)
            Glyph.TAG -> drawTag(color, s, st)
            Glyph.SEARCH -> drawSearch(color, s, st)
            Glyph.CLOSE -> drawClose(color, s, st)
            Glyph.GRID -> drawGrid(color, s, st)
            Glyph.LIST -> drawList(color, s, st)
            Glyph.BOLD -> drawBold(color, s, heavy)
            Glyph.ITALIC -> drawItalic(color, s, st)
            Glyph.HEADING -> drawHeading(color, s, heavy, st)
            Glyph.CHECKLIST -> drawChecklist(color, s, st)
            Glyph.BULLET -> drawBullet(color, s, st)
            Glyph.UNDO -> drawUndo(color, s, st, mirror = false)
            Glyph.REDO -> drawUndo(color, s, st, mirror = true)
            Glyph.RESTORE -> drawUndo(color, s, st, mirror = false)
            Glyph.CHECK -> drawCheck(color, s, st)
            Glyph.PLUS -> drawPlus(color, s, st)
            Glyph.CHEVRON -> drawChevron(color, s, st, back = false)
            Glyph.BACK -> drawChevron(color, s, st, back = true)
            Glyph.MORE -> drawMore(color, s)
            Glyph.SPARKLE -> drawSparkle(color, s, st)
            Glyph.CALENDAR -> drawCalendar(color, s, st)
            Glyph.CLOCK -> drawClock(color, s, st)
            Glyph.CHEVRON_UP -> drawChevronVert(color, s, st, up = true)
            Glyph.CHEVRON_DOWN -> drawChevronVert(color, s, st, up = false)
            Glyph.DOCUMENT -> drawDocument(color, s, st)
            Glyph.BOOK -> drawBook(color, s, st)
            Glyph.MIC -> drawMic(color, s, st)
        }
    }
}

/** A page with a folded corner + a couple of text lines (capture: New note). */
private fun DrawScope.drawDocument(c: Color, s: Float, st: Stroke) {
    val page = Path().apply {
        moveTo(s * 0.30f, s * 0.22f)
        lineTo(s * 0.58f, s * 0.22f)
        lineTo(s * 0.70f, s * 0.34f)
        lineTo(s * 0.70f, s * 0.78f)
        lineTo(s * 0.30f, s * 0.78f)
        close()
    }
    drawPath(page, c, style = st)
    // Folded corner.
    val fold = Path().apply {
        moveTo(s * 0.58f, s * 0.22f)
        lineTo(s * 0.58f, s * 0.34f)
        lineTo(s * 0.70f, s * 0.34f)
    }
    drawPath(fold, c, style = st)
    line(c, s * 0.38f, s * 0.52f, s * 0.62f, s * 0.52f, st)
    line(c, s * 0.38f, s * 0.64f, s * 0.62f, s * 0.64f, st)
}

/** A closed book with a spine (capture: New diary entry). */
private fun DrawScope.drawBook(c: Color, s: Float, st: Stroke) {
    roundRect(c, s * 0.28f, s * 0.22f, s * 0.44f, s * 0.56f, s, st)
    line(c, s * 0.40f, s * 0.24f, s * 0.40f, s * 0.76f, st)
    line(c, s * 0.50f, s * 0.34f, s * 0.64f, s * 0.34f, st)
    line(c, s * 0.50f, s * 0.46f, s * 0.64f, s * 0.46f, st)
}

/** A capsule mic + stand (capture: Voice ramble). */
private fun DrawScope.drawMic(c: Color, s: Float, st: Stroke) {
    roundRect(c, s * 0.40f, s * 0.22f, s * 0.20f, s * 0.34f, s, st)
    // Cradle arc.
    val cradle = Path().apply {
        moveTo(s * 0.32f, s * 0.48f)
        cubicTo(s * 0.32f, s * 0.66f, s * 0.68f, s * 0.66f, s * 0.68f, s * 0.48f)
    }
    drawPath(cradle, c, style = st)
    line(c, s * 0.50f, s * 0.66f, s * 0.50f, s * 0.78f, st)
    line(c, s * 0.40f, s * 0.78f, s * 0.60f, s * 0.78f, st)
}

/** 2-unit corner radius on the 24-unit grid. */
private fun cr(s: Float) = s * (2f / 24f)

/** Stroked rounded rect — the only rectangle primitive (no sharp `drawRect`). */
private fun DrawScope.roundRect(c: Color, x: Float, y: Float, w: Float, h: Float, s: Float, st: Stroke) {
    drawRoundRect(
        color = c,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(cr(s), cr(s)),
        style = st
    )
}

private fun DrawScope.drawSparkle(c: Color, s: Float, st: Stroke) {
    // Clean 8-point star (matches drawNotesGlyph's spark) + a small companion.
    fun star(cx: Float, cy: Float, r: Float) {
        val p = Path().apply {
            moveTo(cx, cy - r)
            lineTo(cx + r * 0.32f, cy - r * 0.32f)
            lineTo(cx + r, cy)
            lineTo(cx + r * 0.32f, cy + r * 0.32f)
            lineTo(cx, cy + r)
            lineTo(cx - r * 0.32f, cy + r * 0.32f)
            lineTo(cx - r, cy)
            lineTo(cx - r * 0.32f, cy - r * 0.32f)
            close()
        }
        drawPath(p, c, style = st)
    }
    star(s * 0.44f, s * 0.44f, s * 0.22f)
    star(s * 0.72f, s * 0.70f, s * 0.10f)
}

private fun DrawScope.drawCalendar(c: Color, s: Float, st: Stroke) {
    roundRect(c, s * 0.24f, s * 0.28f, s * 0.52f, s * 0.48f, s, st)
    line(c, s * 0.24f, s * 0.40f, s * 0.76f, s * 0.40f, st)
    line(c, s * 0.36f, s * 0.24f, s * 0.36f, s * 0.32f, st)
    line(c, s * 0.64f, s * 0.24f, s * 0.64f, s * 0.32f, st)
    drawCircle(c, s * 0.03f, Offset(s * 0.40f, s * 0.54f))
    drawCircle(c, s * 0.03f, Offset(s * 0.56f, s * 0.54f))
}

private fun DrawScope.drawClock(c: Color, s: Float, st: Stroke) {
    drawCircle(c, s * 0.26f, Offset(s * 0.5f, s * 0.5f), style = st)
    line(c, s * 0.5f, s * 0.5f, s * 0.5f, s * 0.32f, st)
    line(c, s * 0.5f, s * 0.5f, s * 0.64f, s * 0.56f, st)
}

private fun DrawScope.drawChevronVert(c: Color, s: Float, st: Stroke, up: Boolean) {
    if (up) {
        line(c, s * 0.34f, s * 0.58f, s * 0.5f, s * 0.42f, st)
        line(c, s * 0.5f, s * 0.42f, s * 0.66f, s * 0.58f, st)
    } else {
        line(c, s * 0.34f, s * 0.42f, s * 0.5f, s * 0.58f, st)
        line(c, s * 0.5f, s * 0.58f, s * 0.66f, s * 0.42f, st)
    }
}

private fun DrawScope.line(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, st: Stroke) =
    drawLine(c, Offset(x1, y1), Offset(x2, y2), st.width, StrokeCap.Round)

private fun DrawScope.drawPin(c: Color, s: Float, st: Stroke) {
    drawCircle(c, s * 0.16f, Offset(s * 0.5f, s * 0.36f), style = st)
    line(c, s * 0.5f, s * 0.52f, s * 0.5f, s * 0.82f, st)
}

private fun DrawScope.drawArchive(c: Color, s: Float, st: Stroke) {
    roundRect(c, s * 0.22f, s * 0.24f, s * 0.56f, s * 0.16f, s, st)
    roundRect(c, s * 0.26f, s * 0.40f, s * 0.48f, s * 0.34f, s, st)
    line(c, s * 0.42f, s * 0.52f, s * 0.58f, s * 0.52f, st)
}

private fun DrawScope.drawTrash(c: Color, s: Float, st: Stroke) {
    line(c, s * 0.28f, s * 0.30f, s * 0.72f, s * 0.30f, st)
    line(c, s * 0.42f, s * 0.30f, s * 0.44f, s * 0.22f, st)
    line(c, s * 0.56f, s * 0.22f, s * 0.58f, s * 0.30f, st)
    val body = Path().apply {
        moveTo(s * 0.33f, s * 0.30f)
        lineTo(s * 0.37f, s * 0.78f)
        lineTo(s * 0.63f, s * 0.78f)
        lineTo(s * 0.67f, s * 0.30f)
    }
    drawPath(body, c, style = st)
    line(c, s * 0.45f, s * 0.40f, s * 0.46f, s * 0.68f, st)
    line(c, s * 0.55f, s * 0.40f, s * 0.54f, s * 0.68f, st)
}

private fun DrawScope.drawDuplicate(c: Color, s: Float, st: Stroke) {
    roundRect(c, s * 0.24f, s * 0.24f, s * 0.36f, s * 0.36f, s, st)
    roundRect(c, s * 0.40f, s * 0.40f, s * 0.36f, s * 0.36f, s, st)
}

private fun DrawScope.drawFolder(c: Color, s: Float, st: Stroke) {
    val p = Path().apply {
        moveTo(s * 0.22f, s * 0.34f)
        lineTo(s * 0.44f, s * 0.34f)
        lineTo(s * 0.50f, s * 0.42f)
        lineTo(s * 0.78f, s * 0.42f)
        lineTo(s * 0.78f, s * 0.72f)
        lineTo(s * 0.22f, s * 0.72f)
        close()
    }
    drawPath(p, c, style = st)
}

private fun DrawScope.drawTag(c: Color, s: Float, st: Stroke) {
    val p = Path().apply {
        moveTo(s * 0.28f, s * 0.28f)
        lineTo(s * 0.52f, s * 0.28f)
        lineTo(s * 0.74f, s * 0.50f)
        lineTo(s * 0.52f, s * 0.72f)
        lineTo(s * 0.28f, s * 0.48f)
        close()
    }
    drawPath(p, c, style = st)
    drawCircle(c, s * 0.035f, Offset(s * 0.40f, s * 0.40f))
}

private fun DrawScope.drawSearch(c: Color, s: Float, st: Stroke) {
    drawCircle(c, s * 0.20f, Offset(s * 0.44f, s * 0.44f), style = st)
    line(c, s * 0.58f, s * 0.58f, s * 0.74f, s * 0.74f, st)
}

private fun DrawScope.drawClose(c: Color, s: Float, st: Stroke) {
    line(c, s * 0.32f, s * 0.32f, s * 0.68f, s * 0.68f, st)
    line(c, s * 0.68f, s * 0.32f, s * 0.32f, s * 0.68f, st)
}

private fun DrawScope.drawGrid(c: Color, s: Float, st: Stroke) {
    roundRect(c, s * 0.24f, s * 0.24f, s * 0.20f, s * 0.20f, s, st)
    roundRect(c, s * 0.56f, s * 0.24f, s * 0.20f, s * 0.20f, s, st)
    roundRect(c, s * 0.24f, s * 0.56f, s * 0.20f, s * 0.20f, s, st)
    roundRect(c, s * 0.56f, s * 0.56f, s * 0.20f, s * 0.20f, s, st)
}

private fun DrawScope.drawList(c: Color, s: Float, st: Stroke) {
    for (i in 0..2) {
        val y = s * (0.32f + i * 0.18f)
        drawCircle(c, s * 0.03f, Offset(s * 0.30f, y))
        line(c, s * 0.40f, y, s * 0.74f, y, st)
    }
}

private fun DrawScope.drawBold(c: Color, s: Float, heavy: Stroke) {
    line(c, s * 0.38f, s * 0.28f, s * 0.38f, s * 0.72f, heavy)
    line(c, s * 0.38f, s * 0.30f, s * 0.58f, s * 0.30f, heavy)
    line(c, s * 0.58f, s * 0.30f, s * 0.58f, s * 0.48f, heavy)
    line(c, s * 0.38f, s * 0.50f, s * 0.60f, s * 0.50f, heavy)
    line(c, s * 0.60f, s * 0.50f, s * 0.60f, s * 0.70f, heavy)
    line(c, s * 0.38f, s * 0.70f, s * 0.60f, s * 0.70f, heavy)
}

private fun DrawScope.drawItalic(c: Color, s: Float, st: Stroke) {
    line(c, s * 0.44f, s * 0.30f, s * 0.62f, s * 0.30f, st)
    line(c, s * 0.38f, s * 0.70f, s * 0.56f, s * 0.70f, st)
    line(c, s * 0.56f, s * 0.30f, s * 0.44f, s * 0.70f, st)
}

private fun DrawScope.drawHeading(c: Color, s: Float, heavy: Stroke, st: Stroke) {
    line(c, s * 0.32f, s * 0.30f, s * 0.32f, s * 0.70f, heavy)
    line(c, s * 0.56f, s * 0.30f, s * 0.56f, s * 0.70f, heavy)
    line(c, s * 0.32f, s * 0.50f, s * 0.56f, s * 0.50f, heavy)
    line(c, s * 0.66f, s * 0.44f, s * 0.66f, s * 0.70f, st)
}

private fun DrawScope.drawChecklist(c: Color, s: Float, st: Stroke) {
    roundRect(c, s * 0.24f, s * 0.30f, s * 0.16f, s * 0.16f, s, st)
    val tick = Path().apply {
        moveTo(s * 0.26f, s * 0.38f); lineTo(s * 0.31f, s * 0.43f); lineTo(s * 0.40f, s * 0.30f)
    }
    drawPath(tick, c, style = st)
    line(c, s * 0.50f, s * 0.38f, s * 0.76f, s * 0.38f, st)
    line(c, s * 0.50f, s * 0.62f, s * 0.76f, s * 0.62f, st)
    roundRect(c, s * 0.24f, s * 0.54f, s * 0.16f, s * 0.16f, s, st)
}

private fun DrawScope.drawBullet(c: Color, s: Float, st: Stroke) {
    for (i in 0..1) {
        val y = s * (0.38f + i * 0.24f)
        drawCircle(c, s * 0.045f, Offset(s * 0.30f, y))
        line(c, s * 0.42f, y, s * 0.74f, y, st)
    }
}

private fun DrawScope.drawUndo(c: Color, s: Float, st: Stroke, mirror: Boolean) {
    val arc = Path().apply {
        if (!mirror) {
            moveTo(s * 0.34f, s * 0.40f)
            cubicTo(s * 0.34f, s * 0.68f, s * 0.68f, s * 0.72f, s * 0.72f, s * 0.56f)
            moveTo(s * 0.34f, s * 0.40f); lineTo(s * 0.28f, s * 0.30f)
            moveTo(s * 0.34f, s * 0.40f); lineTo(s * 0.46f, s * 0.38f)
        } else {
            moveTo(s * 0.66f, s * 0.40f)
            cubicTo(s * 0.66f, s * 0.68f, s * 0.32f, s * 0.72f, s * 0.28f, s * 0.56f)
            moveTo(s * 0.66f, s * 0.40f); lineTo(s * 0.72f, s * 0.30f)
            moveTo(s * 0.66f, s * 0.40f); lineTo(s * 0.54f, s * 0.38f)
        }
    }
    drawPath(arc, c, style = st)
}

private fun DrawScope.drawCheck(c: Color, s: Float, st: Stroke) {
    val p = Path().apply {
        moveTo(s * 0.28f, s * 0.52f); lineTo(s * 0.44f, s * 0.68f); lineTo(s * 0.74f, s * 0.32f)
    }
    drawPath(p, c, style = st)
}

private fun DrawScope.drawPlus(c: Color, s: Float, st: Stroke) {
    line(c, s * 0.30f, s * 0.50f, s * 0.70f, s * 0.50f, st)
    line(c, s * 0.50f, s * 0.30f, s * 0.50f, s * 0.70f, st)
}

private fun DrawScope.drawChevron(c: Color, s: Float, st: Stroke, back: Boolean) {
    if (back) {
        line(c, s * 0.58f, s * 0.32f, s * 0.40f, s * 0.50f, st)
        line(c, s * 0.40f, s * 0.50f, s * 0.58f, s * 0.68f, st)
    } else {
        line(c, s * 0.44f, s * 0.32f, s * 0.62f, s * 0.50f, st)
        line(c, s * 0.62f, s * 0.50f, s * 0.44f, s * 0.68f, st)
    }
}

private fun DrawScope.drawMore(c: Color, s: Float) {
    for (i in 0..2) drawCircle(c, s * 0.045f, Offset(s * (0.32f + i * 0.18f), s * 0.5f))
}
