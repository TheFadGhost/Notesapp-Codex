package com.fadghost.notesapp.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.backup.BackupManager
import com.fadghost.notesapp.data.backup.BackupPreview
import com.fadghost.notesapp.data.backup.BackupRestoreGuard
import com.fadghost.notesapp.data.backup.ImportMode
import com.fadghost.notesapp.data.prefs.BackupPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val backup: BackupManager,
    private val backupPrefs: BackupPreferences
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Last successful export (epoch millis, 0 = never) — drives the stale nudge (IDEAS #83). */
    val lastBackupAt: StateFlow<Long> =
        backupPrefs.lastBackupAt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

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
            backupPrefs.markBackupDone()
            _status.value = "Exported $count notes."
        }
    }

    fun loadPreview(source: Uri) {
        lastOp = { loadPreview(source) }
        _pendingPreview.value = null
        run(BackupUiState.Importing, "Couldn't verify that backup file.") {
            val preview = backup.preview(source)
            BackupRestoreGuard.requireIntact(preview)
            _pendingPreview.value = preview
            _status.value = "Backup verified: ${preview.manifest.noteCount} notes, " +
                "${preview.manifest.folderCount} folders, ${preview.manifest.tagCount} tags, " +
                "${preview.manifest.diaryEntryCount} diary entries, ${preview.manifest.eventCount} events, " +
                "${preview.manifest.reminderCount} reminders."
        }
    }

    fun confirmImport(mode: ImportMode) {
        val preview = _pendingPreview.value ?: return
        lastOp = { confirmImport(mode) }
        run(BackupUiState.Importing, "Couldn't import that backup.") {
            BackupRestoreGuard.requireIntact(preview)
            backup.restore(preview, mode)
            _pendingPreview.value = null
            val verb = if (mode == ImportMode.REPLACE) "Replaced with" else "Merged in"
            _status.value = "$verb ${preview.manifest.noteCount} notes, " +
                "${preview.manifest.diaryEntryCount} diary entries, ${preview.manifest.eventCount} events, " +
                "and ${preview.manifest.reminderCount} reminders."
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
