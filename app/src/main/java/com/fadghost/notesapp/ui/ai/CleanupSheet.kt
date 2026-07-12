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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.components.rememberAuraHaptics
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/**
 * Before/after Clean-up sheet (PLAN.md §5 — "before/after toggle, NOT a line
 * diff"). Segmented Before|After control; the After pane fills live while the
 * model streams. Accept / Keep original / Regenerate, plus a mid-stream Cancel.
 */
@Composable
fun CleanupSheet(
    state: CleanupState,
    onSegment: (BeforeAfter) -> Unit,
    onCancel: () -> Unit,
    onRegenerate: () -> Unit,
    onKeepOriginal: () -> Unit,
    onAccept: () -> Unit
) {
    val tokens = Aura.tokens
    // Success haptic when a clean-up finishes streaming (PLAN.md §10 — AI done).
    val haptics = rememberAuraHaptics()
    LaunchedEffect(state.done) {
        if (state.done && state.after.isNotBlank()) haptics.success()
    }
    AnimatedVisibility(
        visible = state.active,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onKeepOriginal
                )
        ) {
            AnimatedVisibility(
                visible = state.active,
                enter = slideInVertically(spring(stiffness = Spring.StiffnessLow)) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                        .background(tokens.colors.surface)
                        .border(
                            1.dp, tokens.colors.outline,
                            RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg)
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .navigationBarsPadding()
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AuraGlyph(Glyph.SPARKLE, tokens.colors.accent, Modifier.size(22.dp))
                        Spacer(Modifier.size(10.dp))
                        BasicText("Clean up", style = AuraType.title.copy(color = tokens.colors.textPrimary))
                        Spacer(Modifier.weight(1f))
                        if (state.streaming) {
                            BasicText("streaming…", style = AuraType.label.copy(color = tokens.colors.textSecondary))
                        }
                    }
                    Spacer(Modifier.size(14.dp))

                    Segmented(state.segment, onSegment)
                    Spacer(Modifier.size(12.dp))

                    val shown = if (state.segment == BeforeAfter.BEFORE) state.before
                    else state.after.ifEmpty { if (state.streaming) "…" else "" }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 360.dp)
                            .clip(RoundedCornerShape(tokens.radii.md))
                            .background(tokens.colors.background)
                            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        BasicText(shown, style = AuraType.body.copy(color = tokens.colors.textPrimary))
                    }

                    state.error?.let {
                        Spacer(Modifier.size(10.dp))
                        BasicText(it, style = AuraType.label.copy(color = tokens.colors.danger))
                    }
                    if (state.queued) {
                        Spacer(Modifier.size(10.dp))
                        QueuedChip()
                    }

                    Spacer(Modifier.size(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        when {
                            state.streaming -> SoftButton("Cancel", filled = false, onClick = onCancel)
                            else -> {
                                SoftButton("Keep original", filled = false, onClick = onKeepOriginal)
                                SoftButton("Regenerate", filled = false, onClick = onRegenerate)
                                Spacer(Modifier.weight(1f))
                                if (state.done && state.after.isNotBlank()) {
                                    SoftButton("Accept", filled = true, onClick = onAccept)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Segmented(selected: BeforeAfter, onSelect: (BeforeAfter) -> Unit) {
    val tokens = Aura.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.background)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .padding(3.dp)
    ) {
        SegItem("Before", selected == BeforeAfter.BEFORE, Modifier.weight(1f)) { onSelect(BeforeAfter.BEFORE) }
        SegItem("After", selected == BeforeAfter.AFTER, Modifier.weight(1f)) { onSelect(BeforeAfter.AFTER) }
    }
}

@Composable
private fun SegItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .background(if (selected) tokens.colors.accent else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            label,
            style = AuraType.label.copy(
                color = if (selected) tokens.colors.background else tokens.colors.textSecondary,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
fun QueuedChip() {
    val tokens = Aura.tokens
    Row(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.accent.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuraGlyph(Glyph.CLOCK, tokens.colors.accent, Modifier.size(14.dp))
        Spacer(Modifier.size(6.dp))
        BasicText("Queued — will run when back online", style = AuraType.label.copy(color = tokens.colors.accent))
    }
}
