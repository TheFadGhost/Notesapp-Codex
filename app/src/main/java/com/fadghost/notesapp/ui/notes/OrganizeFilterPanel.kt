package com.fadghost.notesapp.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.FlowChips
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraAccents
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraFloatShadow
import kotlin.math.roundToInt

internal const val ORGANIZE_FILTER_SEARCH_THRESHOLD = 8

/** User-facing description shared by the panel subtitle and trigger accessibility label. */
internal fun noteFilterSummary(filter: NoteFilter): String = when (filter) {
    NoteFilter.All -> "All notes"
    NoteFilter.Untagged -> "Untagged"
    NoteFilter.Archived -> "Archived notes"
    NoteFilter.Trash -> "Trash"
    is NoteFilter.WithTag -> "Tag · #${filter.name}"
}

internal fun shouldSearchOrganizeFilters(tags: List<Tag>): Boolean =
    tags.size > ORGANIZE_FILTER_SEARCH_THRESHOLD

/** Search and order are pure so large-list behaviour can be verified without Compose. */
internal fun visibleOrganizeTags(
    tags: List<Tag>,
    query: String,
    selected: NoteFilter
): List<Tag> {
    val normalized = query.trim()
    val selectedId = (selected as? NoteFilter.WithTag)?.id
    return tags.asSequence()
        .filter { normalized.isBlank() || it.name.contains(normalized, ignoreCase = true) }
        .sortedWith(compareByDescending<Tag> { it.id == selectedId }.thenBy { it.name.lowercase() })
        .toList()
}

internal fun filterForTag(tag: Tag): NoteFilter = NoteFilter.WithTag(tag.id, tag.name)

/**
 * Tag-only, single-selection filter panel for the list screen. It uses the measured
 * trigger bounds, flips above the trigger when needed, clamps to the window, and keeps
 * long collections searchable and vertically scrollable.
 */
