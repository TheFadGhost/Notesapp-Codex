package com.fadghost.notesapp.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.fadghost.notesapp.data.ai.parse.ActionType
import com.fadghost.notesapp.data.ai.parse.ProposedAction
import com.fadghost.notesapp.ui.components.AuraDateTimePicker
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Extract-actions sheet (PLAN.md §5): one swipeable confirmation card per
 * proposal (swipe right = accept, left = reject, spring animated), with inline
 * Edit (title + custom date-time picker) and an "Other" free-text revision box.
 * Accept-all lives in the footer; the single Undo-all snackbar is shown by the
 * editor from the view-model.
 */
@Composable
fun ExtractSheet(
    state: ExtractState,
    onAccept: (Long) -> Unit,
    onReject: (Long) -> Unit,
    onBeginEdit: (Long) -> Unit,
    onCancelEdit: (Long) -> Unit,
    onApplyEdit: (Long, String, Long?) -> Unit,
    onRevise: (Long, String) -> Unit,
    onAcceptAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    AnimatedVisibility(state.active, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        ) {
            AnimatedVisibility(
                state.active,
                enter = slideInVertically(spring(stiffness = Spring.StiffnessLow)) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                        .background(tokens.colors.surface)
                        .border(1.dp, tokens.colors.outline, RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .navigationBarsPadding()
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AuraGlyph(Glyph.CALENDAR, tokens.colors.accent, Modifier.size(22.dp))
                        Spacer(Modifier.size(10.dp))
                        BasicText("Extracted actions", style = AuraType.title.copy(color = tokens.colors.textPrimary))
                        Spacer(Modifier.weight(1f))
                        if (state.cards.isNotEmpty()) {
                            BasicText("swipe or tap", style = AuraType.label.copy(color = tokens.colors.textSecondary))
                        }
                    }
                    Spacer(Modifier.size(12.dp))

                    when {
                        state.loading -> Loading()
                        state.error != null || state.rawError != null -> ErrorBlock(
                            friendly = state.error,
                            raw = state.rawError,
                            onRetry = onDismiss
                        )
                        state.cards.isEmpty() -> BasicText(
                            if (state.acceptedCount > 0) "All set — ${state.acceptedCount} added."
                            else "No actions found in this note.",
                            style = AuraType.body.copy(color = tokens.colors.textSecondary)
                        )
                        else -> Column(
                            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            state.warnings.forEach { WarningLine(it) }
                            state.cards.forEach { card ->
                                ActionCardItem(
                                    card = card,
                                    onAccept = { onAccept(card.id) },
                                    onReject = { onReject(card.id) },
                                    onBeginEdit = { onBeginEdit(card.id) },
                                    onCancelEdit = { onCancelEdit(card.id) },
                                    onApplyEdit = { t, dt -> onApplyEdit(card.id, t, dt) },
                                    onRevise = { instr -> onRevise(card.id, instr) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.size(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Done", filled = false, onClick = onDismiss)
                        Spacer(Modifier.weight(1f))
                        if (state.cards.isNotEmpty()) SoftButton("Accept all", filled = true, onClick = onAcceptAll)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCardItem(
    card: ExtractCard,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onApplyEdit: (String, Long?) -> Unit,
    onRevise: (String) -> Unit
) {
    val tokens = Aura.tokens
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offsetX = remember { Animatable(0f) }
    val threshold = with(density) { 110.dp.toPx() }
    var showOther by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = offsetX.value }
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.background)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .then(
                if (!card.editing) Modifier.pointerInput(card.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offsetX.value > threshold -> { offsetX.animateTo(1200f); onAccept() }
                                    offsetX.value < -threshold -> { offsetX.animateTo(-1200f); onReject() }
                                    else -> offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                            }
                        }
                    ) { _, drag -> scope.launch { offsetX.snapTo(offsetX.value + drag) } }
                } else Modifier
            )
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(card.action.type)
                Spacer(Modifier.size(8.dp))
                BasicText(card.action.title, style = AuraType.body.copy(color = tokens.colors.textPrimary))
            }
            card.action.datetimeMillis?.let {
                Spacer(Modifier.size(4.dp))
                BasicText(formatDate(it), style = AuraType.label.copy(color = tokens.colors.textSecondary))
            }
            card.action.notes?.let {
                Spacer(Modifier.size(4.dp))
                BasicText(it, style = AuraType.label.copy(color = tokens.colors.textSecondary))
            }

            if (card.revising) {
                Spacer(Modifier.size(8.dp))
                BasicText("Revising…", style = AuraType.label.copy(color = tokens.colors.accent))
            }

            if (card.editing) {
                EditPanel(card.action, onApplyEdit, onCancelEdit)
            } else if (showOther) {
                OtherPanel(onSend = { instr -> showOther = false; onRevise(instr) }, onCancel = { showOther = false })
            } else {
                Spacer(Modifier.size(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardAction(Glyph.CHECK, tokens.colors.accent, onAccept)
                    CardAction(Glyph.CLOSE, tokens.colors.danger, onReject)
                    CardAction(Glyph.HEADING, tokens.colors.textSecondary, onBeginEdit)
                    TextAction("Other") { showOther = true }
                }
            }
        }
    }
}

@Composable
private fun EditPanel(action: ProposedAction, onApply: (String, Long?) -> Unit, onCancel: () -> Unit) {
    val tokens = Aura.tokens
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf(action.title) }
    var dt by remember {
        mutableStateOf(
            action.datetimeMillis?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
                ?: LocalDateTime.now(zone).withSecond(0).withNano(0)
        )
    }
    Column(Modifier.padding(top = 10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(tokens.radii.sm))
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                .padding(12.dp)
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.size(10.dp))
        if (action.type != ActionType.TODO) {
            AuraDateTimePicker(value = dt, onChange = { dt = it })
            Spacer(Modifier.size(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Cancel", onCancel)
            TextAction("Save") {
                val millis = if (action.type == ActionType.TODO) action.datetimeMillis
                else dt.atZone(zone).toInstant().toEpochMilli()
                onApply(title, millis)
            }
        }
    }
}

@Composable
private fun OtherPanel(onSend: (String) -> Unit, onCancel: () -> Unit) {
    val tokens = Aura.tokens
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(tokens.radii.sm))
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                .padding(12.dp)
        ) {
            if (text.isEmpty()) BasicText("e.g. move it to next Monday 3pm", style = AuraType.body.copy(color = tokens.colors.textSecondary))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("Cancel", onCancel)
            TextAction("Send") { if (text.isNotBlank()) onSend(text) }
        }
    }
}

