package com.fadghost.notesapp.ui.whatsnew

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.R
import com.fadghost.notesapp.data.prefs.ThemePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gates the post-update "What's new" sheet (PLAN.md §13): on first launch after a
 * versionName change, load the bundled changelog and expose it once; marking it seen
 * persists the current version so it will not show again until the next update.
 */
@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: ThemePreferences
) : ViewModel() {

    data class WhatsNew(val version: String, val lines: List<ChangelogLine>)

    private val _state = MutableStateFlow<WhatsNew?>(null)
    val state: StateFlow<WhatsNew?> = _state.asStateFlow()

    private val currentVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    init {
        viewModelScope.launch {
            val lastSeen = prefs.lastSeenVersion.first()
            // Fresh install: baseline silently so the NEXT update shows its changelog.
            if (lastSeen.isBlank() && currentVersion.isNotBlank()) {
                prefs.setLastSeenVersion(currentVersion)
            }
            if (ChangelogGate.shouldShow(lastSeen, currentVersion)) {
                val lines = runCatching {
                    context.resources.openRawResource(R.raw.changelog)
                        .bufferedReader().use { it.readText() }
                }.getOrDefault("").let(::parseChangelog)
                if (lines.isNotEmpty()) _state.value = WhatsNew(currentVersion, lines)
            }
        }
    }

    fun dismiss() {
        _state.value = null
        viewModelScope.launch { prefs.setLastSeenVersion(currentVersion) }
    }
}
