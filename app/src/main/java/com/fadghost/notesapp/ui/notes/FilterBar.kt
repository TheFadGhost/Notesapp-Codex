package com.fadghost.notesapp.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.db.entity.Folder
import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.ui.components.PlainChip
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/** Chip bar: All, folders, tags, Untagged, Archived, Trash (PLAN.md §6). */
@Composable
fun FilterBar(
    filter: NoteFilter,
    folders: List<Folder>,
    tags: List<Tag>,
    onSelect: (NoteFilter) -> Unit,
    onManageTag: (Tag) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlainChip("All", filter is NoteFilter.All) { onSelect(NoteFilter.All) }
        folders.forEach { f ->
            PlainChip(f.name, filter is NoteFilter.InFolder && filter.id == f.id) {
                onSelect(NoteFilter.InFolder(f.id, f.name))
            }
        }
        tags.forEach { t ->
            TagFilterChip(
                label = "#${t.name}",
                selected = filter is NoteFilter.WithTag && filter.id == t.id,
                onClick = { onSelect(NoteFilter.WithTag(t.id, t.name)) },
                onLongClick = { onManageTag(t) }
            )
        }
        PlainChip("Untagged", filter is NoteFilter.Untagged) { onSelect(NoteFilter.Untagged) }
        PlainChip("Archived", filter is NoteFilter.Archived) { onSelect(NoteFilter.Archived) }
        PlainChip("Trash", filter is NoteFilter.Trash) { onSelect(NoteFilter.Trash) }
    }
}

/** Tag chip that long-presses into the tag manager. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagFilterChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val tokens = Aura.tokens
    val bg = if (selected) tokens.colors.accent.copy(alpha = 0.9f) else tokens.colors.surface
    val fg = if (selected) lerp(tokens.colors.textPrimary, tokens.colors.background, 0.9f) else tokens.colors.textSecondary
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(bg)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, style = AuraType.label.copy(color = fg))
    }
}
