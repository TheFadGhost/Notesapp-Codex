package com.fadghost.notesapp.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.data.prefs.ThemeMode
import com.fadghost.notesapp.ui.screens.CalendarScreen
import com.fadghost.notesapp.ui.screens.DiaryScreen
import com.fadghost.notesapp.ui.screens.NotesScreen
import com.fadghost.notesapp.ui.screens.SettingsScreen
import com.fadghost.notesapp.ui.theme.Aura

@Composable
fun AppShell(
    themeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit
) {
    val tokens = Aura.tokens
    var selectedTab by remember { mutableStateOf(NavTab.NOTES) }
    var captureVisible by remember { mutableStateOf(false) }

    val systemBars = WindowInsets.systemBars.asPaddingValues()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
    ) {
        // Content layer (respects top inset; nav bar floats over the bottom).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = systemBars.calculateTopPadding())
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)))
                        .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMediumLow)))
                },
                label = "tab"
            ) { tab ->
                when (tab) {
                    NavTab.NOTES -> NotesScreen()
                    NavTab.DIARY -> DiaryScreen()
                    NavTab.CALENDAR -> CalendarScreen()
                    NavTab.SETTINGS -> SettingsScreen(
                        currentMode = themeMode,
                        onSelectMode = onSelectThemeMode
                    )
                }
            }
        }

        // Floating translucent nav bar.
        AuraNavBar(
            selected = selectedTab,
            onSelect = { selectedTab = it },
            onCapture = { captureVisible = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navInset + 16.dp)
        )

        CaptureSheet(
            visible = captureVisible,
            onDismiss = { captureVisible = false },
            onAction = { /* no-op stubs in M0 */ }
        )
    }
}
