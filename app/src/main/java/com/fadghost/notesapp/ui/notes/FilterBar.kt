package com.fadghost.notesapp.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura

/**
 * Fast filters stay one-handed and scannable: All and tags scroll on the left,
 * while one pinned 48dp Organize control owns notebooks and secondary states.
 */
@Composable
fun FilterBar(
    filter: NoteFilter,
    tags: List<Tag>,
    onSelect: (NoteFilter) -> Unit,
    onManageTag: (Tag) -> Unit,
    onOpenOrganize: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NoteFilterChip(
                label = "All",
                selected = filter is NoteFilter.All,
                onClick = { onSelect(NoteFilter.All) }
            )
            tags.forEach { tag ->
                Spacer(Modifier.width(8.dp))
                NoteTagFilterChip(
                    tag = tag,
                    selected = filter is NoteFilter.WithTag && filter.id == tag.id,
                    onClick = { onSelect(filterForTag(tag)) },
                    onLongClick = { onManageTag(tag) }
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        OrganizeFilterButton(
            filter = filter,
            onClick = onOpenOrganize
        )
    }
}

@Composable
private fun OrganizeFilterButton(filter: NoteFilter, onClick: (Rect) -> Unit) {
    val tokens = Aura.tokens
    val hiddenFilterSelected = filter is NoteFilter.InFolder ||
        filter is NoteFilter.Untagged ||
        filter is NoteFilter.Archived ||
        filter is NoteFilter.Trash
    val interaction = remember { MutableInteractionSource() }
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier
            .size(48.dp)
            .onGloballyPositioned { anchorBounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(
                if (hiddenFilterSelected) tokens.colors.accent.copy(alpha = 0.16f)
                else tokens.colors.surface
            )
            .border(
                1.dp,
                if (hiddenFilterSelected) tokens.colors.accent else tokens.colors.outline,
                RoundedCornerShape(tokens.radii.pill)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onClick(anchorBounds) }
            )
            .semantics {
                contentDescription = "Organize filters. ${noteFilterSummary(filter)} selected"
            },
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(
            Glyph.FOLDER,
            if (hiddenFilterSelected) tokens.colors.accent else tokens.colors.textPrimary,
            Modifier.size(20.dp)
        )
        // A small tag dot makes the combined tag/notebook purpose legible without
        // turning the fixed 48dp control into another variable-width chip.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 9.dp, bottom = 9.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(tokens.colors.accent)
        )
    }
}
