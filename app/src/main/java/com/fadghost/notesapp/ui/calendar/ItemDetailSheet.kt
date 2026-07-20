package com.fadghost.notesapp.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.db.entity.Recurrence
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraDateTimePicker
import com.fadghost.notesapp.ui.components.AuraToggle
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.shell.LocalNavPillClearance
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * Pure, testable save-gate for the create/edit sheet (ux.md P1-4). Kept free of any
 * Compose/Android types and with an injectable [nowMillis] so it is deterministic
 * under unit test. A reminder whose time has already passed is blocked; events may
 * legitimately be logged in the past, so [Result.PAST_TIME] applies to reminders only.
 */
object ItemDetailValidation {
    enum class Result { OK, BLANK_TITLE, PAST_TIME, PAST_NOTIFICATION }

    fun canSave(
        kind: CalendarKind,
        title: String,
        whenMillis: Long,
        nowMillis: Long,
        recurrence: Recurrence = Recurrence.NONE,
        notificationLeadMinutes: Int? = null
    ): Result = when {
        title.isBlank() -> Result.BLANK_TITLE
        kind == CalendarKind.REMINDER && whenMillis < nowMillis -> Result.PAST_TIME
        kind == CalendarKind.EVENT &&
            recurrence == Recurrence.NONE &&
            notificationLeadMinutes != null &&
            notificationTime(whenMillis, notificationLeadMinutes) <= nowMillis -> Result.PAST_NOTIFICATION
        else -> Result.OK
    }

    private fun notificationTime(whenMillis: Long, leadMinutes: Int): Long {
        val leadMillis = leadMinutes.coerceAtLeast(0).toLong() * 60_000L
        return if (whenMillis < Long.MIN_VALUE + leadMillis) Long.MIN_VALUE else whenMillis - leadMillis
    }
}

/**
 * Spring-up create/edit sheet for a calendar item (PLAN.md §8 — title, start/end
 * via [AuraDateTimePicker], timezone-aware, notes, recurrence). One sheet serves
 * both events and reminders via a kind toggle; end-time and notes only apply to
 * events (the [com.fadghost.notesapp.data.db.entity.Reminder] row has neither).
 */
@Composable
fun ItemDetailSheet(
    draft: ItemDraft?,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (ItemDraft) -> Unit,
    onDelete: (ItemDraft) -> Unit
) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    val visible = draft != null
    androidx.activity.compose.BackHandler(enabled = visible) { onDismiss() }
    var lastDraft by remember { mutableStateOf<ItemDraft?>(null) }
    val renderedDraft = draft ?: lastDraft
    SideEffect { if (draft != null) lastDraft = draft }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // The handle owns a small, deliberate drag affordance. Keeping this offset on
    // the sheet itself lets the form body remain a normal vertical scroller.
    val dragOffsetY = remember { Animatable(0f) }
    val dismissThreshold = with(density) { 88.dp.toPx() }
    val dismissDistance = with(density) { 480.dp.toPx() }

    LaunchedEffect(visible) {
        if (!visible) dragOffsetY.snapTo(0f)
    }

    fun dismissWithAnimation() {
        scope.launch {
            dragOffsetY.animateTo(dismissDistance, tween(180))
            onDismiss()
            dragOffsetY.snapTo(0f)
        }
    }

    val handleDrag = Modifier.pointerInput(dismissThreshold) {
        detectVerticalDragGestures(
            onDragStart = { scope.launch { dragOffsetY.stop() } },
            onDragEnd = {
                scope.launch {
                    if (dragOffsetY.value >= dismissThreshold) {
                        dragOffsetY.animateTo(dismissDistance, tween(160))
                        onDismiss()
                        dragOffsetY.snapTo(0f)
                    } else {
                        dragOffsetY.animateTo(0f, MotionTokens.settle(reduceMotion))
                    }
                }
            },
            onDragCancel = {
                scope.launch {
                    dragOffsetY.animateTo(0f, MotionTokens.settle(reduceMotion))
                }
            }
        ) { _, deltaY ->
            scope.launch {
                dragOffsetY.snapTo((dragOffsetY.value + deltaY).coerceAtLeast(0f))
            }
        }
    }

    AnimatedVisibility(
        visible,
        enter = fadeIn(MotionTokens.fastFinite(reduceMotion)),
        exit = fadeOut(MotionTokens.fastFinite(reduceMotion)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = ::dismissWithAnimation
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible,
                enter = slideInVertically(MotionTokens.bouncyFinite(reduceMotion)) { it } +
                    fadeIn(MotionTokens.fastFinite(reduceMotion)),
                exit = slideOutVertically(MotionTokens.fastFinite(reduceMotion)) { it } +
                    fadeOut(MotionTokens.fastFinite(reduceMotion))
            ) {
                val seed = renderedDraft ?: return@AnimatedVisibility
                SheetBody(
                    seed = seed,
                    zone = zone,
                    onDismiss = ::dismissWithAnimation,
                    onSave = onSave,
                    onDelete = onDelete,
                    dragOffsetY = dragOffsetY.value,
                    handleDrag = handleDrag
                )
            }
        }
    }
}

