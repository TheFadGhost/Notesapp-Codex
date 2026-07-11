package com.fadghost.notesapp.ui.shell

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.theme.Aura

/** Custom translucent floating pill nav bar (PLAN.md §4). No Material components. */
@Composable
fun AuraNavBar(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    Row(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.surfaceTranslucent)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        NavTab.entries.forEach { tab ->
            TabItem(
                tab = tab,
                selected = tab == selected,
                onClick = { onSelect(tab) }
            )
        }
        Spacer(Modifier.width(4.dp))
        CaptureButton(onClick = onCapture)
    }
}

@Composable
private fun TabItem(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tokens = Aura.tokens
    val bouncy = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    val highlight by animateFloatAsState(if (selected) 1f else 0f, bouncy, label = "highlight")
    val scale by animateFloatAsState(if (selected) 1.18f else 1f, bouncy, label = "scale")
    val iconColor = lerpColor(tokens.colors.textSecondary, tokens.colors.accent, highlight)

    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 48.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .clickable(
                interactionSource = remembered(),
                indication = null,
                onClick = onClick
            )
            .semantics {
                this.selected = selected
                contentDescription = tab.label
            },
        contentAlignment = Alignment.Center
    ) {
        // Soft pill highlight — blurred glow behind the active icon (blur region kept tiny).
        if (highlight > 0.01f) {
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 34.dp)
                    .graphicsLayer { alpha = highlight * 0.9f }
                    .blur(tokens.blur.navPill * highlight.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .background(tokens.colors.accent.copy(alpha = tokens.elevation.pressed + 0.06f))
            )
        }
        TabGlyph(
            icon = tab.icon,
            color = iconColor,
            modifier = Modifier
                .size(24.dp)
                .scale(scale)
        )
    }
}

@Composable
private fun CaptureButton(onClick: () -> Unit) {
    val tokens = Aura.tokens
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(tokens.colors.accent.copy(alpha = 0.85f))
            .border(1.dp, tokens.colors.outline, CircleShape)
            .clickable(
                interactionSource = remembered(),
                indication = null,
                onClick = onClick
            )
            .semantics { contentDescription = "Capture" },
        contentAlignment = Alignment.Center
    ) {
        PlusGlyph(color = Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun remembered(): MutableInteractionSource =
    androidx.compose.runtime.remember { MutableInteractionSource() }

private fun lerpColor(a: Color, b: Color, t: Float): Color =
    androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))
