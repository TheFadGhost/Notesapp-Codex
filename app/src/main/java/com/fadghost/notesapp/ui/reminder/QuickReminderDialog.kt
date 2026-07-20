package com.fadghost.notesapp.ui.reminder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraDateTimePicker
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Custom Aura Quick-reminder dialog (PLAN.md §4/§8 — no stock pickers). Title
 * field + [AuraDateTimePicker]; creates a Reminder row on confirm.
 */
@Composable
fun QuickReminderDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
    viewModel: QuickReminderViewModel = hiltViewModel()
) {
    val tokens = Aura.tokens
    androidx.activity.compose.BackHandler(enabled = visible) { onDismiss() }
    val zone = ZoneId.systemDefault()
    // Saveable: a half-filled reminder must survive rotation / process death.
    var title by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var dt by androidx.compose.runtime.saveable.rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = { it.toString() },
            restore = { LocalDateTime.parse(it) }
        )
    ) { mutableStateOf(LocalDateTime.now(zone).plusHours(1).withSecond(0).withNano(0)) }

    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .imePadding()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible,
                enter = scaleIn(MotionTokens.bouncyFinite(LocalReduceMotion.current)) + fadeIn(MotionTokens.fastFinite(LocalReduceMotion.current)),
                exit = scaleOut() + fadeOut()
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(tokens.radii.lg))
                        .background(tokens.colors.surface)
                        .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .padding(22.dp)
                ) {
                    BasicText("Quick reminder", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
                    Spacer(Modifier.size(14.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(tokens.radii.sm))
                            .background(tokens.colors.background)
                            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                            .padding(12.dp)
                    ) {
                        if (title.isEmpty()) {
                            BasicText("What should I remind you about?", style = AuraType.body.copy(color = tokens.colors.textSecondary))
                        }
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it },
                            singleLine = true,
                            textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                            cursorBrush = SolidColor(tokens.colors.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.size(16.dp))
                    AuraDateTimePicker(value = dt, onChange = { dt = it })

                    val whenMillis = dt.atZone(zone).toInstant().toEpochMilli()
                    val validation = QuickReminderViewModel.validate(title, whenMillis, System.currentTimeMillis())
                    val canCreate = validation == ReminderValidation.Ok
                    if (validation == ReminderValidation.PastTime) {
                        Spacer(Modifier.size(10.dp))
                        BasicText(
                            "That time has already passed — pick a time in the future.",
                            style = AuraType.label.copy(color = tokens.colors.danger)
                        )
                    }
                    if (title.isBlank()) {
                        Spacer(Modifier.size(10.dp))
                        BasicText(
                            "Give it a title to create. It lands in your Calendar with an alarm.",
                            style = AuraType.label.copy(color = tokens.colors.textSecondary)
                        )
                    }

                    Spacer(Modifier.size(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Cancel", filled = false, onClick = onDismiss)
                        Spacer(Modifier.weight(1f))
                        SoftButton(
                            "Create",
                            filled = true,
                            modifier = Modifier.graphicsLayer { alpha = if (canCreate) 1f else 0.4f },
                            onClick = {
                                if (canCreate) {
                                    viewModel.create(title, whenMillis) { onCreated() }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
