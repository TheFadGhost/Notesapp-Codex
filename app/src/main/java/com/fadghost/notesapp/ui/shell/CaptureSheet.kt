package com.fadghost.notesapp.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import kotlinx.coroutines.launch

data class CaptureAction(val label: String, val subtitle: String)

private val actions = listOf(
    CaptureAction("New note", "Blank markdown note"),
    CaptureAction("New diary entry", "Today's journal"),
    CaptureAction("Voice ramble", "Record and transcribe"),
    CaptureAction("Quick reminder", "Fire at a clock time")
)

/**
 * Custom spring-up bottom sheet (PLAN.md §4/§10): drag handle, velocity fling
 * dismiss, 4 capture actions. Actions are no-op stubs in M0.
 */
@Composable
fun CaptureSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAction: (CaptureAction) -> Unit
) {
    if (!visible) return
    val tokens = Aura.tokens
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val sheetHeightPx = with(density) { 360.dp.toPx() }
    val offsetY = remember { Animatable(sheetHeightPx) }
    val scrimAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { scrimAlpha.animateTo(1f, tween(220)) }
        offsetY.animateTo(
            0f,
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
        )
    }

    suspend fun close() {
        scope.launch { scrimAlpha.animateTo(0f, tween(180)) }
        offsetY.animateTo(sheetHeightPx, tween(200))
        onDismiss()
    }

    Box(Modifier.fillMaxSize()) {
        // Scrim (semi-opaque tint — still reads translucent, cheap vs full blur).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha.value }
                .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { scope.launch { close() } }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .graphicsLayer { translationY = offsetY.value }
                .clip(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                .background(tokens.colors.surfaceTranslucent)
                .border(
                    1.dp,
                    tokens.colors.outline,
                    RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg)
                )
                .padding(bottom = 28.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetY.value > sheetHeightPx * 0.28f) close()
                                else offsetY.animateTo(
                                    0f,
                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                )
                            }
                        }
                    ) { _, dragAmount ->
                        scope.launch {
                            val next = (offsetY.value + dragAmount).coerceAtLeast(0f)
                            offsetY.snapTo(next)
                        }
                    }
                }
        ) {
            Spacer(Modifier.height(12.dp))
            // Drag handle.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.textSecondary.copy(alpha = 0.5f))
            )
            Spacer(Modifier.height(16.dp))
            BasicText(
                text = "Capture",
                style = AuraType.title.copy(color = tokens.colors.textPrimary),
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            actions.forEach { action ->
                ActionRow(action = action, onClick = { onAction(action); scope.launch { close() } })
            }
        }
    }
}

@Composable
private fun ActionRow(action: CaptureAction, onClick: () -> Unit) {
    val tokens = Aura.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 12.dp)
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
            PlusGlyph(color = tokens.colors.accent, modifier = Modifier.size(20.dp))
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
