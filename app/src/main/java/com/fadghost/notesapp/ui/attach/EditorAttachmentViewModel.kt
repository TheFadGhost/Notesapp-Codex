package com.fadghost.notesapp.ui.attach

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.attach.AttachmentIngest
import com.fadghost.notesapp.data.attach.AttachmentRepository
import com.fadghost.notesapp.data.attach.RemovedAttachment
import com.fadghost.notesapp.data.db.entity.Attachment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Editor-scoped attachment state (M-A): observes the open note's attachment rows so
 * the body can render `[[att:<id>]]` bubble chips, and drives ingest + undoable remove.
 * The note must already exist (id > 0) before ingesting — the editor forces a save
 * first, mirroring the voice-attachment flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EditorAttachmentViewModel @Inject constructor(
    private val repository: AttachmentRepository,
    private val ingest: AttachmentIngest
) : ViewModel() {

    private val noteId = MutableStateFlow(0L)

    /** All attachments for the open note (drives chips + popover). */
    val attachments: StateFlow<List<Attachment>> = noteId
        .flatMapLatest { id -> if (id > 0) repository.observeForNote(id) else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** id -> attachment lookup for chip rendering. */
    val byId: StateFlow<Map<Long, Attachment>> = attachments
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun bind(id: Long) { noteId.value = id }

    /** Ingest a content [uri] onto [noteId]; [onStored] gets the new row for token insert. */
    fun ingest(noteId: Long, uri: Uri, onStored: (Attachment) -> Unit) {
        viewModelScope.launch {
            ingest.ingest(noteId, uri)?.let(onStored)
        }
    }

    /** Ingest raw [bytes] (clipboard bitmap) onto [noteId]. */
    fun ingestBytes(noteId: Long, bytes: ByteArray, displayName: String, mime: String, onStored: (Attachment) -> Unit) {
        viewModelScope.launch {
            ingest.ingestBytes(noteId, bytes, displayName, mime)?.let(onStored)
        }
    }

    fun noteBytes(noteId: Long): Long = repository.noteBytes(noteId)

    /** Remove an attachment, retaining it for undo; [onRemoved] receives the retained copy. */
    fun remove(id: Long, onRemoved: (RemovedAttachment) -> Unit) {
        viewModelScope.launch {
            repository.removeForUndo(id)?.let(onRemoved)
        }
    }

    /** Undo a remove: re-create the exact row + file (same id, so the token still resolves). */
    fun restore(removed: RemovedAttachment) {
        viewModelScope.launch { repository.restore(removed) }
    }

    /**
     * Save an annotated copy (M-A part 6): store [bytes] as a NEW image attachment linked
     * to [original] via annotatedOfId (the original is untouched), then hand the new id
     * back so the note token can be repointed at the copy.
     */
    fun saveAnnotation(noteId: Long, original: Attachment, bytes: ByteArray, onSaved: (Long) -> Unit) {
        viewModelScope.launch {
            val stem = original.displayName.substringBeforeLast('.', original.displayName)
            val att = repository.store(
                noteId = noteId,
                bytes = bytes,
                displayName = "$stem-annotated.png",
                mime = "image/png",
                annotatedOfId = original.id
            )
            onSaved(att.id)
        }
    }
}
