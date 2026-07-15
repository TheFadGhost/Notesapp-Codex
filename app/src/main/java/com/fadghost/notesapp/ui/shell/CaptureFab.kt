package com.fadghost.notesapp.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.components.rememberAuraHaptics
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens
import kotlinx.coroutines.launch

data class CaptureAction(val label: String, val subtitle: String)

/** Distinct glyph per capture action (P1-2 — no more four identical "+" icons). */
private fun glyphForAction(label: String): Glyph = when (label) {
    "New note" -> Glyph.DOCUMENT
    "New diary entry" -> Glyph.BOOK
    "Voice ramble" -> Glyph.MIC
    "Quick reminder" -> Glyph.CLOCK
    else -> Glyph.PLUS
}

private val captureActions = listOf(
    CaptureAction("New note", "Blank markdown note"),
    CaptureAction("New diary entry", "Today's journal"),
    CaptureAction("Voice ramble", "Record and transcribe"),
    CaptureAction("Quick reminder", "Fire at a clock time")
)

private val FAB_SIZE = NavFabSize
private val PANEL_WIDTH = 280.dp

/**
 * Standalone bottom-right FAB (V2-SPEC item 4). 60dp accent circle. Its action is
 * contextual (the shell decides); a long-press always opens the full capture panel. It
 * remains visible during scrolling so the primary creation action is always within reach.
 */
@Composable
fun ContextualFab(
    panelOpen: Boolean,
    onPrimary: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberAuraHaptics()
    val density = LocalDensity.current
    // "+" rotates toward "x" while the panel is open.
    val rotation by animateFloatAsState(
        if (panelOpen) 45f else 0f,
        MotionTokens.press(reduceMotion),
        label = "fabRotate"
    )
    // Shared Aura press feedback — detectTapGestures drives a pressed flag (it has no
    // InteractionSource), honouring reduce-motion like [auraPress] elsewhere.
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        if (pressed && !reduceMotion) 0.92f else 1f,
        MotionTokens.press(reduceMotion),
        label = "fabPress"
    )
    val pressLift by animateFloatAsState(
        if (pressed && !reduceMotion) with(density) { (-6).dp.toPx() } else 0f,
        MotionTokens.press(reduceMotion),
        label = "fabPressLift"
    )

    Box(
        modifier = modifier
            .size(FAB_SIZE)
            .graphicsLayer {
                translationY = pressLift
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            // Translucent "frosted glass" accent (item 2): ~0.66 alpha reads as glass over
            // scrolling content on all four themes while the white glyph stays legible; the
            // accent-tinted rim keeps the circle defined against light backgrounds.
            .background(tokens.colors.accent.copy(alpha = 0.66f))
            .border(1.dp, tokens.colors.accent.copy(alpha = 0.5f), CircleShape)
            .pointerInput(Unit) {
                // Tap = contextual primary; long-press = full capture panel.
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { haptics.confirm(); onPrimary() },
                    onLongPress = { haptics.confirm(); onLongPress() }
                )
            }
            .semantics { contentDescription = "Capture" },
        contentAlignment = Alignment.Center
    ) {
        PlusGlyph(
            color = Color.White,
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer { rotationZ = rotation }
        )
    }
}

/**
 * FAB-anchored capture panel (V2-SPEC item 5, motion.md §2). Replaces the old
 * full-width bottom sheet. A ~280dp card grows out of the FAB: overshoot lives ONLY
 * in scale (absorbed by internal padding), rise + alpha are critically damped, so the
 * clip rect never moves past its bounds — the old fling-too-high / cut-off bug is
 * impossible by construction. Items stagger 40 + 30·i ms with an 8dp rise.
 */
@Composable
fun CapturePanel(
    visible: Boolean,
    navInset: Dp,
    onDismiss: () -> Unit,
    onAction: (CaptureAction) -> Unit
) {
    val tokens = Aura.tokens
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberAuraHaptics()
    val scope = rememberCoroutineScope()

    // Keep composed through the exit animation; unmount only after it finishes.
    var rendered by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) rendered = true }
    if (!rendered) return

    val riseStart = with(density) { 12.dp.toPx() }
    val itemRise = with(density) { 8.dp.toPx() }
    val scale = remember { Animatable(0.90f) }
    val alpha = remember { Animatable(0f) }
    val riseY = remember { Animatable(riseStart) }
    val itemAlpha = remember { List(captureActions.size) { Animatable(0f) } }
    val itemY = remember { List(captureActions.size) { Animatable(itemRise) } }

    LaunchedEffect(visible) {
        if (visible) {
            if (reduceMotion) {
                launch { alpha.animateTo(1f, tween(90)) }
                launch { scale.animateTo(1f, tween(90)) }
                launch { riseY.animateTo(0f, tween(90)) }
                itemAlpha.forEach { launch { it.animateTo(1f, tween(90)) } }
                itemY.forEach { launch { it.animateTo(0f, tween(0)) } }
            } else {
                launch { alpha.animateTo(1f, tween(140)) }
                launch { riseY.animateTo(0f, MotionTokens.PanelRise) }
                launch { scale.animateTo(1f, MotionTokens.PanelScale) }
                captureActions.indices.forEach { i ->
                    val d = 40 + i * 30
                    launch { itemAlpha[i].animateTo(1f, tween(130, delayMillis = d)) }
                    launch {
                        kotlinx.coroutines.delay(d.toLong())
                        itemY[i].animateTo(0f, MotionTokens.PanelItem)
                    }
                }
            }
        } else {
            // Exit — no bounce, snappier than open (motion.md §2.6).
            val dur = if (reduceMotion) 0 else 150
            launch { alpha.animateTo(0f, tween(dur)) }
            launch { scale.animateTo(0.92f, tween(dur)) }
            riseY.animateTo(riseStart, tween(dur))
            rendered = false
        }
    }

    fun requestClose() = onDismiss()

    Box(Modifier.fillMaxSize()) {
        // Scrim — cheap tint, tap to dismiss.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha.value }
                .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { requestClose() }
        )

        // The card, anchored at the FAB (bottom-right), sitting above it.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = navInset + 20.dp + FAB_SIZE + 12.dp)
                .width(PANEL_WIDTH)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.92f, 0.98f)
                    scaleX = scale.value
                    scaleY = scale.value
                    translationY = riseY.value
                    this.alpha = alpha.value
                }
                .clip(RoundedCornerShape(tokens.radii.lg))
                .background(tokens.colors.surfaceTranslucent)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                .padding(vertical = 8.dp)
        ) {
            captureActions.forEachIndexed { i, action ->
                ActionRow(
                    action = action,
                    modifier = Modifier.graphicsLayer {
                        this.alpha = itemAlpha[i].value
                        translationY = itemY[i].value
                    },
                    onClick = {
                        haptics.confirm()
                        onAction(action)
                        scope.launch { requestClose() }
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    action: CaptureAction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .auraPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .semantics { contentDescription = action.label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tokens.colors.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            AuraGlyph(glyphForAction(action.label), tokens.colors.accent, Modifier.size(20.dp))
        }
        Column {
            BasicText(
                text = action.label,
                style = AuraType.body.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Start)
            )
            BasicText(
                text = action.subtitle,
                style = AuraType.label.copy(color = tokens.colors.textSecondary)
            )
        }
    }
}
