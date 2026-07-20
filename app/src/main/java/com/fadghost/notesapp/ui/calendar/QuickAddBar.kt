package com.fadghost.notesapp.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.calendar.QuickAddParser
import com.fadghost.notesapp.calendar.QuickAddResult
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Local natural-language quick-add (PLAN.md §8 — "gym tomorrow 7am", offline, no
 * AI). Parses on every keystroke via [QuickAddParser] and, when something is
 * recognised, springs up a prefilled confirm chip (title + datetime + recurrence)
 * the user taps to create.
 */
@Composable
fun QuickAddBar(
    zone: ZoneId,
    onConfirm: (QuickAddResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    var text by remember { mutableStateOf("") }
    val parsed: QuickAddResult? = remember(text) {
        if (text.isBlank()) null else QuickAddParser.parse(text, LocalDateTime.now(zone))
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(tokens.radii.pill))
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuraGlyph(Glyph.PLUS, tokens.colors.accent, Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Box(Modifier.fillMaxWidth()) {
                if (text.isEmpty()) {
                    BasicText(
                        "Try \"gym tomorrow 7am\"",
                        style = AuraType.body.copy(color = tokens.colors.textSecondary)
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                    cursorBrush = SolidColor(tokens.colors.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        AnimatedVisibility(
            visible = parsed != null,
            enter = expandVertically(MotionTokens.mediumFinite(LocalReduceMotion.current)) + fadeIn(),
            exit = shrinkVertically(MotionTokens.mediumFinite(LocalReduceMotion.current)) + fadeOut()
        ) {
            val result = parsed ?: return@AnimatedVisibility
            ConfirmChip(result) {
                onConfirm(result)
                text = ""
            }
        }
    }
}

@Composable
private fun ConfirmChip(result: QuickAddResult, onAdd: () -> Unit) {
    val tokens = Aura.tokens
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.accent.copy(alpha = 0.12f))
            .border(1.dp, tokens.colors.accent.copy(alpha = 0.4f), RoundedCornerShape(tokens.radii.md))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(result.title, style = AuraType.body.copy(color = tokens.colors.textPrimary))
            val sub = buildString {
                append(result.dateTime.format(fmt))
                if (result.recurrence != com.fadghost.notesapp.data.db.entity.Recurrence.NONE) {
                    append(" · ").append(result.recurrence.shortLabel())
                }
            }
            BasicText(sub, style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }
        val addInteraction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .clip(RoundedCornerShape(tokens.radii.pill))
                .auraPress(addInteraction, tint = true)
                .background(tokens.colors.accent)
                .clickable(interactionSource = addInteraction, indication = null, onClick = onAdd)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            BasicText("Add", style = AuraType.label.copy(color = tokens.colors.background))
        }
    }
}
