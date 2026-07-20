package com.fadghost.notesapp.ui.whatsnew

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.prefs.ThemePreferences
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * First-run Welcome sheet (council blocker: zero orientation on cold launch). One
 * scrollable card that names the five tabs, introduces Folio, and is honest about
 * the optional OpenRouter key BEFORE the user hits a wall. Shown exactly once.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val prefs: ThemePreferences
) : ViewModel() {
    /** null while loading (show nothing), then true exactly when never seen. */
    val show: StateFlow<Boolean?> =
        prefs.welcomeSeen.map { seen -> !seen }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismiss() {
        viewModelScope.launch { prefs.setWelcomeSeen() }
    }
}

@Composable
fun WelcomeHost(viewModel: WelcomeViewModel = hiltViewModel()) {
    val show by viewModel.show.collectAsStateWithLifecycle()
    WelcomeSheet(visible = show == true, onDismiss = viewModel::dismiss)
}

@Composable
private fun WelcomeSheet(visible: Boolean, onDismiss: () -> Unit) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    androidx.activity.compose.BackHandler(enabled = visible) { onDismiss() }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(MotionTokens.bouncyFinite(reduceMotion)) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                        .background(tokens.colors.surface)
                        .border(1.dp, tokens.colors.outline, RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .navigationBarsPadding()
                        .padding(20.dp)
                ) {
                    BasicText("Welcome", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
                    Spacer(Modifier.height(4.dp))
                    BasicText(
                        "Your notes, diary and calendar — private, on this device.",
                        style = AuraType.body.copy(color = tokens.colors.textSecondary)
                    )
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        WelcomeRow(Glyph.DOCUMENT, "Notes", "Capture anything. The + button creates; long-press it for voice ramble and quick reminders.")
                        WelcomeRow(Glyph.BOOK, "Diary", "One page per day, with moods and streaks. Completely private.")
                        WelcomeRow(Glyph.CALENDAR, "Calendar", "Events and reminders that actually ring.")
                        WelcomeRow(Glyph.SPARKLE, "Ask · Folio", "Folio is your AI librarian — it answers questions from your own notes and can tidy, extract and remember for you.")
                        WelcomeRow(Glyph.CLOCK, "AI is optional", "AI features use your own OpenRouter key (openrouter.ai) and cost only what you use. Add it any time in Settings → AI.")
                    }
                    Spacer(Modifier.height(16.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        SoftButton("Let's go", filled = true, onClick = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeRow(glyph: Glyph, title: String, body: String) {
    val tokens = Aura.tokens
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(tokens.radii.sm))
                .background(tokens.colors.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { AuraGlyph(glyph, tokens.colors.accent, Modifier.size(20.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = AuraType.bodyLg.copy(color = tokens.colors.textPrimary))
            Spacer(Modifier.height(2.dp))
            BasicText(body, style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }
    }
}
