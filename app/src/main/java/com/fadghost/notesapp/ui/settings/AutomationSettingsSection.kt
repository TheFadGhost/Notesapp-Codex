package com.fadghost.notesapp.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fadghost.notesapp.data.webhook.WebhookTokenStore
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraToggle
import com.fadghost.notesapp.ui.components.SectionCard
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/**
 * Settings → Automation (v4). Enable/disable the embedded webhook, see where it is
 * listening, copy the bearer key (masked, tap-to-copy), rotate it behind a two-step
 * danger confirm, and opt into LAN exposure. Custom Aura surfaces only — no Material.
 * The full manual for AI agents lives at WEBHOOK.md in the repo.
 */
@Composable
fun AutomationSettingsSection(viewModel: AutomationSettingsViewModel = hiltViewModel()) {
    val tokens = Aura.tokens
    val clipboard = LocalClipboardManager.current

    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val allowLan by viewModel.allowLan.collectAsStateWithLifecycle()
    val token by viewModel.token.collectAsStateWithLifecycle()

    var copied by remember { mutableStateOf(false) }
    var confirmNewKey by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { kotlinx.coroutines.delay(1600); copied = false }
    }

    SectionCard(title = "Automation") {
        // Enable row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                BasicText(
                    "Automation webhook",
                    style = AuraType.body.copy(color = tokens.colors.textPrimary)
                )
                Spacer(Modifier.height(2.dp))
                BasicText(
                    if (enabled) {
                        "Listening on ${viewModel.hostFor(allowLan)}:${viewModel.port}"
                    } else {
                        "Off"
                    },
                    style = AuraType.label.copy(color = tokens.colors.textSecondary)
                )
            }
            AuraToggle(checked = enabled, onCheckedChange = viewModel::setEnabled)
        }

        if (enabled) {
            DividerLineAutomation()

            // Bearer key: masked, tap-to-copy.
            BasicText(
                "Bearer key",
                style = AuraType.labelSm.copy(color = tokens.colors.textSecondary)
            )
            Spacer(Modifier.height(6.dp))
            val copyInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.radii.sm))
                    .auraPress(copyInteraction)
                    .background(tokens.colors.background)
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                    .clickable(copyInteraction, indication = null) {
                        token?.let {
                            clipboard.setText(AnnotatedString(it))
                            copied = true
                        }
                    }
                    .padding(12.dp)
            ) {
                BasicText(
                    if (copied) "Copied to clipboard" else "${WebhookTokenStore.redact(token)}   ·   tap to copy",
                    style = AuraType.body.copy(color = tokens.colors.textPrimary)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Rotate key: two-step danger confirm (regenerating breaks integrations).
            if (!confirmNewKey) {
                SoftButton(
                    label = "New key",
                    filled = false,
                    danger = true,
                    onClick = { confirmNewKey = true }
                )
            } else {
                BasicText(
                    "Generate a new key? Every automation using the old key stops working until you update it.",
                    style = AuraType.label.copy(color = tokens.colors.danger)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Cancel", filled = false, onClick = { confirmNewKey = false })
                    SoftButton(
                        label = "Regenerate",
                        filled = true,
                        danger = true,
                        onClick = {
                            viewModel.regenerateToken()
                            confirmNewKey = false
                            copied = false
                        }
                    )
                }
            }

            DividerLineAutomation()

            // Allow local network sub-toggle + warning.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    BasicText(
                        "Allow local network",
                        style = AuraType.body.copy(color = tokens.colors.textPrimary)
                    )
                    Spacer(Modifier.height(2.dp))
                    BasicText(
                        "Binds 0.0.0.0 so other devices on your Wi-Fi can reach it. Leave off unless you need it.",
                        style = AuraType.label.copy(color = tokens.colors.textSecondary)
                    )
                }
                AuraToggle(checked = allowLan, onCheckedChange = viewModel::setAllowLan)
            }
        }

        DividerLineAutomation()

        BasicText(
            "Hand an AI assistant or tool the key and the manual at WEBHOOK.md to schedule reminders, create notes and more. The server only runs while this app is open.",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )
    }
}

@Composable
private fun DividerLineAutomation() {
    val tokens = Aura.tokens
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.colors.outline))
    Spacer(Modifier.height(12.dp))
}
