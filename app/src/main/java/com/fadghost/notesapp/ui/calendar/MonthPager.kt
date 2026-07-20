package com.fadghost.notesapp.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val WEEKDAY_LABELS = listOf("M", "T", "W", "T", "F", "S", "S")

/**
 * Custom month view (PLAN.md §8): header with month label + jump-to-today,
 * weekday strip, and a springy horizontal month-swipe grid. Day cells carry up to
 * three dots for that day's events/reminders; the selected day gets a filled
 * accent disc, today a hollow ring. No Material components — grid is hand-laid.
 */
@Composable
fun MonthSection(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    dotsByDay: Map<LocalDate, List<CalendarItem>>,
    onMonthDelta: (Int) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onJumpToday: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    Column(modifier.fillMaxWidth()) {
        // Header.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicText(
                "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                style = AuraType.titleLg.copy(color = tokens.colors.textPrimary)
            )
            Spacer(Modifier.weight(1f))
            TodayButton(onJumpToday)
            Spacer(Modifier.size(4.dp))
            IconTap(Glyph.BACK, label = "Previous month") { onMonthDelta(-1) }
            Spacer(Modifier.width(8.dp))
            IconTap(Glyph.CHEVRON, label = "Next month") { onMonthDelta(1) }
        }

        // Weekday header (Mon-first, UK).
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            WEEKDAY_LABELS.forEach { d ->
                BasicText(
                    d,
                    style = AuraType.label.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        val reduceMotion = LocalReduceMotion.current
        // Springy month grid — slides horizontally on month change, and a horizontal
        // fling/drag steps the month.
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val forward = targetState.isAfter(initialState)
                val dir = if (forward) 1 else -1
                val rm = reduceMotion
                (slideInHorizontally(MotionTokens.mediumFinite(rm)) { w -> dir * w } + fadeIn(MotionTokens.fastFinite(rm)))
                    .togetherWith(slideOutHorizontally(MotionTokens.mediumFinite(rm)) { w -> -dir * w } + fadeOut(MotionTokens.fastFinite(rm)))
            },
            label = "month"
        ) { m ->
            MonthGrid(
                month = m,
                selected = selected,
                today = today,
                dotsByDay = dotsByDay,
                onSelect = onSelect,
                modifier = Modifier.pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (total > 60f) onMonthDelta(-1) else if (total < -60f) onMonthDelta(1)
                            total = 0f
                        },
                        onHorizontalDrag = { _, dragAmount -> total += dragAmount }
                    )
                }
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    dotsByDay: Map<LocalDate, List<CalendarItem>>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val first = month.atDay(1)
    val leading = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val daysInMonth = month.lengthOfMonth()
    val totalCells = ((leading + daysInMonth + 6) / 7) * 7

    Column(modifier.fillMaxWidth()) {
        var cell = 0
        while (cell < totalCells) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNum = cell - leading + 1
                    if (dayNum in 1..daysInMonth) {
                        val date = month.atDay(dayNum)
                        DayCell(
                            date = date,
                            isToday = date == today,
                            isSelected = date == selected,
                            items = dotsByDay[date].orEmpty(),
                            onClick = { onSelect(date) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    cell++
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    items: List<CalendarItem>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    val discColor by animateColorAsState(
        if (isSelected) tokens.colors.accent else Color.Transparent,
        MotionTokens.bouncy(LocalReduceMotion.current),
        label = "disc"
    )
    val numberColor = when {
        isSelected -> tokens.colors.background
        isToday -> tokens.colors.accent
        else -> tokens.colors.textPrimary
    }
    val dayLabel = buildString {
        append(date.dayOfMonth)
        if (isToday) append(", today")
        val count = items.size
        if (count > 0) append(", $count ${if (count == 1) "item" else "items"}")
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .aspectRatio(1f)
            .padding(3.dp)
            .clip(CircleShape)
            .auraPress(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                selected = isSelected
                contentDescription = dayLabel
            },
        contentAlignment = Alignment.Center
    ) {
        // Selected disc / today ring.
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(discColor)
                .then(
                    if (isToday && !isSelected)
                        Modifier.border(1.5.dp, tokens.colors.accent, CircleShape)
                    else Modifier
                )
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                date.dayOfMonth.toString(),
                style = AuraType.body.copy(
                    color = numberColor,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            )
            if (items.isNotEmpty()) {
                Spacer(Modifier.size(2.dp))
                DotRow(items, dimmed = isSelected)
            }
        }
    }
}

@Composable
private fun DotRow(items: List<CalendarItem>, dimmed: Boolean) {
    val tokens = Aura.tokens
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.take(3).forEach { item ->
            val base = if (item.kind == CalendarKind.EVENT) tokens.colors.accent else tokens.colors.danger
            val color = if (dimmed) tokens.colors.background else base
            Box(Modifier.size(4.dp).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun TodayButton(onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        BasicText("Today", style = AuraType.label.copy(color = tokens.colors.accent))
    }
}

@Composable
private fun IconTap(glyph: Glyph, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = label }
            .auraPress(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(glyph, Aura.tokens.colors.textSecondary, Modifier.size(22.dp))
    }
}
