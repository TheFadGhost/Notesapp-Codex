package com.fadghost.notesapp.ui.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.db.entity.Recurrence
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/** Segmented Aura control for the simple v1 repeat cycle (PLAN.md §8). */
@Composable
fun RecurrencePicker(
    value: Recurrence,
    onChange: (Recurrence) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.background)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Recurrence.entries.forEach { r ->
            Segment(label = r.shortLabel(), selected = r == value, onClick = { onChange(r) })
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tokens = Aura.tokens
    val bg by animateColorAsState(
        if (selected) tokens.colors.accent else tokens.colors.surface.copy(alpha = 0f),
        spring(stiffness = Spring.StiffnessMediumLow), label = "segbg"
    )
    val fg = if (selected) tokens.colors.background else tokens.colors.textSecondary
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = AuraType.label.copy(color = fg))
    }
}

fun Recurrence.shortLabel(): String = when (this) {
    Recurrence.NONE -> "Once"
    Recurrence.DAILY -> "Daily"
    Recurrence.WEEKLY -> "Weekly"
    Recurrence.MONTHLY -> "Monthly"
}
