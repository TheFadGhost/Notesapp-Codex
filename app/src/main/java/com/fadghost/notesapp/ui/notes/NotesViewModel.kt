package com.fadghost.notesapp.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.db.entity.Folder
import com.fadghost.notesapp.data.db.entity.Note
import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.data.repo.NotesRepository
import com.fadghost.notesapp.ui.components.UndoMessage
import com.fadghost.notesapp.util.FtsQuery
import com.fadghost.notesapp.util.Markdown
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The active filter chip (PLAN.md §6 filter bar). */
sealed interface NoteFilter {
    data object All : NoteFilter
    data object Untagged : NoteFilter
    data object Archived : NoteFilter
    data object Trash : NoteFilter
    data class InFolder(val id: Long, val name: String) : NoteFilter
    data class WithTag(val id: Long, val name: String) : NoteFilter
}

/** A note prepared for display in the list/grid. */
data class NoteCardUi(
    val id: Long,
    val title: String,
    val preview: String,
    val pinned: Boolean,
    val archived: Boolean,
    val inTrash: Boolean,
    val tags: List<Tag>
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repo: NotesRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow<NoteFilter>(NoteFilter.All)
    val filter: StateFlow<NoteFilter> = _filter.asStateFlow()

    private val _isGrid = MutableStateFlow(true)
    val isGrid: StateFlow<Boolean> = _isGrid.asStateFlow()

    private val _snackbar = MutableStateFlow<UndoMessage?>(null)
    val snackbar: StateFlow<UndoMessage?> = _snackbar.asStateFlow()
    private var pendingUndo: (suspend () -> Unit)? = null

    val folders: StateFlow<List<Folder>> =
        repo.observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<Tag>> =
        repo.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<List<NoteCardUi>> =
        combine(_filter, _query.debounce(120)) { f, q -> f to q }
            .flatMapLatest { (f, q) ->
                val base: Flow<List<Note>> = if (q.isBlank()) {
                    sourceForFilter(f)
                } else {
                    FtsQuery.build(q)?.let { repo.search(it) } ?: sourceForFilter(f)
                }
                combine(base, repo.observeAllNoteTags()) { list, rows ->
                    val tagsByNote = rows.groupBy { it.noteId }
                    list.map { note ->
                        NoteCardUi(
                            id = note.id,
                            title = note.title,
                            preview = Markdown.strip(note.body).take(180),
                            pinned = note.pinned,
                            archived = note.archived,
                            inTrash = note.deletedAt != null,
                            tags = tagsByNote[note.id].orEmpty()
                                .map { Tag(it.tagId, it.name, it.color) }
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun sourceForFilter(f: NoteFilter): Flow<List<Note>> = when (f) {
        NoteFilter.All -> repo.observeActive()
        NoteFilter.Untagged -> repo.observeUntagged()
        NoteFilter.Archived -> repo.observeArchived()
        NoteFilter.Trash -> repo.observeTrash()
        is NoteFilter.InFolder -> repo.observeByFolder(f.id)
        is NoteFilter.WithTag -> repo.observeByTag(f.id)
    }

    fun setQuery(q: String) { _query.value = q }
    fun setFilter(f: NoteFilter) { _filter.value = f }
    fun toggleGrid() { _isGrid.value = !_isGrid.value }

    // --- Actions with universal undo snackbar (PLAN.md §7) ----------------------

    fun togglePin(id: Long, currentlyPinned: Boolean) = viewModelScope.launch {
        repo.setPinned(id, !currentlyPinned)
        offerUndo(if (currentlyPinned) "Unpinned" else "Pinned") { repo.setPinned(id, currentlyPinned) }
    }

    fun archive(id: Long) = viewModelScope.launch {
        repo.setArchived(id, true)
        offerUndo("Archived") { repo.setArchived(id, false) }
    }

    fun unarchive(id: Long) = viewModelScope.launch { repo.setArchived(id, false) }

    fun delete(id: Long) = viewModelScope.launch {
        repo.softDelete(id)
        offerUndo("Moved to Trash") { repo.restore(id) }
    }

    /**
     * The editor already soft-deleted [id]; just surface the universal undo snackbar
     * here (P0-2). No second softDelete — the undo restores the same row.
     */
    fun onEditorDeleted(id: Long) {
        offerUndo("Moved to Trash") { repo.restore(id) }
    }

    fun duplicate(id: Long) = viewModelScope.launch { repo.duplicate(id) }

    fun moveToFolder(id: Long, folderId: Long?) = viewModelScope.launch { repo.moveToFolder(id, folderId) }

    fun restore(id: Long) = viewModelScope.launch { repo.restore(id) }

    fun deleteForever(id: Long) = viewModelScope.launch { repo.hardDelete(id) }

    // --- Tag management (PLAN.md §6: rename / recolour / delete / merge) ---------

    fun renameTag(id: Long, name: String) = viewModelScope.launch { repo.renameTag(id, name) }

    fun recolorTag(id: Long, color: Int) = viewModelScope.launch { repo.setTagColor(id, color) }

    fun deleteTag(id: Long) = viewModelScope.launch {
        if ((_filter.value as? NoteFilter.WithTag)?.id == id) _filter.value = NoteFilter.All
        repo.deleteTag(id)
    }

    fun mergeTag(sourceId: Long, targetId: Long) = viewModelScope.launch {
        if ((_filter.value as? NoteFilter.WithTag)?.id == sourceId) _filter.value = NoteFilter.All
        repo.mergeTags(sourceId, targetId)
    }

    private fun offerUndo(text: String, undo: suspend () -> Unit) {
        pendingUndo = undo
        _snackbar.value = UndoMessage(text)
    }

    fun undoSnackbar() {
        val action = pendingUndo ?: return
        pendingUndo = null
        _snackbar.value = null
        viewModelScope.launch { action() }
    }

    fun dismissSnackbar() {
        pendingUndo = null
        _snackbar.value = null
    }
}
