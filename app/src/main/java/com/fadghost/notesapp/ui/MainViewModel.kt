package com.fadghost.notesapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.prefs.ThemeMode
import com.fadghost.notesapp.data.prefs.ThemePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val themePreferences: ThemePreferences
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = themePreferences.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.SYSTEM
    )

    /** Accent picker index (PLAN.md §9). -1 == theme default. */
    val accentIndex: StateFlow<Int> = themePreferences.accentIndex.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = -1
    )

    /** In-app reduce-motion toggle (PLAN.md §10). */
    val reduceMotion: StateFlow<Boolean> = themePreferences.reduceMotion.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    /** In-app text scale (IDEAS #89); multiplies the system font scale. */
    val textScale: StateFlow<Float> = themePreferences.textScale.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 1f
    )

    fun setTextScale(scale: Float) {
        viewModelScope.launch { themePreferences.setTextScale(scale) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themePreferences.setThemeMode(mode) }
    }

    fun setAccentIndex(index: Int) {
        viewModelScope.launch { themePreferences.setAccentIndex(index) }
    }

    fun setReduceMotion(enabled: Boolean) {
        viewModelScope.launch { themePreferences.setReduceMotion(enabled) }
    }
}
