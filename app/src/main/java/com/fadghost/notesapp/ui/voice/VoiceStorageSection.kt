package com.fadghost.notesapp.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.data.audio.AudioStorage
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraSheetShadow

/**
 * Settings → Storage card (PLAN.md §6): total audio-attachment size plus a
 * clear-orphans button. Custom Aura surface, no Material.
 */
@Composable
fun VoiceStorageSection(viewModel: VoiceStorageViewModel = hiltViewModel()) {
    val tokens = Aura.tokens
    val total by viewModel.totalBytes.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .auraSheetShadow(RoundedCornerShape(tokens.radii.md))
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .padding(16.dp)
    ) {
        BasicText("STORAGE", style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("Voice attachments", style = AuraType.bodyLg.copy(color = tokens.colors.textPrimary))
                BasicText(AudioStorage.formatSize(total), style = AuraType.bodySm.copy(color = tokens.colors.textSecondary))
            }
            SoftButton("Clear orphans", filled = false, onClick = viewModel::clearOrphans)
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.colors.outline))
            Spacer(Modifier.height(8.dp))
            BasicText(it, style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }
    }
}
