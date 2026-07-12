package com.fadghost.notesapp.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.db.entity.Folder
import com.fadghost.notesapp.data.db.entity.Note
import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.data.prefs.DraftStore
import com.fadghost.notesapp.data.prefs.DraftSnapshot
import com.fadghost.notesapp.data.repo.NotesRepository
import com.fadghost.notesapp.util.UndoStack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditorState(
    val loaded: Boolean = false,
    val noteId: Long = 0,
    val initialTitle: String = "",
    val initialBody: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val folderId: Long? = null
)

/**
 * Editor state holder (PLAN.md §6): debounced autosave to Room, snapshot
 * undo/redo, continuous draft mirroring for crash recovery, and tag/folder
 * assignment. Text lives in the composable ([EditorScreen]) as [TextFieldValue];
 * this VM owns persistence, history and metadata.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val repo: NotesRepository,
    private val draftStore: DraftStore,
    private val savedState: SavedStateHandle
) : ViewModel() {

    private companion object {
        const val KEY_ID = "editor_note_id"
        const val KEY_TITLE = "editor_title"
        const val KEY_BODY = "editor_body"
        const val AUTOSAVE_DEBOUNCE_MS = 500L
    }

    private val undo = UndoStack<String>()
    private var curTitle = ""
    private var curBody = ""
    private var createdAt = 0L
    private var autosaveJob: Job? = null

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val noteIdFlow = MutableStateFlow(0L)

    val folders: StateFlow<List<Folder>> =
        repo.observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTags: StateFlow<List<Tag>> =
        repo.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val noteTags: StateFlow<List<Tag>> = noteIdFlow
        .flatMapLatest { id -> if (id > 0) repo.observeTagsForNote(id) else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Open [id] (0 == new note). Restores process-death state from
     * SavedStateHandle, or from an explicit crash-recovery [draft] when offered.
     */
    fun open(id: Long, draft: DraftSnapshot? = null) {
        if (_state.value.loaded && _state.value.noteId == id) return
        viewModelScope.launch {
            val note = if (id > 0) repo.getNote(id) else null
            val restoredId = savedState.get<Long>(KEY_ID)
            val sameSession = restoredId != null && restoredId == id
            curTitle = (if (sameSession) savedState.get<String>(KEY_TITLE) else null) ?: note?.title ?: ""
            curBody = (if (sameSession) savedState.get<String>(KEY_BODY) else null) ?: note?.body ?: ""
            // Prefer the recovery draft when it is at least as fresh as the DB copy.
            if (draft != null && draft.noteId == id && (note == null || draft.updatedAt >= note.updatedAt)) {
                curTitle = draft.title
                curBody = draft.body
            }
            createdAt = note?.createdAt ?: System.currentTimeMillis()
            savedState[KEY_ID] = id
            undo.reset(curBody)
            noteIdFlow.value = id
            _state.value = EditorState(
                loaded = true,
                noteId = id,
                initialTitle = curTitle,
                initialBody = curBody,
                canUndo = false,
                canRedo = false,
                folderId = note?.folderId
            )
        }
    }

    fun onTitleChanged(title: String) {
        curTitle = title
        savedState[KEY_TITLE] = title
        scheduleAutosave()
    }

    fun onBodyChanged(body: String, coalesce: UndoStack.CoalesceKey) {
        if (body == curBody) return
        curBody = body
        savedState[KEY_BODY] = body
        undo.record(body, coalesce)
        pushUndoState()
        scheduleAutosave()
    }

    fun undo(): String? = undo.undo()?.also { applyHistory(it) }
    fun redo(): String? = undo.redo()?.also { applyHistory(it) }

    private fun applyHistory(body: String) {
        curBody = body
        savedState[KEY_BODY] = body
        pushUndoState()
        scheduleAutosave()
    }

    private fun pushUndoState() {
        _state.value = _state.value.copy(canUndo = undo.canUndo, canRedo = undo.canRedo)
    }

    private fun scheduleAutosave() {
        writeDraft()
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(AUTOSAVE_DEBOUNCE_MS)
            persist()
        }
    }

    /** Persist to Room. Skips creating an entirely empty new note. */
    private suspend fun persist(): Long {
        val id = _state.value.noteId
        if (id == 0L && curTitle.isBlank() && curBody.isBlank()) return 0L
        val now = System.currentTimeMillis()
        val newId = repo.saveNote(
            Note(
                id = id,
                title = curTitle,
                body = curBody,
                createdAt = createdAt,
                updatedAt = now,
                folderId = _state.value.folderId
            )
        )
        if (id == 0L && newId > 0L) {
            savedState[KEY_ID] = newId
            noteIdFlow.value = newId
            _state.value = _state.value.copy(noteId = newId)
        }
        return newId
    }

    private fun writeDraft() {
        viewModelScope.launch {
            draftStore.save(
                DraftSnapshot(
                    noteId = _state.value.noteId,
                    title = curTitle,
                    body = curBody,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** Ensure the note exists (needed before assigning tags to a brand-new note). */
    private suspend fun ensurePersisted(): Long {
        val id = _state.value.noteId
        return if (id > 0) id else persist()
    }

    /**
     * Force the note to exist (even if still empty) and hand back its id. Used before
     * attaching a voice recording to a brand-new note (PLAN.md §5): the transcript
     * lands via the caret afterward, so we must create the row up front to anchor the
     * audio attachment.
     */
    fun ensureSaved(onReady: (Long) -> Unit) {
        viewModelScope.launch {
            var id = _state.value.noteId
            if (id <= 0) {
                val now = System.currentTimeMillis()
                id = repo.saveNote(
                    Note(
                        id = 0,
                        title = curTitle,
                        body = curBody,
                        createdAt = createdAt.takeIf { it > 0 } ?: now,
                        updatedAt = now,
                        folderId = _state.value.folderId
                    )
                )
                if (id > 0) {
                    savedState[KEY_ID] = id
                    noteIdFlow.value = id
                    _state.value = _state.value.copy(noteId = id)
                }
            }
            if (id > 0) onReady(id)
        }
    }

    fun toggleTag(tagId: Long) {
        viewModelScope.launch {
            val id = ensurePersisted()
            if (id <= 0) return@launch
            val current = noteTags.value.map { it.id }.toMutableSet()
            if (!current.add(tagId)) current.remove(tagId)
            repo.setTagsForNote(id, current)
        }
    }

    fun createAndAssignTag(name: String, color: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = ensurePersisted()
            if (id <= 0) return@launch
            val tagId = repo.createTag(name, color)
            val current = noteTags.value.map { it.id }.toMutableSet().apply { add(tagId) }
            repo.setTagsForNote(id, current)
        }
    }

    fun createFolderAndMove(name: String) {
        viewModelScope.launch {
            val folderId = repo.createFolder(name)
            moveToFolder(folderId)
        }
    }

    fun moveToFolder(folderId: Long?) {
        _state.value = _state.value.copy(folderId = folderId)
        viewModelScope.launch {
            val id = ensurePersisted()
            if (id > 0) repo.moveToFolder(id, folderId)
        }
    }

    /** Flush and clear the crash-recovery draft (clean exit). */
    fun close() {
        autosaveJob?.cancel()
        viewModelScope.launch {
            persist()
            draftStore.clear()
            _state.value = EditorState()
        }
    }

    /**
     * Soft-delete the open note and exit. [onDone] receives the deleted note id (0 for a
     * never-persisted new note) so the shell can route it to the Notes list for the
     * universal undo snackbar (P0-2). A brand-new empty note has nothing to undo.
     */
    fun deleteNote(onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val id = _state.value.noteId
            if (id > 0) repo.softDelete(id)
            draftStore.clear()
            _state.value = EditorState()
            onDone(id)
        }
    }
}
