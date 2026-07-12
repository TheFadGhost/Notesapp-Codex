package com.fadghost.notesapp.ui.notes

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.ui.components.FlowChips
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.PlainChip
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraAccents
import com.fadghost.notesapp.ui.theme.AuraType

/**
 * Manage a single tag (PLAN.md §6): rename, recolour from the accent palette,
 * delete, or merge into another tag. Custom Aura overlay.
 */
@Composable
fun TagManagerOverlay(
    tag: Tag,
    otherTags: List<Tag>,
    onRename: (String) -> Unit,
    onRecolor: (Int) -> Unit,
    onDelete: () -> Unit,
    onMerge: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    var name by remember(tag.id) { mutableStateOf(TextFieldValue(tag.name)) }
    // Inline danger confirms for the two irreversible actions (P2-10).
    var confirmingDelete by remember(tag.id) { mutableStateOf(false) }
    var mergeTarget by remember(tag.id) { mutableStateOf<Tag?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = tokens.elevation.scrim))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(tokens.radii.lg))
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(20.dp)
        ) {
            BasicText("Manage tag", style = AuraType.label.copy(color = tokens.colors.textSecondary))
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .height(42.dp).weight(1f)
                        .clip(RoundedCornerShape(tokens.radii.sm))
                        .background(tokens.colors.background)
                        .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                        cursorBrush = SolidColor(tokens.colors.accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(10.dp))
                SmallAction(Glyph.CHECK) { if (name.text.isNotBlank()) onRename(name.text.trim()) }
            }

            Spacer(Modifier.height(16.dp))
            BasicText("Colour", style = AuraType.label.copy(color = tokens.colors.textSecondary))
            Spacer(Modifier.height(8.dp))
            FlowChips {
                AuraAccents.palette.forEach { c ->
                    val dotInteraction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .auraPress(dotInteraction)
                            .background(c)
                            .border(
                                width = if (tag.color == c.toArgb()) 2.dp else 0.dp,
                                color = tokens.colors.textPrimary,
                                shape = CircleShape
                            )
                            .clickable(
                                interactionSource = dotInteraction,
                                indication = null,
                                onClick = { onRecolor(c.toArgb()) }
                            )
                    )
                }
            }

            if (otherTags.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                BasicText("Merge into", style = AuraType.label.copy(color = tokens.colors.textSecondary))
                Spacer(Modifier.height(8.dp))
                FlowChips {
                    otherTags.forEach { t ->
                        PlainChip(t.name, selected = mergeTarget?.id == t.id) {
                            mergeTarget = t
                            confirmingDelete = false
                        }
                    }
                }
                // Inline merge confirm (irreversible) — only the danger button commits.
                mergeTarget?.let { target ->
                    Spacer(Modifier.height(10.dp))
                    DangerConfirm(
                        message = "Merge “${tag.name}” into “${target.name}”? This can't be undone.",
                        confirmLabel = "Merge",
                        onConfirm = { onMerge(target.id) },
                        onCancel = { mergeTarget = null }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            if (confirmingDelete) {
                DangerConfirm(
                    message = "Delete this tag? It's removed from every note (their text is kept).",
                    confirmLabel = "Delete tag",
                    onConfirm = onDelete,
                    onCancel = { confirmingDelete = false }
                )
            } else {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    val deleteInteraction = remember { MutableInteractionSource() }
                    BasicText(
                        "Delete tag",
                        style = AuraType.label.copy(color = tokens.colors.danger),
                        modifier = Modifier
                            .clip(RoundedCornerShape(tokens.radii.pill))
                            .auraPress(deleteInteraction)
                            .clickable(
                                interactionSource = deleteInteraction,
                                indication = null,
                                onClick = { confirmingDelete = true; mergeTarget = null }
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Inline two-step danger confirm (P0-1 / P1-7 / P2-10 house style): an explanatory
 * line + a Cancel and a filled danger button. Only the danger button commits.
 */
@Composable
private fun DangerConfirm(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val tokens = Aura.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.danger.copy(alpha = 0.10f))
            .border(1.dp, tokens.colors.danger.copy(alpha = 0.5f), RoundedCornerShape(tokens.radii.md))
            .padding(14.dp)
    ) {
        BasicText(message, style = AuraType.label.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            val cancelInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .auraPress(cancelInteraction)
                    .clickable(
                        interactionSource = cancelInteraction,
                        indication = null,
                        onClick = onCancel
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                BasicText("Cancel", style = AuraType.label.copy(color = tokens.colors.textSecondary))
            }
            Spacer(Modifier.width(8.dp))
            val confirmInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .auraPress(confirmInteraction, tint = true)
                    .background(tokens.colors.danger)
                    .clickable(
                        interactionSource = confirmInteraction,
                        indication = null,
                        onClick = onConfirm
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                BasicText(confirmLabel, style = AuraType.label.copy(color = tokens.colors.background))
            }
        }
    }
}

@Composable
private fun SmallAction(glyph: Glyph, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(tokens.radii.sm))
            .auraPress(interaction, tint = true)
            .background(tokens.colors.accent.copy(alpha = 0.16f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(glyph, tokens.colors.accent, Modifier.size(18.dp))
    }
}
