package com.fadghost.notesapp.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.ui.components.AuraEmptyState
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.AuraUndoSnackbar
import com.fadghost.notesapp.ui.components.EmptyGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.PlainChip
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.shell.LocalNavPillClearance
import com.fadghost.notesapp.ui.shell.NavTab
import com.fadghost.notesapp.ui.shell.ShellSignal
import com.fadghost.notesapp.ui.shell.ShellSignals
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraFloatShadow
import com.fadghost.notesapp.ui.theme.auraSheetShadow
import com.fadghost.notesapp.ui.theme.auraTopHighlight
import kotlinx.coroutines.launch

/**
 * Notes list/grid (PLAN.md §6): search, filter chips, pinned-first cards with
 * swipe actions + context menu, universal undo snackbar.
 */
@Composable
fun NotesScreen(
    onOpenNote: (Long) -> Unit,
    viewModel: NotesViewModel = hiltViewModel()
) {
    val tokens = Aura.tokens
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val isGrid by viewModel.isGrid.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbar.collectAsStateWithLifecycle()

    var menuFor by remember { mutableStateOf<NoteCardUi?>(null) }
    var managingTag by remember { mutableStateOf<com.fadghost.notesapp.data.db.entity.Tag?>(null) }
    var organizeFiltersAnchor by remember { mutableStateOf<Rect?>(null) }
    // Permanent (hard) delete is irreversible — unlike soft-delete it has no Undo, so it
    // must go through an explicit confirm popover (Bug 3).
    var pendingForeverDelete by remember { mutableStateOf<NoteCardUi?>(null) }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val navPillClearance = LocalNavPillClearance.current
    // Nav re-tap on the active tab scrolls the grid to the top (V2-SPEC item 13).
    LaunchedEffect(Unit) {
        ShellSignals.flow.collect { msg ->
            if (msg.tab == NavTab.NOTES && msg.signal == ShellSignal.SCROLL_TOP) {
                scope.launch { gridState.animateScrollToItem(0) }
            }
        }
    }
    // The editor soft-deleted a note → surface the universal undo snackbar here (P0-2).
    LaunchedEffect(Unit) {
        ShellSignals.deleted.collect { msg -> viewModel.onEditorDeleted(msg.noteId) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Header + grid/list toggle.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    // Single-line + ellipsis so a narrow (320dp) width can never char-wrap
                    // the title to "N / ot / es" (P0-2). The eyebrow is pinned to one line too.
                    BasicText(
                        "YOUR LIBRARY",
                        style = AuraType.labelSm.copy(color = tokens.colors.textSecondary),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    BasicText(
                        "Notes",
                        style = AuraType.titleLg.copy(color = tokens.colors.textPrimary),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Title has layout priority (weight(1f) above); the count and the grid
                // toggle are the fixed-width siblings that starve it. Drop the count first
                // (<360dp), then the toggle at ultra-narrow (<200dp, the ~122dp / 320px
                // repro) so the word "Notes" always renders legibly instead of collapsing
                // to an ellipsis "•••" (P0-2).
                val screenWidthDp = LocalConfiguration.current.screenWidthDp
                val roomForCount = screenWidthDp >= 360
                val roomForToggle = screenWidthDp >= 200
                if (notes.isNotEmpty() && roomForCount) {
                    val pinned = notes.count { it.pinned }
                    val countText = "${notes.size} ${if (notes.size == 1) "note" else "notes"}" +
                        if (pinned > 0) " · $pinned pinned" else ""
                    BasicText(
                        countText,
                        style = AuraType.bodySm.copy(color = tokens.colors.textSecondary),
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(Modifier.width(12.dp))
                }
                if (roomForToggle) {
                    val gridToggleInteraction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(tokens.radii.pill))
                            .auraPress(gridToggleInteraction, tint = true)
                            .background(tokens.colors.surface)
                            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
                            .clickable(
                                interactionSource = gridToggleInteraction,
                                indication = null,
                                onClick = viewModel::toggleGrid
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AuraGlyph(if (isGrid) Glyph.LIST else Glyph.GRID, tokens.colors.textPrimary, Modifier.size(20.dp))
                    }
                }
            }

            SearchBar(query = query, onQueryChange = viewModel::setQuery)
            Spacer(Modifier.height(12.dp))
            FilterBar(
                filter = filter,
                tags = tags,
                onSelect = viewModel::setFilter,
                onManageTag = { managingTag = it },
                onOpenOrganize = { organizeFiltersAnchor = it }
            )
            Spacer(Modifier.height(12.dp))

            if (notes.isEmpty()) {
                EmptyNotes(
                    filter = filter,
                    searching = query.isNotBlank(),
                    onCreate = { onOpenNote(0) }
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(if (isGrid) 2 else 1),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp, end = 20.dp, top = 4.dp,
                        bottom = navPillClearance
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                        NoteCard(
                            note = note,
                            index = index,
                            onOpen = { if (!note.inTrash) onOpenNote(note.id) else menuFor = note },
                            onLongPress = { menuFor = note },
                            onPin = { viewModel.togglePin(note.id, note.pinned) },
                            onArchive = { if (note.archived) viewModel.unarchive(note.id) else viewModel.archive(note.id) },
                            onDelete = { if (note.inTrash) pendingForeverDelete = note else viewModel.delete(note.id) },
                            modifier = Modifier.animateItem(),
                            query = query
                        )
                    }
                }
            }
        }

        AuraUndoSnackbar(
            message = snackbar,
            onAction = viewModel::undoSnackbar,
            onDismiss = viewModel::dismissSnackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navPillClearance)
        )

        menuFor?.let { note ->
            NoteContextMenu(
                items = contextItemsFor(
                    note,
                    viewModel,
                    onDeleteForever = { pendingForeverDelete = it }
                ),
                onDismiss = { menuFor = null }
            )
        }

        pendingForeverDelete?.let { note ->
            DeleteForeverOverlay(
                onConfirm = {
                    viewModel.deleteForever(note.id)
                    pendingForeverDelete = null
                },
                onDismiss = { pendingForeverDelete = null }
            )
        }

        managingTag?.let { tag ->
            TagManagerOverlay(
                tag = tag,
                otherTags = tags.filter { it.id != tag.id },
                onRename = { viewModel.renameTag(tag.id, it); managingTag = null },
                onRecolor = { viewModel.recolorTag(tag.id, it); managingTag = null },
                onDelete = { viewModel.deleteTag(tag.id); managingTag = null },
                onMerge = { target -> viewModel.mergeTag(tag.id, target); managingTag = null },
                onDismiss = { managingTag = null }
            )
        }

        organizeFiltersAnchor?.let { anchor ->
            OrganizeFilterPanel(
                anchorBounds = anchor,
                filter = filter,
                tags = tags,
                onSelect = viewModel::setFilter,
                onManageTag = { tag ->
                    organizeFiltersAnchor = null
                    managingTag = tag
                },
                onDismiss = { organizeFiltersAnchor = null }
            )
        }
    }
}

