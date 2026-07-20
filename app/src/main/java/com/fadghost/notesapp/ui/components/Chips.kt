package com.fadghost.notesapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraAccents
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState

/** Wrapping row of chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowChips(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

/** A tag chip with its accent colour dot; filled when [selected]. */
@Composable
fun TagChip(tag: Tag, selected: Boolean, onClick: (() -> Unit)? = null) {
    val tokens = Aura.tokens
    val dot = AuraAccents.resolve(tag.color, tokens.colors.accent)
    val reduceMotion = LocalReduceMotion.current
    val bg by animateColorAsState(
        if (selected) dot.copy(alpha = 0.18f) else tokens.colors.surface,
        MotionTokens.fast(reduceMotion), label = "tagChipBg"
    )
    val borderColor by animateColorAsState(
        if (selected) dot else tokens.colors.outline,
        MotionTokens.fast(reduceMotion), label = "tagChipBorder"
    )
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .then(if (onClick != null) Modifier.auraPress(interaction) else Modifier)
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(tokens.radii.pill))
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        BasicText(tag.name, style = AuraType.label.copy(color = tokens.colors.textPrimary))
    }
}

/** A plain text chip (folders, filters). */
@Composable
fun PlainChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val plainReduceMotion = LocalReduceMotion.current
    val bg by animateColorAsState(
        if (selected) tokens.colors.accent.copy(alpha = 0.9f) else tokens.colors.surface,
        MotionTokens.fast(plainReduceMotion), label = "plainChipBg"
    )
    val fg by animateColorAsState(
        if (selected) lerp(tokens.colors.textPrimary, tokens.colors.background, 0.9f) else tokens.colors.textSecondary,
        MotionTokens.fast(plainReduceMotion), label = "plainChipFg"
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(bg)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = AuraType.label.copy(color = fg))
    }
}