@Composable
private fun SheetBody(
    seed: ItemDraft,
    zone: ZoneId,
    onDismiss: () -> Unit,
    onSave: (ItemDraft) -> Unit,
    onDelete: (ItemDraft) -> Unit,
    dragOffsetY: Float,
    handleDrag: Modifier
) {
    val tokens = Aura.tokens
    val isNew = seed.baseId == 0L
    // Clear the floating nav pill (inset + pill + margin + gap), not just the system
    // nav bar — otherwise the Repeat row and Save/Create buttons render under the pill.
    val navClearance = LocalNavPillClearance.current

    var kind by remember(seed) { mutableStateOf(seed.kind) }
    var title by remember(seed) { mutableStateOf(seed.title) }
    var start by remember(seed) { mutableStateOf(toLdt(seed.start, zone)) }
    var endEnabled by remember(seed) { mutableStateOf(seed.end != null) }
    var end by remember(seed) {
        mutableStateOf(toLdt(seed.end ?: EventEndTime.defaultFor(seed.start), zone))
    }
    var notes by remember(seed) { mutableStateOf(seed.notes) }
    var recurrence by remember(seed) { mutableStateOf(seed.recurrence) }
    var notificationLeadMinutes by remember(seed) { mutableStateOf(seed.notificationLeadMinutes) }

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffsetY }
            // imePadding OUTSIDE the surface lifts the whole bottom-anchored sheet above
            // the keyboard so the title/notes field and the action buttons stay visible.
            .imePadding()
            // Lift the whole surface above the shell pill. Padding only the scroll
            // content still allowed the form's action row to sit behind the nav fade.
            .padding(bottom = navClearance)
            .clip(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 20.dp)
    ) {
        // Grab handle.
        Box(
            Modifier
                .then(handleDrag)
                // A 64x40dp hit target makes the visible dash reliably draggable,
                // instead of requiring a pixel-perfect grab on its 4dp stroke.
                .size(width = 64.dp, height = 40.dp)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            com.fadghost.notesapp.ui.components.GrabHandle()
        }

        BasicText(
            if (isNew) "New ${kind.name.lowercase()}" else "Edit",
            style = AuraType.titleSm.copy(color = tokens.colors.textPrimary)
        )
        Spacer(Modifier.size(14.dp))

        // Kind toggle (only when creating; editing keeps the existing kind).
        if (isNew) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                KindTab("Event", kind == CalendarKind.EVENT, Modifier.weight(1f)) { kind = CalendarKind.EVENT }
                KindTab("Reminder", kind == CalendarKind.REMINDER, Modifier.weight(1f)) { kind = CalendarKind.REMINDER }
            }
            Spacer(Modifier.size(14.dp))
        }

        Field(value = title, placeholder = "Title", onChange = { title = it })
        Spacer(Modifier.size(16.dp))

        BasicText(if (kind == CalendarKind.EVENT) "Starts" else "When", style = AuraType.label.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.size(6.dp))
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            AuraDateTimePicker(value = start, onChange = { newStart ->
                // Keep the event duration when the user shifts the start.
                if (kind == CalendarKind.EVENT && endEnabled) {
                    end = toLdt(
                        EventEndTime.moveWithStart(
                            toMillis(start, zone),
                            toMillis(newStart, zone),
                            toMillis(end, zone)
                        ),
                        zone
                    )
                }
                start = newStart
            }, zone = zone)
        }

        if (kind == CalendarKind.EVENT) {
            Spacer(Modifier.size(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    BasicText("End time", style = AuraType.label.copy(color = tokens.colors.textPrimary))
                    BasicText(
                        if (endEnabled) "Event has a finish time" else "No end time",
                        style = AuraType.label.copy(color = tokens.colors.textSecondary)
                    )
                }
                AuraToggle(checked = endEnabled, onCheckedChange = { enabled ->
                    if (enabled && !end.isAfter(start)) {
                        end = toLdt(EventEndTime.defaultFor(toMillis(start, zone)), zone)
                    }
                    endEnabled = enabled
                })
            }
            if (endEnabled) {
                Spacer(Modifier.size(8.dp))
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    AuraDateTimePicker(value = end, onChange = { candidate ->
                        end = toLdt(
                            EventEndTime.validEnd(
                                toMillis(start, zone), toMillis(candidate, zone)
                            ),
                            zone
                        )
                    }, zone = zone)
                }
            }
        }

        Spacer(Modifier.size(16.dp))
        BasicText("Repeat", style = AuraType.label.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.size(6.dp))
        RecurrencePicker(value = recurrence, onChange = { recurrence = it }, modifier = Modifier.fillMaxWidth())

        if (kind == CalendarKind.EVENT) {
            Spacer(Modifier.size(16.dp))
            BasicText("Alert", style = AuraType.label.copy(color = tokens.colors.textSecondary))
            Spacer(Modifier.size(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EVENT_ALERT_OPTIONS.forEach { (minutes, label) ->
                    LeadChip(
                        label = label,
                        selected = notificationLeadMinutes == minutes,
                        onClick = { notificationLeadMinutes = minutes }
                    )
                }
            }

            Spacer(Modifier.size(16.dp))
            BasicText("Notes", style = AuraType.label.copy(color = tokens.colors.textSecondary))
            Spacer(Modifier.size(6.dp))
            Field(value = notes, placeholder = "Add notes", onChange = { notes = it }, singleLine = false)
        }

        // Live save-gate (ux.md P1-4): blank title dims the button; a past reminder
        // time surfaces an inline notice and blocks the save entirely.
        val startMs = toMillis(start, zone)
        val validation = ItemDetailValidation.canSave(
            kind = kind,
            title = title,
            whenMillis = startMs,
            nowMillis = System.currentTimeMillis(),
            recurrence = recurrence,
            notificationLeadMinutes = notificationLeadMinutes
        )
        val canSave = validation == ItemDetailValidation.Result.OK

        if (validation == ItemDetailValidation.Result.PAST_TIME) {
            Spacer(Modifier.size(14.dp))
            BasicText(
                "That time has already passed — pick a future time for this reminder.",
                style = AuraType.label.copy(color = tokens.colors.danger)
            )
        }
        if (validation == ItemDetailValidation.Result.PAST_NOTIFICATION) {
            Spacer(Modifier.size(14.dp))
            BasicText(
                "That alert time has already passed — choose a later event time or a shorter alert.",
                style = AuraType.label.copy(color = tokens.colors.danger)
            )
        }

        Spacer(Modifier.size(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) {
                SoftButton("Delete", filled = false, onClick = {
                    onDelete(seed); onDismiss()
                })
            }
            Spacer(Modifier.weight(1f))
            SoftButton("Cancel", filled = false, onClick = onDismiss)
            Spacer(Modifier.size(10.dp))
            SaveButton(label = if (isNew) "Create" else "Save", enabled = canSave, onClick = {
                val endMs = if (kind == CalendarKind.EVENT && endEnabled) {
                    EventEndTime.validEnd(startMs, toMillis(end, zone))
                } else null
                onSave(
                    seed.copy(
                        kind = kind,
                        title = title,
                        start = startMs,
                        end = endMs,
                        notes = notes,
                        recurrence = recurrence,
                        notificationLeadMinutes = if (kind == CalendarKind.EVENT) notificationLeadMinutes else null
                    )
                )
                onDismiss()
            })
        }
    }
}