private fun contextItemsFor(
    note: NoteCardUi,
    vm: NotesViewModel,
    onDeleteForever: (NoteCardUi) -> Unit
): List<ContextMenuItem> = if (note.inTrash) {
    listOf(
        ContextMenuItem(Glyph.RESTORE, "Restore") { vm.restore(note.id) },
        // Route through the confirm popover — hard delete has no Undo (Bug 3).
        ContextMenuItem(Glyph.TRASH, "Delete forever", danger = true) { onDeleteForever(note) }
    )
} else {
    listOf(
        ContextMenuItem(Glyph.PIN, if (note.pinned) "Unpin" else "Pin") { vm.togglePin(note.id, note.pinned) },
        ContextMenuItem(Glyph.ARCHIVE, if (note.archived) "Unarchive" else "Archive") {
            if (note.archived) vm.unarchive(note.id) else vm.archive(note.id)
        },
        ContextMenuItem(Glyph.DUPLICATE, "Duplicate") { vm.duplicate(note.id) },
        ContextMenuItem(Glyph.TRASH, "Delete", danger = true) { vm.delete(note.id) }
    )
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    val tokens = Aura.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(48.dp)
            .auraSheetShadow(RoundedCornerShape(tokens.radii.pill))
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuraGlyph(Glyph.SEARCH, tokens.colors.textSecondary, Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                BasicText("Search notes", style = AuraType.body.copy(color = tokens.colors.textSecondary))
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
                    .size(24.dp)
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onQueryChange("") }
                    ),
                contentAlignment = Alignment.Center
            ) { AuraGlyph(Glyph.CLOSE, tokens.colors.textSecondary, Modifier.size(14.dp)) }
        }
    }
}