@Composable
fun OrganizeFilterPanel(
    anchorBounds: Rect,
    filter: NoteFilter,
    tags: List<Tag>,
    onSelect: (NoteFilter) -> Unit,
    onManageTag: (Tag) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    val density = LocalDensity.current
    var query by remember { mutableStateOf("") }
    val searchable = shouldSearchOrganizeFilters(tags)
    val visibleTags = remember(tags, query, filter) { visibleOrganizeTags(tags, query, filter) }

    Popup(
        alignment = Alignment.TopStart,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false
        ),
        onDismissRequest = onDismiss
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            val margin = 12.dp
            val gap = 8.dp
            // Never force a minimum wider/taller than the actual window: this matters in
            // split-screen and for the app's narrow-width regression target.
            val panelWidth = (maxWidth - margin * 2).coerceAtLeast(1.dp).coerceAtMost(340.dp)
            val panelMaxHeight = (maxHeight - margin * 2).coerceAtLeast(1.dp).coerceAtMost(500.dp)
            val rootWidthPx = with(density) { maxWidth.roundToPx() }
            val rootHeightPx = with(density) { maxHeight.roundToPx() }
            val panelWidthPx = with(density) { panelWidth.roundToPx() }
            val panelHeightPx = with(density) { panelMaxHeight.roundToPx() }
            val marginPx = with(density) { margin.roundToPx() }
            val gapPx = with(density) { gap.roundToPx() }
            val minUsefulHeightPx = with(density) { 220.dp.roundToPx() }
            val maxX = (rootWidthPx - panelWidthPx - marginPx).coerceAtLeast(marginPx)
            val x = (anchorBounds.right.roundToInt() - panelWidthPx).coerceIn(marginPx, maxX)
            val latestTop = (rootHeightPx - marginPx - panelHeightPx).coerceAtLeast(marginPx)
            val belowY = anchorBounds.bottom.roundToInt() + gapPx
            val availableBelow = rootHeightPx - marginPx - belowY
            val y = if (availableBelow >= minUsefulHeightPx.coerceAtMost(panelHeightPx)) {
                belowY.coerceIn(marginPx, latestTop)
            } else {
                (anchorBounds.top.roundToInt() - gapPx - panelHeightPx).coerceIn(marginPx, latestTop)
            }

            Column(
                modifier = Modifier
                    .offset { IntOffset(x, y) }
                    .width(panelWidth)
                    .heightIn(max = panelMaxHeight)
                    .imePadding()
                    .auraFloatShadow(RoundedCornerShape(tokens.radii.lg))
                    .clip(RoundedCornerShape(tokens.radii.lg))
                    .background(tokens.colors.surface)
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText("Organize", style = AuraType.title.copy(color = tokens.colors.textPrimary))
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            noteFilterSummary(filter),
                            style = AuraType.label.copy(color = tokens.colors.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    ClosePanelButton(onDismiss)
                }

                if (searchable) {
                    Spacer(Modifier.height(14.dp))
                    OrganizeFilterSearch(query = query, onQueryChange = { query = it })
                }

                Spacer(Modifier.height(18.dp))
                FilterSectionLabel("Tags")
                FlowChips {
                    NoteFilterChip(
                        label = "All",
                        selected = filter is NoteFilter.All,
                        onClick = { onSelect(NoteFilter.All) }
                    )
                    visibleTags.forEach { tag ->
                        NoteTagFilterChip(
                            tag = tag,
                            selected = filter is NoteFilter.WithTag && filter.id == tag.id,
                            onClick = { onSelect(filterForTag(tag)) },
                            onLongClick = { onManageTag(tag) }
                        )
                    }
                }
                if (tags.isNotEmpty() && visibleTags.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    BasicText("No matching tags.", style = AuraType.bodySm.copy(color = tokens.colors.textSecondary))
                }

                FilterDivider()
                FilterSectionLabel("Other")
                FlowChips {
                    NoteFilterChip(
                        label = "Untagged",
                        selected = filter is NoteFilter.Untagged,
                        onClick = { onSelect(NoteFilter.Untagged) }
                    )
                    NoteFilterChip(
                        label = "Archived",
                        selected = filter is NoteFilter.Archived,
                        onClick = { onSelect(NoteFilter.Archived) }
                    )
                    NoteFilterChip(
                        label = "Trash",
                        selected = filter is NoteFilter.Trash,
                        danger = true,
                        onClick = { onSelect(NoteFilter.Trash) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterSectionLabel(text: String) {
    BasicText(
        text.uppercase(),
        style = AuraType.labelSm.copy(color = Aura.tokens.colors.textSecondary),
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun FilterDivider() {
    Spacer(Modifier.height(18.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Aura.tokens.colors.outline))
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun OrganizeFilterSearch(query: String, onQueryChange: (String) -> Unit) {
    val tokens = Aura.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(tokens.radii.sm))
            .background(tokens.colors.background)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuraGlyph(Glyph.SEARCH, tokens.colors.textSecondary, Modifier.size(18.dp))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                BasicText(
                    "Find a tag or notebook",
                    style = AuraType.body.copy(color = tokens.colors.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onQueryChange("") }
                    )
                    .semantics { contentDescription = "Clear filter search" },
                contentAlignment = Alignment.Center
            ) {
                AuraGlyph(Glyph.CLOSE, tokens.colors.textSecondary, Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ClosePanelButton(onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .auraPress(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Close Organize filters" },
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(Glyph.CLOSE, tokens.colors.textSecondary, Modifier.size(18.dp))
    }
}

/** Shared 48dp choice used in both the compact filter bar and the panel. */
@Composable
internal fun NoteFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    val tokens = Aura.tokens
    val selectedColor = if (danger) tokens.colors.danger else tokens.colors.accent
    val bg = if (selected) selectedColor.copy(alpha = 0.9f) else tokens.colors.surface
    val fg = if (selected) {
        lerp(tokens.colors.textPrimary, tokens.colors.background, 0.9f)
    } else if (danger) {
        tokens.colors.danger
    } else {
        tokens.colors.textSecondary
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(48.dp)
            .defaultMinSize(minWidth = 48.dp)
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(bg)
            .border(
                1.dp,
                if (selected) selectedColor else tokens.colors.outline,
                RoundedCornerShape(tokens.radii.pill)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .semantics {
                this.selected = selected
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            label,
            style = AuraType.label.copy(color = fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 48dp coloured tag chip; long-press keeps the existing tag-management path. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteTagFilterChip(
    tag: Tag,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    val dot = AuraAccents.resolve(tag.color, tokens.colors.accent)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(48.dp)
            .defaultMinSize(minWidth = 48.dp)
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .background(if (selected) dot.copy(alpha = 0.18f) else tokens.colors.surface)
            .border(1.dp, if (selected) dot else tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = "Manage tag",
                onLongClick = onLongClick
            )
            .semantics {
                this.selected = selected
                contentDescription = "Tag ${tag.name}"
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        BasicText(
            "#${tag.name}",
            style = AuraType.label.copy(color = tokens.colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (selected) {
            Spacer(Modifier.width(5.dp))
            AuraGlyph(Glyph.CHECK, dot, Modifier.size(14.dp))
        }
    }
}
