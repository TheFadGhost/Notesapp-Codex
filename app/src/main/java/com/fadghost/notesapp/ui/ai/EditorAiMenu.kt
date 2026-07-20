package com.fadghost.notesapp.ui.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.components.rememberAuraHaptics
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.auraFloatShadow
import com.fadghost.notesapp.ui.theme.auraTopHighlight

/**
 * The editor AI sparkle menu (V3-PROMPTS.md §1.9): one sparkle button opens an anchored
 * four-row panel — Clean up / Rewrite / Extract / Add to memory. Replaces the old two
 * separate toolbar icons. Anchored to the sparkle button's bounds ([anchor], root px),
 * dropping below it and clamped to the screen edges; scrim dismiss; reduce-motion aware.
 */
@Composable
fun EditorAiMenu(
    visible: Boolean,
    anchor: Rect,
    memoryEnabled: Boolean,
    onCleanup: () -> Unit,
    onRewrite: () -> Unit,
    onExtract: () -> Unit,
    onAddMemory: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    androidx.activity.compose.BackHandler(enabled = visible) { onDismiss() }
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    val haptics = rememberAuraHaptics()

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            val cardW = 264.dp
            val margin = 12.dp
            // Place below the sparkle, left-aligned to it, clamped 12dp from the screen edges.
            val offset = with(density) {
                val screenW = constraintsMaxWidthPx()
                val cardWpx = cardW.toPx()
                val marginPx = margin.toPx()
                val x = anchor.left.coerceIn(marginPx, (screenW - cardWpx - marginPx).coerceAtLeast(marginPx))
                val y = anchor.bottom + 8.dp.toPx()
                IntOffset(x.toInt(), y.toInt())
            }
            AnimatedVisibility(
                visible = visible,
                enter = if (reduceMotion) fadeIn()
                else scaleIn(
                    animationSpec = MotionTokens.PanelScale,
                    initialScale = 0.72f,
                    transformOrigin = TransformOrigin(0f, 0f)
                ) + fadeIn(),
                exit = if (reduceMotion) fadeOut() else scaleOut(transformOrigin = TransformOrigin(0f, 0f)) + fadeOut(),
                modifier = Modifier.offset { offset }
            ) {
                Column(
                    Modifier
                        .widthIn(max = cardW)
                        .width(cardW)
                        .auraFloatShadow(RoundedCornerShape(tokens.radii.lg))
                        .clip(RoundedCornerShape(tokens.radii.lg))
                        .background(tokens.colors.surface)
                        .auraTopHighlight(tokens.radii.lg)
                        .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .padding(vertical = 8.dp)
                ) {
                    MenuRow(Glyph.SPARKLE, "Clean up", "Tidy grammar & formatting") { haptics.tick(); onCleanup() }
                    MenuRow(Glyph.PENCIL, "Rewrite", "Restructure a ramble, keep every fact") { haptics.tick(); onRewrite() }
                    MenuRow(Glyph.CALENDAR, "Extract", "Pull out reminders & dates") { haptics.tick(); onExtract() }
                    MenuRow(
                        glyph = Glyph.BOOK,
                        title = "Add to memory",
                        subtitle = if (memoryEnabled) "Keep the durable bits" else "Off — enable in Settings",
                        enabled = memoryEnabled
                    ) { haptics.tick(); onAddMemory() }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    glyph: Glyph,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(tokens.radii.md))
            .auraPress(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(tokens.radii.sm))
                .background(tokens.colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { AuraGlyph(glyph, tokens.colors.accent, Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            BasicText(title, style = AuraType.body.copy(color = tokens.colors.textPrimary))
            BasicText(subtitle, style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
        }
    }
}

/** Screen width in px, read from the enclosing BoxWithConstraints-free layout via density. */
@Composable
private fun constraintsMaxWidthPx(): Float {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val density = LocalDensity.current
    return with(density) { config.screenWidthDp.dp.toPx() }
}
