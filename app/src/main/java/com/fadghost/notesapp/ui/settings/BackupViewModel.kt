package com.fadghost.notesapp.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.backup.BackupManager
import com.fadghost.notesapp.data.backup.BackupPreview
import com.fadghost.notesapp.data.backup.ImportMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Visible progress/error state for the backup card (ux.md P1-5 / P2-6 / P1-9).
 * Busy states drive the inline "Exporting…/Importing…" copy and row dimming;
 * [Error] carries friendly-first copy plus the raw technical [detail] hidden
 * behind a "Show details" disclosure in the UI, and is cleared by a retry.
 */
sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Exporting : BackupUiState
    data object Importing : BackupUiState
    data class Error(val friendly: String, val detail: String) : BackupUiState
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: BackupManager
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _uiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** Non-null when an import ZIP has been read and is awaiting a merge/replace choice. */
    private val _pendingPreview = MutableStateFlow<BackupPreview?>(null)
    val pendingPreview: StateFlow<BackupPreview?> = _pendingPreview.asStateFlow()

    /** The last attempted operation, replayed by [retry] after a failure (ux.md P1-9). */
    private var lastOp: (() -> Unit)? = null

    fun export(target: Uri) {
        lastOp = { export(target) }
        run(BackupUiState.Exporting, "Couldn't export your backup.") {
            val count = backup.export(target)
            _status.value = "Exported $count notes."
        }
    }

    fun loadPreview(source: Uri) {
        lastOp = { loadPreview(source) }
        run(BackupUiState.Importing, "Couldn't read that backup file.") {
            val preview = backup.preview(source)
            _pendingPreview.value = preview
            _status.value = if (preview.isIntact) {
                "Backup verified: ${preview.manifest.noteCount} notes, " +
                    "${preview.manifest.folderCount} folders, ${preview.manifest.tagCount} tags."
            } else {
                "Warning: ${preview.checksumMismatches.size} file(s) failed checksum verification."
            }
        }
    }

    fun confirmImport(mode: ImportMode) {
        val preview = _pendingPreview.value ?: return
        lastOp = { confirmImport(mode) }
        run(BackupUiState.Importing, "Couldn't import that backup.") {
            backup.restore(preview, mode)
            _pendingPreview.value = null
            val verb = if (mode == ImportMode.REPLACE) "Replaced with" else "Merged in"
            _status.value = "$verb ${preview.manifest.noteCount} notes."
        }
    }

    fun cancelImport() {
        _pendingPreview.value = null
        _status.value = null
        if (_uiState.value is BackupUiState.Error) _uiState.value = BackupUiState.Idle
    }

    /** Re-runs the last export/import attempt after an error (ux.md P1-9). */
    fun retry() {
        _uiState.value = BackupUiState.Idle
        lastOp?.invoke()
    }

    private fun run(busyState: BackupUiState, friendly: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.value = busyState
            runCatching { block() }
                .onSuccess { _uiState.value = BackupUiState.Idle }
                .onFailure {
                    _status.value = null
                    _uiState.value = BackupUiState.Error(friendly, it.message ?: it.toString())
                }
        }
    }
}
