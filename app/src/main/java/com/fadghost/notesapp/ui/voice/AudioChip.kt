package com.fadghost.notesapp.ui.voice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.db.entity.AudioAttachment
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura

/**
 * The small circular audio chip drawn at the start of a transcript line (PLAN.md
 * §2.3 — user's explicit design decision). Rendered as a filled accent circle with a
 * tiny three-bar waveform. Tapping opens the Aura popover player.
 */
@Composable
fun AudioChip(size: androidx.compose.ui.unit.Dp = 20.dp, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .auraPress(interaction, tint = true)
            .background(tokens.colors.accent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Play voice note" }
    ) {
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension
            val c = tokens.colors.background
            val w = s * 0.09f
            fun bar(cx: Float, half: Float) = drawLine(
                c,
                Offset(cx, s * 0.5f - half),
                Offset(cx, s * 0.5f + half),
                strokeWidth = w,
                cap = StrokeCap.Round
            )
            bar(s * 0.36f, s * 0.12f)
            bar(s * 0.50f, s * 0.22f)
            bar(s * 0.64f, s * 0.15f)
        }
    }
}

/**
 * Positions an [AudioChip] at each attachment's transcript-line start over the body
 * text field, mirroring the checkbox-overlay technique in the editor. Uses the body
 * [TextLayoutResult] to resolve the caret offset to a pixel rect. Tapping a chip
 * invokes [onOpen] with that attachment.
 */
@Composable
fun AudioChipOverlay(
    attachments: List<AudioAttachment>,
    layout: TextLayoutResult?,
    textLength: Int,
    onOpen: (AudioAttachment) -> Unit
) {
    val layoutResult = layout ?: return
    val density = LocalDensity.current
    attachments.forEach { att ->
        val offset = att.transcriptStart.coerceIn(0, textLength)
        val rect = runCatching { layoutResult.getBoundingBox(offset) }.getOrNull() ?: return@forEach
        with(density) {
            Box(
                Modifier.offset(
                    x = (rect.left - 24.dp.toPx()).coerceAtLeast(0f).toDp(),
                    y = rect.top.toDp()
                )
            ) {
                AudioChip(size = 18.dp) { onOpen(att) }
            }
        }
    }
}
