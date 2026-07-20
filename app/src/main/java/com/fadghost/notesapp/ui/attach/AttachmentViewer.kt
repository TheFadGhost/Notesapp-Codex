package com.fadghost.notesapp.ui.attach

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.db.entity.Attachment
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

/**
 * Fullscreen image viewer (M-A part 5): pinch-zoom + double-tap zoom, pan when zoomed,
 * drag-down-to-dismiss with a fading scrim, spring settling consistent with the app.
 * Reduce-motion snaps instead of springing and skips the fade. A close button gives a
 * non-gesture dismiss path (accessibility).
 */
@Composable
fun AttachmentViewer(attachment: Attachment?, onDismiss: () -> Unit) {
    val visible = attachment != null && attachment.isImage
    androidx.activity.compose.BackHandler(enabled = visible) { onDismiss() }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        if (attachment != null) ViewerContent(attachment, onDismiss)
    }
}

@Composable
private fun ViewerContent(att: Attachment, onDismiss: () -> Unit) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val viewportW = with(density) { config.screenWidthDp.dp.toPx() }
    val viewportH = with(density) { config.screenHeightDp.dp.toPx() }
    val dismissThreshold = viewportH * 0.22f

    val scale = remember { Animatable(1f) }
    val tx = remember { Animatable(0f) }
    val ty = remember { Animatable(0f) }
    val dismiss = remember { Animatable(0f) } // downward drag progress (px)
    val zoomed by remember { derivedStateOf { scale.value > 1.02f } }

    val thumb = rememberThumbnail(att.path, reqPx = 2200)
    val scrimAlpha = (1f - (dismiss.value / (dismissThreshold * 3f)).coerceIn(0f, 0.8f))

    suspend fun settleTo(s: Float, x: Float, y: Float) {
        if (reduceMotion) {
            scale.snapTo(s); tx.snapTo(x); ty.snapTo(y)
        } else {
            val spec = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
            scope.launch { scale.animateTo(s, spec) }
            scope.launch { tx.animateTo(x, spec) }
            scope.launch { ty.animateTo(y, spec) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha))
            // Double-tap toggles zoom, centred on the tap point.
            .pointerInput(att.id) {
                detectTapGestures(onDoubleTap = { pos ->
                    scope.launch {
                        if (zoomed) settleTo(1f, 0f, 0f)
                        else {
                            val target = 2.5f
                            val nx = (viewportW / 2f - pos.x) * (target - 1f)
                            val ny = (viewportH / 2f - pos.y) * (target - 1f)
                            settleTo(target, nx, ny)
                        }
                    }
                })
            }
            // Pinch-zoom + pan (pan only meaningful when zoomed).
            .pointerInput(att.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val ns = (scale.value * zoom).coerceIn(1f, 5f)
                    scope.launch { scale.snapTo(ns) }
                    if (ns > 1.02f) {
                        val maxX = (viewportW * (ns - 1f)) / 2f
                        val maxY = (viewportH * (ns - 1f)) / 2f
                        scope.launch { tx.snapTo((tx.value + pan.x).coerceIn(-maxX, maxX)) }
                        scope.launch { ty.snapTo((ty.value + pan.y).coerceIn(-maxY, maxY)) }
                    }
                }
            }
            // Drag-down-to-dismiss, active only at 1x so it never fights panning.
            .pointerInput(zoomed) {
                if (!zoomed) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dismiss.value > dismissThreshold) onDismiss()
                            else scope.launch {
                                if (reduceMotion) dismiss.snapTo(0f)
                                else dismiss.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium))
                            }
                        }
                    ) { _, dy ->
                        scope.launch { dismiss.snapTo((dismiss.value + dy).coerceAtLeast(0f)) }
                    }
                }
            }
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = att.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationX = tx.value
                        translationY = ty.value + dismiss.value
                    }
            )
        } else {
            BasicText(
                "Can't open this image.",
                style = AuraType.body.copy(color = Color.White),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // OCR "Copy text" pill (IDEAS #59) — surfaces the already-indexed text so it can
        // finally leave the image. Only shown when indexing actually produced text.
        val ocr = att.ocrText?.trim().orEmpty()
        if (ocr.isNotEmpty()) {
            val clipboard = LocalClipboardManager.current
            var copied by remember(att.id) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    kotlinx.coroutines.delay(1600)
                    copied = false
                }
            }
            val copyInteraction = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .background(tokens.colors.scrimTint.copy(alpha = 0.55f))
                    .auraPress(copyInteraction)
                    .clickable(copyInteraction, indication = null) {
                        clipboard.setText(AnnotatedString(ocr))
                        copied = true
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { contentDescription = if (copied) "Text copied" else "Copy text from image" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuraGlyph(if (copied) Glyph.CHECK else Glyph.COPY, Color.White, Modifier.size(16.dp))
                Box(Modifier.width(8.dp))
                BasicText(
                    if (copied) "Copied" else "Copy text",
                    style = AuraType.label.copy(color = Color.White)
                )
            }
        }

        // Close button (non-gesture dismiss).
        val closeInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(tokens.radii.pill))
                .background(tokens.colors.scrimTint.copy(alpha = 0.4f))
                .auraPress(closeInteraction)
                .clickable(closeInteraction, indication = null, onClick = onDismiss)
                .semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center
        ) { AuraGlyph(Glyph.CLOSE, Color.White, Modifier.size(20.dp)) }
    }
}
