package com.fadghost.notesapp.ui.diary

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import java.time.LocalDate

/**
 * GitHub-style contribution heat-map (PLAN.md §7). A custom Compose/Canvas grid —
 * weeks as columns, weekdays as rows — with the accent colour ramped by entry
 * intensity. Tapping a past/today cell opens that day. Future cells in the current
 * week are drawn faintly and are not tappable.
 */
@Composable
fun DiaryHeatMap(
    cells: List<List<HeatCell>>,
    today: LocalDate,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.97f else 1f,
        animationSpec = tween(if (reduceMotion) 0 else 100),
        label = "heatPressScale"
    )
    if (cells.isEmpty()) return
    val weeks = cells.size
    val gapDp = 3.dp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val gapPx = with(density) { gapDp.toPx() }
        // Size each square so the whole grid fills the available width.
        val cell = ((totalWidthPx - gapPx * (weeks - 1)) / weeks).coerceAtLeast(1f)
        val step = cell + gapPx
        val heightPx = cell * 7 + gapPx * 6
        val heightDp = with(density) { heightPx.toDp() }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .pointerInput(cells, step) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = onTap@{ pos ->
                            val col = (pos.x / step).toInt().coerceIn(0, weeks - 1)
                            val row = (pos.y / step).toInt().coerceIn(0, 6)
                            val hc = cells.getOrNull(col)?.getOrNull(row) ?: return@onTap
                            if (!hc.date.isAfter(today)) onOpenDay(hc.date)
                        }
                    )
                }
        ) {
            cells.forEachIndexed { colIdx, column ->
                column.forEachIndexed { rowIdx, hc ->
                    val x = colIdx * step
                    val y = rowIdx * step
                    val future = hc.date.isAfter(today)
                    val fill = when {
                        future -> tokens.colors.outline.copy(alpha = 0.25f)
                        hc.level == 0 -> tokens.colors.textSecondary.copy(alpha = 0.12f)
                        else -> tokens.colors.accent.copy(alpha = levelAlpha(hc.level))
                    }
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(x, y),
                        size = Size(cell, cell),
                        cornerRadius = CornerRadius(cell * 0.22f, cell * 0.22f)
                    )
                }
            }
        }
    }
}

/** Legend: "Less [ ][ ][ ][ ][ ] More" using the same ramp. */
@Composable
fun HeatMapLegend(modifier: Modifier = Modifier) {
    val tokens = Aura.tokens
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BasicText("Less", style = AuraType.label.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.width(2.dp))
        (0..4).forEach { level ->
            androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                val fill = if (level == 0) tokens.colors.textSecondary.copy(alpha = 0.12f)
                else tokens.colors.accent.copy(alpha = levelAlpha(level))
                drawRoundRect(fill, cornerRadius = CornerRadius(size.minDimension * 0.22f))
            }
        }
        Spacer(Modifier.width(2.dp))
        BasicText("More", style = AuraType.label.copy(color = tokens.colors.textSecondary))
    }
}

private fun levelAlpha(level: Int): Float = when (level) {
    1 -> 0.30f
    2 -> 0.50f
    3 -> 0.72f
    else -> 0.95f
}
