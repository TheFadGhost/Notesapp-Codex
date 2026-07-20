package com.fadghost.notesapp.ui.attach

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens

/**
 * Compact source menu for adding an attachment (M-A ingest): Photo (system photo
 * picker), File (document picker) and Paste image (when the clipboard holds one).
 * A dismiss scrim + a springy card anchored below the editor's top bar; reduce-motion
 * collapses the spring to a fade.
 */
@Composable
fun AttachMenu(
    visible: Boolean,
    canPaste: Boolean,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onPasteImage: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    androidx.activity.compose.BackHandler(enabled = visible) { onDismiss() }
    val reduceMotion = LocalReduceMotion.current

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.TopCenter
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = if (reduceMotion) fadeIn()
                else scaleIn(MotionTokens.bouncyFinite(false)) + fadeIn(MotionTokens.fastFinite(false)),
                exit = if (reduceMotion) fadeOut() else scaleOut() + fadeOut()
            ) {
                Column(
                    Modifier
                        .statusBarsPadding()
                        .padding(top = 64.dp)
                        .widthIn(min = 200.dp, max = 224.dp)
                        .clip(RoundedCornerShape(tokens.radii.lg))
                        .background(tokens.colors.surface)
                        .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .padding(8.dp)
                ) {
                    BasicText(
                        "Add to note",
                        style = AuraType.labelSm.copy(color = tokens.colors.textSecondary),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                    MenuRow(Glyph.IMAGE, "Photo", onPhoto)
                    MenuRow(Glyph.PAPERCLIP, "File", onFile)
                    if (canPaste) MenuRow(Glyph.DOCUMENT, "Paste image", onPasteImage)
                }
            }
        }
    }
}

@Composable
private fun MenuRow(glyph: Glyph, label: String, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(tokens.radii.md))
            .auraPress(interaction, tint = true)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(tokens.radii.pill))
                .background(tokens.colors.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { AuraGlyph(glyph, tokens.colors.accent, Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        BasicText(label, style = AuraType.body.copy(color = tokens.colors.textPrimary))
    }
}