@Composable
private fun EmptyNotes(filter: NoteFilter, searching: Boolean, onCreate: () -> Unit) {
    val (glyph, title, subtitle) = when {
        searching -> Triple(EmptyGlyph.SEARCH, "No matches", "Try a different search.")
        filter is NoteFilter.Trash -> Triple(EmptyGlyph.TRASH, "Trash is empty", "Deleted notes rest here for 30 days.")
        filter is NoteFilter.Archived -> Triple(EmptyGlyph.ARCHIVE, "Nothing archived", "Archived notes stay out of the way.")
        else -> Triple(EmptyGlyph.NOTES, "No notes yet", "Start with a blank page — your first note is one tap away.")
    }
    // P0 (ux.md §3): the first-run empty state gets a real CTA button, not inert text.
    val isFirstRun = !searching && filter is NoteFilter.All
    AuraEmptyState(
        glyph = glyph,
        title = title,
        subtitle = subtitle,
        actionLabel = if (isFirstRun) "New note" else null,
        onAction = if (isFirstRun) onCreate else null
    )
}


/**
 * Irreversible confirm popover for permanent (hard) delete from Trash (Bug 3). Unlike
 * soft-delete there is no Undo, so the destructive action is gated behind an explicit
 * Cancel/Delete choice. Aura-styled (scrim + floating card, danger-filled commit
 * button), presses give feedback via [auraPress], all theming via tokens.
 */
@Composable
private fun DeleteForeverOverlay(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val tokens = Aura.tokens
    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = tokens.elevation.scrim))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .auraFloatShadow(RoundedCornerShape(tokens.radii.lg))
                .clip(RoundedCornerShape(tokens.radii.lg))
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                .auraTopHighlight(tokens.radii.lg)
                // Swallow taps on the card so the scrim's dismiss doesn't fire.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(20.dp)
        ) {
            BasicText("Delete forever?", style = AuraType.titleLg.copy(color = tokens.colors.textPrimary))
            Spacer(Modifier.height(6.dp))
            BasicText(
                "This note will be permanently removed. This can't be undone.",
                style = AuraType.body.copy(color = tokens.colors.textSecondary)
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                val cancelInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(tokens.radii.pill))
                        .auraPress(cancelInteraction)
                        .clickable(
                            interactionSource = cancelInteraction,
                            indication = null,
                            onClick = onDismiss
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    BasicText("Cancel", style = AuraType.label.copy(color = tokens.colors.textSecondary))
                }
                Spacer(Modifier.width(8.dp))
                val confirmInteraction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(tokens.radii.pill))
                        .auraPress(confirmInteraction, tint = true)
                        .background(tokens.colors.danger)
                        .clickable(
                            interactionSource = confirmInteraction,
                            indication = null,
                            onClick = onConfirm
                        )
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    BasicText("Delete", style = AuraType.label.copy(color = tokens.colors.background))
                }
            }
        }
    }
}