/**
 * Accent primary button that can be disabled (ux.md P1-4). Mirrors [SoftButton]'s
 * filled look but dims to 40% and drops its click when [enabled] is false, so a blank
 * title or past-time reminder can never be saved.
 */
@Composable
private fun SaveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    val alpha = if (enabled) 1f else 0.4f
    Box(
        Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(tokens.colors.accent.copy(alpha = alpha))
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = AuraType.label.copy(color = tokens.colors.background.copy(alpha = alpha)))
    }
}

@Composable
private fun KindTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val bg = if (selected) tokens.colors.accent else tokens.colors.background
    val fg = if (selected) tokens.colors.background else tokens.colors.textSecondary
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(bg)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = AuraType.label.copy(color = fg))
    }
}

private val EVENT_ALERT_OPTIONS: List<Pair<Int?, String>> = listOf(
    null to "Off",
    0 to "At time",
    10 to "10 min",
    30 to "30 min",
    60 to "1 hour",
    1_440 to "1 day"
)

@Composable
private fun LeadChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val background = if (selected) tokens.colors.accent else tokens.colors.background
    val foreground = if (selected) tokens.colors.background else tokens.colors.textSecondary
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(background)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = AuraType.label.copy(color = foreground))
    }
}

@Composable
private fun Field(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true
) {
    val tokens = Aura.tokens
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.sm))
            .background(tokens.colors.background)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
            .padding(12.dp)
    ) {
        if (value.isEmpty()) {
            BasicText(placeholder, style = AuraType.body.copy(color = tokens.colors.textSecondary))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
            cursorBrush = SolidColor(tokens.colors.accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun toLdt(millis: Long, zone: ZoneId): LocalDateTime =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

private fun toMillis(ldt: LocalDateTime, zone: ZoneId): Long =
    ldt.atZone(zone).toInstant().toEpochMilli()
