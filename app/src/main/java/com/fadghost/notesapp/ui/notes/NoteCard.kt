package com.fadghost.notesapp.ui.notes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.FlowChips
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.TagChip
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.auraSheetShadow
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * A note card (PLAN.md §6): title, body preview, pinned badge, tag chips.
 * Horizontal swipe triggers pin (right) / archive (left) / delete (far left)
 * with haptics; long-press raises the context menu. Staggered spring entrance.
 */
@Composable
fun NoteCard(
    note: NoteCardUi,
    index: Int,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    query: String = ""
) {
    val tokens = Aura.tokens
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val reduceMotion = LocalReduceMotion.current
    // Shared press feedback — the card taps via detectTapGestures (no InteractionSource),
    // so drive a pressed flag through onPress and fold a subtle scale into its layer.
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        if (pressed && !reduceMotion) 0.98f else 1f,
        tween(if (reduceMotion) 0 else 100),
        label = "cardPress"
    )

    // Staggered entrance.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(note.id) {
        appear.snapTo(0f)
        kotlinx.coroutines.delay((index % 12) * 28L)
        appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }

    val triggerPx = 150f
    val deletePx = 320f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 40f
                translationX = offsetX.value
                scaleX = pressScale
                scaleY = pressScale
            }
            // Sheet-plane contact shadow (rasterised once; rides the swipe layer so it
            // never re-rasterises per frame — visual.md §2.3 perf note).
            .auraSheetShadow(RoundedCornerShape(tokens.radii.md))
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .pointerInput(note.id) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onOpen() },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                )
            }
            .pointerInput(note.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            when {
                                offsetX.value <= -deletePx -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    offsetX.animateTo(0f); onDelete()
                                }
                                offsetX.value <= -triggerPx -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    offsetX.animateTo(0f); onArchive()
                                }
                                offsetX.value >= triggerPx -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    offsetX.animateTo(0f); onPin()
                                }
                                else -> offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            }
                        }
                    }
                ) { _, dragAmount ->
                    scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                }
            }
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.pinned) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape)
                            .background(tokens.colors.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) { AuraGlyph(Glyph.PIN, tokens.colors.accent, Modifier.size(12.dp)) }
                    Spacer(Modifier.width(8.dp))
                }
                BasicText(
                    text = highlight(note.title.ifBlank { "Untitled" }, query, tokens.colors.accent),
                    style = AuraType.bodyLg.copy(color = tokens.colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (note.preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = highlight(note.preview, query, tokens.colors.accent),
                    style = AuraType.bodySm.copy(color = tokens.colors.textSecondary),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (note.tags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowChips { note.tags.forEach { TagChip(tag = it, selected = false) } }
            }
        }
    }
}

/** Highlight every query token occurrence in [text] with the accent colour. */
private fun highlight(text: String, query: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString {
    val tokens = Regex("""[\p{L}\p{N}]+""").findAll(query).map { it.value }.filter { it.length >= 2 }.toList()
    if (tokens.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    val ranges = ArrayList<IntRange>()
    for (t in tokens) {
        val needle = t.lowercase()
        var from = 0
        while (true) {
            val i = lower.indexOf(needle, from)
            if (i < 0) break
            ranges += i until (i + needle.length)
            from = i + needle.length
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { r ->
            addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
        }
    }
}
