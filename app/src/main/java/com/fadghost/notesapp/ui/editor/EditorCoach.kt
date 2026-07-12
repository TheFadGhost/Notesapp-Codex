package com.fadghost.notesapp.ui.editor

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One-time editor coach tip gate (P1-3). The tip labels the three unlabeled AI icons —
 * Clean-up / Extract / Voice — the first time the editor is opened, then never again.
 *
 * The show/hide decision is pure ([shouldShow]) so it is unit-testable without Android;
 * [EditorCoachStore] only owns the DataStore persistence around it.
 */
object EditorCoachGate {
    /** Show the coach tip only until it has been seen/dismissed once. */
    fun shouldShow(seen: Boolean): Boolean = !seen
}

private val Context.editorCoachDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "editor_coach")

class EditorCoachStore(context: Context) {
    private val store = context.applicationContext.editorCoachDataStore
    private val seenKey = booleanPreferencesKey("editor_coach_seen")

    /** True once the tip has been dismissed. Defaults false (first run → show). */
    val seen: Flow<Boolean> = store.data.map { it[seenKey] ?: false }

    suspend fun markSeen() {
        store.edit { it[seenKey] = true }
    }
}
