package com.fadghost.notesapp.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/** State for a single undo-able action shown by [AuraUndoSnackbar]. */
data class UndoMessage(
    val text: String,
    val actionLabel: String = "Undo"
)

/**
 * Reusable universal 5-second Undo snackbar (PLAN.md §7 — "build it reusable,
 * later milestones use it"). Custom Aura surface, spring entrance, auto-dismiss.
 * Pass [message] = null to hide. [onAction] runs the undo; [onDismiss] fires on
 * timeout or manual close.
 */
@Composable
fun AuraUndoSnackbar(
    message: UndoMessage?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Long = 5_000
) {
    // Auto-dismiss timer, keyed to the message identity so each new one resets it.
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(durationMs)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(spring(stiffness = Spring.StiffnessLow)) { it } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
        modifier = modifier
    ) {
        val tokens = Aura.tokens
        val msg = message ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(tokens.radii.md))
                .background(tokens.colors.surfaceTranslucent)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BasicText(
                text = msg.text,
                style = AuraType.body.copy(color = tokens.colors.textPrimary)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val actionInteraction = remember { MutableInteractionSource() }
                BasicText(
                    text = msg.actionLabel,
                    style = AuraType.label.copy(color = tokens.colors.accent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(tokens.radii.pill))
                        .auraPress(actionInteraction)
                        .clickable(
                            interactionSource = actionInteraction,
                            indication = null,
                            onClick = onAction
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                val dismissInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(tokens.radii.pill))
                        .auraPress(dismissInteraction)
                        .clickable(
                            interactionSource = dismissInteraction,
                            indication = null,
                            onClick = onDismiss
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AuraGlyph(Glyph.CLOSE, tokens.colors.textSecondary, Modifier.size(16.dp))
                }
            }
        }
    }
}
