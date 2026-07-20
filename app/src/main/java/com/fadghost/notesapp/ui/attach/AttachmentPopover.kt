package com.fadghost.notesapp.ui.attach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.audio.AudioStorage
import com.fadghost.notesapp.data.db.entity.Attachment
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens
import java.util.Locale

/**
 * Tap-an-attachment-chip popover (M-A part 4): a thumbnail (or file-type tile), the
 * file name + size, and the actions Expand / Annotate / Share / Remove. Springy card
 * over a dismiss scrim, consistent with the audio player popover; reduce-motion drops
 * the spring to a fade. Remove is undoable via the caller's snackbar.
 */
@Composable
fun AttachmentPopover(
    attachment: Attachment?,
    onExpand: (Attachment) -> Unit,
    onAnnotate: (Attachment) -> Unit,
    onShare: (Attachment) -> Unit,
    onOpenExternally: (Attachment) -> Unit,
    onRemove: (Attachment) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    val visible = attachment != null
    androidx.activity.compose.BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = if (reduceMotion) fadeIn()
                else scaleIn(MotionTokens.bouncyFinite(false)) + fadeIn(MotionTokens.fastFinite(false)),
                exit = if (reduceMotion) fadeOut() else scaleOut() + fadeOut()
            ) {
                if (attachment != null) {
                    Card(attachment, onExpand, onAnnotate, onShare, onOpenExternally, onRemove)
                }
            }
        }
    }
}

@Composable
private fun Card(
    att: Attachment,
    onExpand: (Attachment) -> Unit,
    onAnnotate: (Attachment) -> Unit,
    onShare: (Attachment) -> Unit,
    onOpenExternally: (Attachment) -> Unit,
    onRemove: (Attachment) -> Unit
) {
    val tokens = Aura.tokens
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .width(320.dp)
            .clip(RoundedCornerShape(tokens.radii.lg))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Preview.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 220.dp)
                .clip(RoundedCornerShape(tokens.radii.md))
                .background(tokens.colors.background),
            contentAlignment = Alignment.Center
        ) {
            if (att.isImage) {
                val thumb = rememberThumbnail(att.path, reqPx = 700)
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = att.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    FileTile(att, tokens.colors.textSecondary)
                }
            } else {
                FileTile(att, tokens.colors.accent)
            }
        }

        Spacer(Modifier.height(14.dp))
        BasicText(
            att.displayName,
            style = AuraType.body.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            "${AudioStorage.formatSize(att.sizeBytes)} · ${typeLabel(att)}",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (att.isImage) {
                ActionItem(Glyph.EXPAND, "Expand") { onExpand(att) }
                ActionItem(Glyph.PENCIL, "Annotate") { onAnnotate(att) }
            } else {
                ActionItem(Glyph.EXPAND, "Open") { onOpenExternally(att) }
            }
            ActionItem(Glyph.SHARE, "Share") { onShare(att) }
            ActionItem(Glyph.TRASH, "Remove", tint = tokens.colors.danger) { onRemove(att) }
        }
    }
}

@Composable
private fun FileTile(att: Attachment, tint: Color) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AuraGlyph(if (att.isImage) Glyph.IMAGE else Glyph.PAPERCLIP, tint, Modifier.size(44.dp))
        Spacer(Modifier.height(6.dp))
        BasicText(
            typeLabel(att),
            style = AuraType.labelSm.copy(color = tokens.colors.textSecondary)
        )
    }
}

@Composable
private fun ActionItem(glyph: Glyph, label: String, tint: Color? = null, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    val color = tint ?: tokens.colors.textPrimary
    Column(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.md))
            .auraPress(interaction, tint = true)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(tokens.radii.pill))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { AuraGlyph(glyph, color, Modifier.size(22.dp)) }
        Spacer(Modifier.height(6.dp))
        BasicText(label, style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
    }
}

private fun typeLabel(att: Attachment): String {
    val ext = att.displayName.substringAfterLast('.', "").uppercase(Locale.ROOT)
    if (ext.isNotBlank() && ext.length <= 5) return ext
    return att.mime.substringAfterLast('/').uppercase(Locale.ROOT)
}
