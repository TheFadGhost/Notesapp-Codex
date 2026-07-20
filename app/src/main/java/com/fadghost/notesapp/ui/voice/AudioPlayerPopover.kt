package com.fadghost.notesapp.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.audio.AudioStorage
import com.fadghost.notesapp.data.db.entity.AudioAttachment
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/**
 * Aura popover player for a voice attachment (PLAN.md §2.3): play/pause, scrubber,
 * duration, per-note audio size and a delete-audio button. Springy card over a
 * dismiss scrim; the [AudioPlayerController] is created on open and released on close.
 */
@Composable
fun AudioPlayerPopover(
    attachment: AudioAttachment?,
    noteBytes: Long,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    val visible = attachment != null

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                if (attachment != null) {
                    PlayerCard(attachment, noteBytes, onDelete, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun PlayerCard(
    attachment: AudioAttachment,
    noteBytes: Long,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    val controller = remember(attachment.id) { AudioPlayerController(attachment.segments) }
    var playing by remember(attachment.id) { mutableStateOf(false) }
    var positionMs by remember(attachment.id) { mutableFloatStateOf(0f) }
    var durationMs by remember(attachment.id) { mutableFloatStateOf(attachment.durationMs.toFloat().coerceAtLeast(1f)) }

    DisposableEffect(attachment.id) {
        onDispose { controller.release() }
    }

    // Poll position while playing.
    LaunchedEffect(playing, attachment.id) {
        while (playing) {
            positionMs = controller.positionMs().toFloat()
            val d = controller.currentDurationMs()
            if (d > 0) durationMs = d.toFloat()
            if (controller.finished) { playing = false; positionMs = durationMs }
            delay(60)
        }
    }

    Column(
        Modifier
            .padding(horizontal = 36.dp)
            .clip(RoundedCornerShape(tokens.radii.lg))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText("Voice note", style = AuraType.title.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(4.dp))
        BasicText(
            "${formatTime(attachment.durationMs)} · ${AudioStorage.formatSize(attachment.sizeBytes)}",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // -10s skip (IDEAS #32).
            SkipButton(label = "-10s") {
                controller.skip(-10_000)
                positionMs = controller.positionMs().toFloat()
            }
            Spacer(Modifier.size(8.dp))
            // Play / pause.
            val playInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .auraPress(playInteraction, tint = true)
                    .background(tokens.colors.accent)
                    .clickable(interactionSource = playInteraction, indication = null) {
                        controller.toggle(); playing = controller.isPlaying
                    },
                contentAlignment = Alignment.Center
            ) {
                if (playing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(width = 5.dp, height = 18.dp).background(tokens.colors.background))
                        Box(Modifier.size(width = 5.dp, height = 18.dp).background(tokens.colors.background))
                    }
                } else {
                    AuraGlyph(Glyph.CHEVRON, tokens.colors.background, Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.size(8.dp))
            // +10s skip (IDEAS #32).
            SkipButton(label = "+10s") {
                controller.skip(10_000)
                positionMs = controller.positionMs().toFloat()
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Scrubber(
                    fraction = (positionMs / durationMs).coerceIn(0f, 1f),
                    onSeek = { f ->
                        val target = (f * durationMs).toInt()
                        controller.seekTo(target)
                        positionMs = target.toFloat()
                    }
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(formatTime(positionMs.toLong()), style = AuraType.label.copy(color = tokens.colors.textSecondary))
                    // Speed chip (IDEAS #32): 1× → 1.25× → 1.5× → 2× → 1×.
                    var speed by remember(attachment.id) { mutableFloatStateOf(1f) }
                    val speedInteraction = remember { MutableInteractionSource() }
                    BasicText(
                        formatSpeed(speed),
                        style = AuraType.label.copy(color = tokens.colors.accent),
                        modifier = Modifier
                            .clip(RoundedCornerShape(tokens.radii.pill))
                            .auraPress(speedInteraction)
                            .background(tokens.colors.accent.copy(alpha = 0.12f))
                            .clickable(speedInteraction, indication = null) { speed = controller.cycleSpeed() }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                    BasicText(formatTime(attachment.durationMs), style = AuraType.label.copy(color = tokens.colors.textSecondary))
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        BasicText(
            "This note's audio: ${AudioStorage.formatSize(noteBytes)}",
            style = AuraType.label.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Close", filled = false, onClick = onDismiss)
            SoftButton("Delete audio", filled = true, onClick = { controller.release(); onDelete(attachment.id) })
        }
    }
}

@Composable
private fun Scrubber(fraction: Float, onSeek: (Float) -> Unit) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.97f else 1f,
        animationSpec = tween(if (reduceMotion) 0 else 100),
        label = "scrubberPressScale"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { pos -> onSeek((pos.x / size.width).coerceIn(0f, 1f)) }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(tokens.colors.outline)
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(CircleShape)
                .background(tokens.colors.accent)
        )
    }
}

/** Small circular ±10s control matching the player's soft chip language. */
@Composable
private fun SkipButton(label: String, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .auraPress(interaction)
            .background(tokens.colors.accent.copy(alpha = 0.12f))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = AuraType.labelSm.copy(color = tokens.colors.accent))
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toLong().toFloat()) "${speed.toLong()}×" else "$speed×"

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
