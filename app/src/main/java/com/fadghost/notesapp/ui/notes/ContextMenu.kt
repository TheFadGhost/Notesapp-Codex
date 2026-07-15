package com.fadghost.notesapp.ui.notes

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens

data class ContextMenuItem(
    val glyph: Glyph,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Custom floating context menu (PLAN.md §6): Aura-styled surface with staggered
 * spring item entrance. Dismisses on scrim tap.
 */
@Composable
fun NoteContextMenu(
    items: List<ContextMenuItem>,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = tokens.elevation.scrim))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(min = 220.dp, max = 280.dp)
                .clip(RoundedCornerShape(tokens.radii.lg))
                .background(tokens.colors.surfaceTranslucent)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(vertical = 8.dp)
        ) {
            items.forEachIndexed { index, item ->
                MenuRow(item = item, index = index, onDismiss = onDismiss)
            }
        }
    }
}

@Composable
private fun MenuRow(item: ContextMenuItem, index: Int, onDismiss: () -> Unit) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    val appear = remember { Animatable(0f) }
    LaunchedEffect(index, reduceMotion) {
        if (!reduceMotion) kotlinx.coroutines.delay(index * 32L)
        appear.animateTo(1f, MotionTokens.bouncy(reduceMotion))
    }
    val color = if (item.danger) tokens.colors.danger else tokens.colors.textPrimary
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .graphicsLayer {
                alpha = appear.value
                translationX = (1f - appear.value) * -24f
            }
            .auraPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { item.onClick(); onDismiss() }
            )
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        AuraGlyph(item.glyph, color, Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        BasicText(item.label, style = AuraType.body.copy(color = color))
    }
}
