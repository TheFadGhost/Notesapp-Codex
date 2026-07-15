package com.fadghost.notesapp.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens

/**
 * The single custom Aura on/off switch (no Material Switch). Pill track that lerps
 * outline → accent, a spring-driven knob, and the shared [auraPress] feedback. Used by
 * every settings toggle; previously duplicated in AppearanceSettingsSection and
 * DiarySettingsSection (P2-2 — consolidated here).
 */
@Composable
fun AuraToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val t by animateFloatAsState(
        if (checked) 1f else 0f,
        MotionTokens.bouncy(reduceMotion),
        label = "toggle"
    )
    val trackW = 46.dp
    val knob = 22.dp
    val knobX by animateDpAsState(
        if (checked) trackW - knob - 3.dp else 3.dp,
        MotionTokens.bouncy(reduceMotion),
        label = "knob"
    )
    val track = lerp(tokens.colors.textSecondary.copy(alpha = 0.35f), tokens.colors.accent, t)
    Box(
        modifier
            // Keep the switch artwork at 46x28 while exposing the platform-recommended
            // 48dp semantic/touch target around it.
            .size(width = 48.dp, height = 48.dp)
            .auraPress(interaction)
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(tokens.radii.pill))
                .background(track)
                .size(width = trackW, height = 28.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .padding(start = knobX)
                    .size(knob)
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .background(tokens.colors.surface)
            )
        }
    }
}
