package com.fadghost.notesapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Fully custom date-time picker (PLAN.md §4/§8 — "custom, no stock pickers").
 * A row of stepper columns (month / day / year / hour / minute); each field has
 * up/down chevrons and springs its value. Emits epoch millis in [zone]. Reused by
 * the Extract "Edit" card and the Quick-reminder dialog.
 */
@Composable
fun AuraDateTimePicker(
    value: LocalDateTime,
    onChange: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault()
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperColumn(
            label = "Mon",
            display = value.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            onUp = { onChange(value.plusMonths(1)) },
            onDown = { onChange(value.minusMonths(1)) }
        )
        StepperColumn(
            label = "Day",
            display = value.dayOfMonth.toString().padStart(2, '0'),
            onUp = { onChange(value.plusDays(1)) },
            onDown = { onChange(value.minusDays(1)) }
        )
        StepperColumn(
            label = "Year",
            display = value.year.toString(),
            onUp = { onChange(value.plusYears(1)) },
            onDown = { onChange(value.minusYears(1)) }
        )
        Spacer(Modifier.width(2.dp))
        StepperColumn(
            label = "Hr",
            display = value.hour.toString().padStart(2, '0'),
            onUp = { onChange(value.plusHours(1)) },
            onDown = { onChange(value.minusHours(1)) }
        )
        StepperColumn(
            label = "Min",
            display = value.minute.toString().padStart(2, '0'),
            onUp = { onChange(value.plusMinutes(5)) },
            onDown = { onChange(value.minusMinutes(5)) }
        )
    }
}

@Composable
private fun StepperColumn(
    label: String,
    display: String,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(label, style = AuraType.label.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(4.dp))
        StepButton(Glyph.CHEVRON_UP, onUp)
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .width(48.dp)
                .clip(RoundedCornerShape(tokens.radii.sm))
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                display,
                style = AuraType.body.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
            )
        }
        StepButton(Glyph.CHEVRON_DOWN, onDown)
    }
}

@Composable
private fun StepButton(glyph: Glyph, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(width = 48.dp, height = 30.dp)
            .clip(RoundedCornerShape(tokens.radii.sm))
            .auraPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(glyph, tokens.colors.accent, Modifier.size(20.dp))
    }
}
