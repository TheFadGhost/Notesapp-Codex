package com.fadghost.notesapp.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/**
 * Pure find-within-note matching (IDEAS #15) — kept free of Compose so it is
 * unit-tested on the JVM. Literal, case-insensitive substring search.
 */
object FindInNote {

    /** All match ranges of [query] in [text] (start inclusive, end exclusive). */
    fun matches(text: String, query: String): List<IntRange> {
        val q = query.trim()
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val i = text.indexOf(q, startIndex = from, ignoreCase = true)
            if (i < 0) break
            out += i until (i + q.length)
            from = i + q.length
        }
        return out
    }

    /** Wrap [index] into [0, count) for next/prev cycling; -1 when there are no matches. */
    fun wrapIndex(index: Int, count: Int): Int =
        if (count <= 0) -1 else ((index % count) + count) % count
}

/**
 * Compact in-editor find bar: query field, "n/total" count, prev/next, close.
 * Lives under the editor top bar so the note itself stays visible while stepping
 * through matches (highlighting is applied by the editor's body transformation).
 */
@Composable
fun FindInNoteBar(
    query: String,
    onQuery: (String) -> Unit,
    matchIndex: Int,
    matchCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val tokens = Aura.tokens
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuraGlyph(Glyph.SEARCH, tokens.colors.textSecondary, Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                BasicText(
                    "Find in note",
                    style = AuraType.body.copy(color = tokens.colors.textSecondary)
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = "Find in note" }
            )
        }
        Spacer(Modifier.width(10.dp))
        BasicText(
            if (matchCount == 0) "0" else "${matchIndex + 1}/$matchCount",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )
        Spacer(Modifier.width(6.dp))
        FindAction(Glyph.CHEVRON_UP, "Previous match", enabled = matchCount > 0, onClick = onPrev)
        Spacer(Modifier.width(4.dp))
        FindAction(Glyph.CHEVRON_DOWN, "Next match", enabled = matchCount > 0, onClick = onNext)
        Spacer(Modifier.width(4.dp))
        FindAction(Glyph.CLOSE, "Close find", enabled = true, onClick = onClose)
    }
}

@Composable
private fun FindAction(glyph: Glyph, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(
            glyph,
            if (enabled) tokens.colors.textPrimary else tokens.colors.textSecondary.copy(alpha = 0.4f),
            Modifier.size(16.dp)
        )
    }
}
