package com.fadghost.notesapp.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.AuraToggle
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraSheetShadow

/**
 * Settings sections for the Diary (PLAN.md §7): a Privacy → biometric gate toggle
 * with honest copy, and a journaling-nudge toggle + custom Aura time picker. All
 * state is DataStore-backed via [DiarySettingsViewModel].
 */
@Composable
fun DiarySettingsSection(viewModel: DiarySettingsViewModel = hiltViewModel()) {
    val tokens = Aura.tokens
    val biometric by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val nudgeOn by viewModel.nudgeEnabled.collectAsStateWithLifecycle()
    val nudgeTime by viewModel.nudgeTime.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val notificationsGranted = remember(nudgeOn) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
        else ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    // --- Privacy ---
    SectionCardLocal(title = "Privacy") {
        ToggleRow(
            title = "Lock the Diary tab",
            subtitle = "Locks the diary screen. Data on disk is not encrypted.",
            checked = biometric,
            onToggle = viewModel::setBiometricEnabled
        )
    }

    Spacer(Modifier.height(16.dp))

    // --- Diary nudge ---
    SectionCardLocal(title = "Diary") {
        ToggleRow(
            title = "Daily journaling reminder",
            subtitle = if (nudgeOn && !notificationsGranted)
                "Enable notifications for this app in system settings to receive it."
            else "A gentle nudge to write, at a time you choose.",
            checked = nudgeOn,
            onToggle = viewModel::setNudgeEnabled
        )
        if (nudgeOn) {
            Spacer(Modifier.height(14.dp))
            BasicText("Reminder time", style = AuraType.label.copy(color = tokens.colors.textSecondary))
            Spacer(Modifier.height(10.dp))
            TimeStepper(
                hour = nudgeTime.hour,
                minute = nudgeTime.minute,
                onChange = viewModel::setNudgeTime
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .auraPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onToggle(!checked) }
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = AuraType.body.copy(color = tokens.colors.textPrimary))
            BasicText(subtitle, style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }
        Spacer(Modifier.width(12.dp))
        AuraToggle(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun TimeStepper(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(
            label = "Hour",
            display = hour.toString().padStart(2, '0'),
            onUp = { onChange((hour + 1) % 24, minute) },
            onDown = { onChange((hour + 23) % 24, minute) }
        )
        Field(
            label = "Min",
            display = minute.toString().padStart(2, '0'),
            onUp = { onChange(hour, (minute + 5) % 60) },
            onDown = { onChange(hour, (minute + 55) % 60) }
        )
    }
}

@Composable
private fun Field(label: String, display: String, onUp: () -> Unit, onDown: () -> Unit) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(label, style = AuraType.label.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(4.dp))
        StepBtn(Glyph.CHEVRON_UP, onUp)
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .width(56.dp)
                .clip(RoundedCornerShape(tokens.radii.sm))
                .background(tokens.colors.background)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(display, style = AuraType.titleSm.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center))
        }
        StepBtn(Glyph.CHEVRON_DOWN, onDown)
    }
}

@Composable
private fun StepBtn(glyph: Glyph, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(width = 56.dp, height = 44.dp)
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

@Composable
private fun SectionCardLocal(title: String, content: @Composable () -> Unit) {
    val tokens = Aura.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .auraSheetShadow(RoundedCornerShape(tokens.radii.md))
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .padding(16.dp)
    ) {
        BasicText(title.uppercase(), style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(12.dp))
        content()
    }
}
