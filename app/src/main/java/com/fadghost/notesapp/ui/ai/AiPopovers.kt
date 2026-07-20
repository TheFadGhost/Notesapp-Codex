package com.fadghost.notesapp.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.LocalReduceMotion

/**
 * Friendly no-key popover (PLAN.md §5 — "AI buttons show a friendly 'Add your
 * OpenRouter key' tap-through — never dead buttons"). Scrim + springy card with a
 * deep-link to Settings.
 */
@Composable
fun NoKeyPopover(
    visible: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    androidx.activity.compose.BackHandler(enabled = visible) { onDismiss() }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(MotionTokens.bouncyFinite(LocalReduceMotion.current)) + fadeIn(MotionTokens.fastFinite(LocalReduceMotion.current)),
                exit = scaleOut() + fadeOut()
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 40.dp)
                        .clip(RoundedCornerShape(tokens.radii.lg))
                        .background(tokens.colors.surface)
                        .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                        // Swallow taps so clicking the card doesn't dismiss.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(tokens.radii.pill))
                            .background(tokens.colors.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) { AuraGlyph(Glyph.SPARKLE, tokens.colors.accent, Modifier.size(26.dp)) }
                    Spacer(Modifier.height(14.dp))
                    BasicText("AI needs a key", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        "Add your OpenRouter key in Settings to enable Clean-up and Extract.",
                        style = AuraType.body.copy(color = tokens.colors.textSecondary)
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Not now", filled = false, onClick = onDismiss, modifier = Modifier)
                        Spacer(Modifier.width(2.dp))
                        SoftButton("Open Settings", filled = true, onClick = onOpenSettings, modifier = Modifier)
                    }
                }
            }
        }
    }
}

@Composable
fun SoftButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Destructive styling: danger text, never the accent fill (council G1). */
    danger: Boolean = false
) {
    val tokens = Aura.tokens
    val bg = if (filled && !danger) tokens.colors.accent else tokens.colors.surface
    val fg = when {
        danger -> tokens.colors.danger
        filled -> tokens.colors.background
        else -> tokens.colors.textPrimary
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(bg)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = AuraType.label.copy(color = fg))
    }
}
