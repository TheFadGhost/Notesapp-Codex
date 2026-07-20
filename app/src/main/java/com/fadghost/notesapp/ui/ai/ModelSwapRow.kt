package com.fadghost.notesapp.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.ai.AiPreferences
import com.fadghost.notesapp.ui.components.FlowChips
import com.fadghost.notesapp.ui.components.PlainChip
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/**
 * "Retry with a different model" chips shown under an AI error (IDEAS #28).
 * Converts a "model unavailable" dead-end into a one-tap swap: picking a chip
 * switches the default text model (Settings-visible, honest) and immediately
 * retries the failed operation. Chips come from the curated Recommended list,
 * excluding whichever model just failed.
 */
@Composable
fun ModelSwapRow(currentModel: String, onPick: (String) -> Unit) {
    val tokens = Aura.tokens
    val options = AiPreferences.RECOMMENDED_TEXT_MODELS
        .filterNot { it.first == currentModel }
        .take(3)
    if (options.isEmpty()) return
    Column {
        BasicText(
            "Or retry with a different model:",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )
        Spacer(Modifier.size(8.dp))
        FlowChips {
            options.forEach { (id, name) ->
                PlainChip(label = shortModelName(name), selected = false, onClick = { onPick(id) })
            }
        }
    }
}

/** "Z.AI: GLM 5.2" → "GLM 5.2" — the vendor prefix is noise on a small chip. */
private fun shortModelName(display: String): String =
    display.substringAfter(": ", display)
