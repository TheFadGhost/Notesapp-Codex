package com.fadghost.notesapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.data.prefs.ThemeMode
import com.fadghost.notesapp.ui.MainViewModel
import com.fadghost.notesapp.ui.shell.AppShell
import com.fadghost.notesapp.ui.theme.AuraTheme
import com.fadghost.notesapp.ui.theme.DarkTokens
import com.fadghost.notesapp.ui.theme.LightTokens
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { NotesRoot() }
    }
}

@Composable
private fun NotesRoot(viewModel: MainViewModel = hiltViewModel()) {
    val mode by viewModel.themeMode.collectAsState()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    AuraTheme(tokens = if (dark) DarkTokens else LightTokens) {
        AppShell(
            themeMode = mode,
            onSelectThemeMode = viewModel::setThemeMode
        )
    }
}
