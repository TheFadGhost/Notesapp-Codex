package com.fadghost.notesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/** First-run empty states (PLAN.md §10). Real content arrives in M1+. */
@Composable
private fun EmptyScreen(title: String, subtitle: String) {
    val tokens = Aura.tokens
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BasicText(
            text = title,
            style = AuraType.title.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
        )
        BasicText(
            text = subtitle,
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun NotesScreen() = EmptyScreen("Notes", "Your notes will live here. Tap + to capture.")

@Composable
fun DiaryScreen() = EmptyScreen("Diary", "One entry per day. Locked behind biometrics later.")

@Composable
fun CalendarScreen() = EmptyScreen("Calendar", "App-internal events and reminders arrive in M3.")