@Composable
private fun TypeBadge(type: ActionType) {
    val tokens = Aura.tokens
    val label = when (type) {
        ActionType.EVENT -> "Event"
        ActionType.REMINDER -> "Reminder"
        ActionType.TODO -> "To-do"
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.accent.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        BasicText(label, style = AuraType.label.copy(color = tokens.colors.accent))
    }
}

@Composable
private fun CardAction(glyph: Glyph, color: Color, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(tokens.radii.sm))
            .auraPress(interaction)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AuraGlyph(glyph, color, Modifier.size(18.dp)) }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) { BasicText(label, style = AuraType.label.copy(color = tokens.colors.textPrimary)) }
}

@Composable
private fun WarningLine(text: String) {
    val tokens = Aura.tokens
    BasicText("• $text", style = AuraType.label.copy(color = tokens.colors.textSecondary))
}

/**
 * Friendly-first error state (ux.md P1-9). Leads with plain-language copy and a
 * Retry action; the raw/technical detail is tucked behind a collapsed "Show details"
 * disclosure so it never dumps on the user by default.
 */
@Composable
private fun ErrorBlock(friendly: String?, raw: String?, onRetry: () -> Unit) {
    val tokens = Aura.tokens
    var showDetails by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuraGlyph(Glyph.CLOSE, tokens.colors.danger, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            BasicText("That didn't work", style = AuraType.body.copy(color = tokens.colors.textPrimary))
        }
        Spacer(Modifier.size(6.dp))
        BasicText(
            friendly ?: "I couldn't pull actions from this note just now. Give it another try.",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftButton("Retry", filled = true, onClick = onRetry)
            if (raw != null) {
                TextAction(if (showDetails) "Hide details" else "Show details") { showDetails = !showDetails }
            }
        }
        if (showDetails && raw != null) {
            Spacer(Modifier.size(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(tokens.radii.sm))
                    .background(tokens.colors.background)
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                BasicText(raw, style = AuraType.label.copy(color = tokens.colors.textSecondary))
            }
        }
    }
}

@Composable
private fun Loading() {
    val tokens = Aura.tokens
    BasicText("Reading your note…", style = AuraType.body.copy(color = tokens.colors.textSecondary))
}

private val DATE_FMT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

private fun formatDate(millis: Long): String =
    DATE_FMT.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
