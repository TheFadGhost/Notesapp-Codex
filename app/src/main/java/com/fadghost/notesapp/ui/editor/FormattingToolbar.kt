package com.fadghost.notesapp.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sticky editor toolbar with press-and-hold labels for its icon-only actions. */
@Composable
fun FormattingToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onHeading: () -> Unit,
    onChecklist: () -> Unit,
    onBullet: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    var pressedLabel by remember { mutableStateOf<String?>(null) }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        pressedLabel?.let { label ->
            BasicText(
                label,
                style = AuraType.labelSm.copy(color = tokens.colors.textPrimary),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-36).dp)
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .background(tokens.colors.surface)
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(tokens.radii.pill))
                .background(tokens.colors.surfaceTranslucent)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ToolButton(Glyph.BOLD, "Bold", true, onBold) { label, down -> pressedLabel = if (down) label else null }
            ToolButton(Glyph.ITALIC, "Italic", true, onItalic) { label, down -> pressedLabel = if (down) label else null }
            ToolButton(Glyph.HEADING, "Heading", true, onHeading) { label, down -> pressedLabel = if (down) label else null }
            ToolButton(Glyph.CHECKLIST, "Checklist", true, onChecklist) { label, down -> pressedLabel = if (down) label else null }
            ToolButton(Glyph.BULLET, "Bulleted list", true, onBullet) { label, down -> pressedLabel = if (down) label else null }
            ToolButton(Glyph.UNDO, "Undo", canUndo, onUndo) { label, down -> pressedLabel = if (down) label else null }
            ToolButton(Glyph.REDO, "Redo", canRedo, onRedo) { label, down -> pressedLabel = if (down) label else null }
        }
    }
}

@Composable
private fun ToolButton(
    glyph: Glyph,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onPressChanged: (String, Boolean) -> Unit
) {
    val tokens = Aura.tokens
    val color = if (enabled) tokens.colors.textPrimary else tokens.colors.textSecondary.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(tokens.radii.sm))
            .pointerInput(enabled, label) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        coroutineScope {
                            var becameLongPress = false
                            val timer = launch {
                                delay(android.view.ViewConfiguration.getLongPressTimeout().toLong())
                                becameLongPress = true
                                onPressChanged(label, true)
                            }
                            val released = tryAwaitRelease()
                            timer.cancel()
                            if (becameLongPress) onPressChanged(label, false)
                            else if (released) onClick()
                        }
                    }
                )
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                if (enabled) semanticsOnClick(label) { onClick(); true }
            },
        contentAlignment = Alignment.Center
    ) { AuraGlyph(glyph, color, Modifier.size(22.dp)) }
}
