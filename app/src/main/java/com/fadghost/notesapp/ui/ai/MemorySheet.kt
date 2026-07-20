package com.fadghost.notesapp.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.LocalReduceMotion

/**
 * "Add to memory" confirm sheet (V3-PROMPTS.md §1.2). One card per proposed entry — the
 * user reviews EVERY entry before anything is written to the vault: per-entry accept toggle,
 * inline edit (title + body), remove, and "Keep N". Same confirm pattern as Extract. Folio's
 * voice throughout (§3): thinking line while working, honest empty/error states.
 */
@Composable
fun MemorySheet(
    state: MemoryState,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onBeginEdit: (Long) -> Unit,
    onCancelEdit: (Long) -> Unit,
    onApplyEdit: (Long, String, String) -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
    currentModel: String = "",
    onSwapModel: ((String) -> Unit)? = null,
    /** Re-issues the failed request. Defaults to dismiss for callers without a retry path. */
    onRetry: (() -> Unit)? = null
) {
    val tokens = Aura.tokens
    androidx.activity.compose.BackHandler(enabled = state.active) { onDismiss() }
    val reduceMotion = LocalReduceMotion.current
    AnimatedVisibility(state.active, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        ) {
            AnimatedVisibility(
                state.active,
                enter = slideInVertically(MotionTokens.bouncyFinite(reduceMotion)) { it } + fadeIn(MotionTokens.fastFinite(reduceMotion)),
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
                        AuraGlyph(Glyph.BOOK, tokens.colors.accent, Modifier.size(22.dp))
                        Spacer(Modifier.size(10.dp))
                        BasicText("Add to memory", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
                        Spacer(Modifier.weight(1f))
                        if (state.loading) {
                            BasicText(
                                state.thinking.ifBlank { "Noting what matters…" },
                                style = AuraType.label.copy(color = tokens.colors.textSecondary)
                            )
                        }
                    }
                    Spacer(Modifier.size(12.dp))

                    when {
                        state.loading -> BasicText(
                            state.thinking.ifBlank { "Noting what matters…" },
                            style = AuraType.body.copy(color = tokens.colors.textSecondary)
                        )
                        state.error != null || state.rawError != null ->
                            MemoryError(state.error, state.rawError, onRetry ?: onDismiss, currentModel, onSwapModel)
                        state.cards.isEmpty() -> BasicText(
                            // Folio empty copy — honest, never a blank stare (V3-DELIGHT §3C).
                            state.skippedReason?.let { "Nothing durable to keep here — $it" }
                                ?: "Nothing durable to keep here. Memory is for facts that outlast today.",
                            style = AuraType.body.copy(color = tokens.colors.textSecondary)
                        )
                        else -> Column(
                            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            state.cards.forEach { card ->
                                MemoryCardItem(
                                    card = card,
                                    onToggle = { onToggle(card.id) },
                                    onRemove = { onRemove(card.id) },
                                    onBeginEdit = { onBeginEdit(card.id) },
                                    onCancelEdit = { onCancelEdit(card.id) },
                                    onApplyEdit = { t, b -> onApplyEdit(card.id, t, b) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.size(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SoftButton(if (state.cards.isEmpty()) "Done" else "Not now", filled = false, onClick = onDismiss)
                        Spacer(Modifier.weight(1f))
                        if (state.cards.isNotEmpty()) {
                            val n = state.acceptedCount
                            SoftButton(if (n > 0) "Keep $n" else "Keep", filled = n > 0, onClick = onKeep)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCardItem(
    card: MemoryCard,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onApplyEdit: (String, String) -> Unit
) {
    val tokens = Aura.tokens
    val m = card.entry.model
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.background)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AcceptBox(card.accepted, onToggle)
                Spacer(Modifier.size(10.dp))
                TypeBadge(m.type)
                if (card.entry.isUpdate) {
                    Spacer(Modifier.size(6.dp))
                    UpdatePill()
                }
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.size(8.dp))
            BasicText(m.title, style = AuraType.body.copy(color = tokens.colors.textPrimary))
            Spacer(Modifier.size(4.dp))
            BasicText(m.body, style = AuraType.label.copy(color = tokens.colors.textSecondary))

            if (m.tags.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.tags.forEach { TagPill(it) }
                }
            }

            if (card.editing) {
                MemoryEditPanel(m.title, m.body, onApplyEdit, onCancelEdit)
            } else {
                Spacer(Modifier.size(10.dp))
                // Remove sits alone on the far side — destructive never neighbours its
                // benign twin (council thumb audit).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Glyph.PENCIL, tokens.colors.textSecondary, label = "Edit entry", onClick = onBeginEdit)
                    Spacer(Modifier.weight(1f))
                    IconChip(Glyph.CLOSE, tokens.colors.danger, label = "Remove entry", onClick = onRemove)
                }
            }
        }
    }
}

@Composable
private fun MemoryEditPanel(title: String, body: String, onApply: (String, String) -> Unit, onCancel: () -> Unit) {
    val tokens = Aura.tokens
    var t by remember { mutableStateOf(title) }
    var b by remember { mutableStateOf(body) }
    Column(Modifier.padding(top = 10.dp)) {
        Field(t, "Title") { t = it }
        Spacer(Modifier.size(8.dp))
        Field(b, "Body", minLines = 3) { b = it }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextChip("Cancel", onCancel)
            TextChip("Save") { onApply(t, b) }
        }
    }
}

@Composable
private fun Field(value: String, placeholder: String, minLines: Int = 1, onChange: (String) -> Unit) {
    val tokens = Aura.tokens
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.sm))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
            .padding(12.dp)
    ) {
        if (value.isEmpty()) BasicText(placeholder, style = AuraType.body.copy(color = tokens.colors.textSecondary))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = minLines == 1,
            minLines = minLines,
            textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
            cursorBrush = SolidColor(tokens.colors.accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AcceptBox(checked: Boolean, onToggle: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    // 44dp hit area around the 28dp visual box (council thumb audit).
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(tokens.radii.sm))
            .semantics { contentDescription = if (checked) "Don't keep this memory" else "Keep this memory" }
            .auraPress(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(tokens.radii.sm))
                .background(if (checked) tokens.colors.accent else Color.Transparent)
                .border(1.dp, if (checked) tokens.colors.accent else tokens.colors.outline, RoundedCornerShape(tokens.radii.sm)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) AuraGlyph(Glyph.CHECK, tokens.colors.background, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val tokens = Aura.tokens
    Box(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.accent.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        BasicText(type.replaceFirstChar { it.uppercase() }, style = AuraType.label.copy(color = tokens.colors.accent))
    }
}

@Composable
private fun UpdatePill() {
    val tokens = Aura.tokens
    Box(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.linkBlue.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        BasicText("Updates a note", style = AuraType.label.copy(color = tokens.colors.linkBlue))
    }
}

@Composable
private fun TagPill(tag: String) {
    val tokens = Aura.tokens
    Box(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.outline.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        BasicText(tag, style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
    }
}

@Composable
private fun IconChip(glyph: Glyph, color: Color, label: String, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .semantics { contentDescription = label }
            .clip(RoundedCornerShape(tokens.radii.sm))
            .auraPress(interaction)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AuraGlyph(glyph, color, Modifier.size(18.dp)) }
}

@Composable
private fun TextChip(label: String, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) { BasicText(label, style = AuraType.label.copy(color = tokens.colors.textPrimary)) }
}

@Composable
private fun MemoryError(
    friendly: String?,
    raw: String?,
    onRetry: () -> Unit,
    currentModel: String = "",
    onSwapModel: ((String) -> Unit)? = null
) {
    val tokens = Aura.tokens
    var showDetails by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuraGlyph(Glyph.WARNING, tokens.colors.danger, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            BasicText("That didn't work", style = AuraType.body.copy(color = tokens.colors.textPrimary))
        }
        Spacer(Modifier.size(6.dp))
        BasicText(
            friendly ?: "That came back garbled. Try once more?",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SoftButton("Try again", filled = true, onClick = onRetry)
            if (raw != null) TextChip(if (showDetails) "Hide details" else "Show details") { showDetails = !showDetails }
        }
        if (onSwapModel != null) {
            Spacer(Modifier.size(12.dp))
            ModelSwapRow(currentModel = currentModel, onPick = onSwapModel)
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
