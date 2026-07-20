package com.fadghost.notesapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.data.prefs.ThemeMode
import com.fadghost.notesapp.data.work.DiaryNudgeWorker
import com.fadghost.notesapp.ui.MainViewModel
import com.fadghost.notesapp.notify.EventNotifier
import com.fadghost.notesapp.notify.ReminderNotifier
import com.fadghost.notesapp.ui.calendar.CalendarDeepLink
import com.fadghost.notesapp.ui.capture.CaptureLaunch
import com.fadghost.notesapp.ui.capture.CaptureRequest
import com.fadghost.notesapp.ui.diary.DiaryLaunch
import com.fadghost.notesapp.ui.diary.DiaryLockManager
import com.fadghost.notesapp.ui.shell.AppShell
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraAccents
import com.fadghost.notesapp.ui.theme.AuraTheme
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.ReduceMotion
import com.fadghost.notesapp.ui.theme.ThemeResolver
import com.fadghost.notesapp.ui.theme.ThemeRevealScaffold
import com.fadghost.notesapp.ui.theme.rememberAnimatedTokens
import com.fadghost.notesapp.ui.theme.withAccent
import com.fadghost.notesapp.ui.whatsnew.WhatsNewHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single Activity. Extends [FragmentActivity] because the diary biometric gate uses
 * androidx.biometric's [androidx.biometric.BiometricPrompt], which attaches to a
 * FragmentActivity (PLAN.md §7). Also drives the diary lock lifecycle (re-lock after
 * >30s in background) and routes the journaling-nudge deep link to the Diary tab.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var diaryLockManager: DiaryLockManager
    @Inject lateinit var diaryLaunch: DiaryLaunch

    override fun onCreate(savedInstanceState: Bundle?) {
        // Paint the window with the last-used theme background so a Light-theme user
        // doesn't see the static dark windowBackground flash before Compose draws.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(BootColors.windowBackground(this))
        )
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent { NotesRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        diaryLockManager.onEnterForeground()
    }

    override fun onStop() {
        super.onStop()
        diaryLockManager.onEnterBackground()
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(DiaryNudgeWorker.EXTRA_OPEN_DIARY, false)) {
            diaryLaunch.requestOpenDiary()
        }
        // Reminder-notification tap → open the item on the Calendar tab (PLAN.md §8).
        val sourceNoteId = intent.getLongExtra(ReminderNotifier.EXTRA_OPEN_NOTE_ID, -1L)
        val eventId = intent.getLongExtra(EventNotifier.EXTRA_OPEN_CALENDAR_EVENT_ID, -1L)
        when {
            sourceNoteId > 0L -> CaptureLaunch.post(CaptureRequest.OpenNote(sourceNoteId))
            eventId > 0L -> CalendarDeepLink.requestEvent(eventId)
            intent.getBooleanExtra(ReminderNotifier.EXTRA_OPEN_CALENDAR, false) ->
                CalendarDeepLink.requestReminder(
                    intent.getLongExtra(ReminderNotifier.EXTRA_REMINDER_ID, -1L)
                )
        }
        // Capture paths (PLAN.md §6): tile, shortcuts, share/selected-text.
        handleCaptureIntent(intent)
    }

    /** Route tile / shortcut / share intents into a [CaptureRequest] for the shell. */
    private fun handleCaptureIntent(intent: Intent) {
        when {
            intent.getStringExtra(EXTRA_CAPTURE) == CAPTURE_NEW_NOTE ||
                intent.action == ACTION_NEW_NOTE ->
                CaptureLaunch.post(CaptureRequest.NewNote)

            intent.action == ACTION_VOICE ->
                CaptureLaunch.post(CaptureRequest.Voice)

            intent.action == ACTION_TODAY_DIARY ->
                CaptureLaunch.post(CaptureRequest.TodayDiary)

            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) CaptureLaunch.post(CaptureRequest.SharedText(text))
            }

            intent.action == Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!text.isNullOrBlank()) CaptureLaunch.post(CaptureRequest.SharedText(text))
            }

            // Share an image/file into a new note (M-A). Single + multiple.
            intent.action == Intent.ACTION_SEND -> {
                val uri = intentStreamUris(intent)
                if (uri.isNotEmpty()) CaptureLaunch.post(CaptureRequest.SharedAttachments(uri))
            }

            intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intentStreamUris(intent)
                if (uris.isNotEmpty()) CaptureLaunch.post(CaptureRequest.SharedAttachments(uris))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun intentStreamUris(intent: Intent): List<android.net.Uri> = when (intent.action) {
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty().filterNotNull()
        else ->
            (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri)?.let { listOf(it) }.orEmpty()
    }

    companion object {
        const val EXTRA_CAPTURE = "com.fadghost.notesapp.extra.CAPTURE"
        const val CAPTURE_NEW_NOTE = "new_note"
        const val ACTION_NEW_NOTE = "com.fadghost.notesapp.action.NEW_NOTE"
        const val ACTION_VOICE = "com.fadghost.notesapp.action.VOICE"
        const val ACTION_TODAY_DIARY = "com.fadghost.notesapp.action.TODAY_DIARY"
    }
}

@Composable
private fun NotesRoot(viewModel: MainViewModel = hiltViewModel()) {
    val mode by viewModel.themeMode.collectAsState()
    val accentIndex by viewModel.accentIndex.collectAsState()
    val userReduceMotion by viewModel.reduceMotion.collectAsState()
    val textScale by viewModel.textScale.collectAsState()

    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    // System animator scale (0 == animations off) OR the in-app toggle.
    val systemScale = remember { ReduceMotion.systemAnimatorScale(context) }
    val reduceMotion = ReduceMotion.effective(userReduceMotion, systemScale)

    // Base tokens by mode, then apply the accent override, then morph smoothly.
    val target = ThemeResolver.baseTokens(mode, systemDark)
        .withAccent(AuraAccents.accentForIndex(accentIndex))
    val animated by rememberAnimatedTokens(target, reduceMotion)

    // Persist this theme's background for the next cold start, and drive system-bar
    // icon contrast from the ACTIVE Aura theme (not system dark) — a Light theme under
    // system Dark must still get dark status-bar icons (platform.md §5).
    val view = LocalView.current
    SideEffect {
        val bg = target.colors.background
        BootColors.save(context, bg.toArgb())
        (view.context as? android.app.Activity)?.window?.let { window ->
            val light = bg.luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    // In-app text scale (IDEAS #89): compose the user's choice ONTO the system font
    // scale by overriding the density local — every sp in the app follows, including
    // dynamic system sizes (accessibility multiplies, never replaces).
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = remember(baseDensity, textScale) {
        androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * textScale)
    }

    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,
        androidx.compose.ui.platform.LocalDensity provides scaledDensity
    ) {
        AuraTheme(tokens = animated) {
            // Circular reveal from the tapped control on any theme/accent change.
            ThemeRevealScaffold(
                revealKey = mode to accentIndex,
                revealColor = target.colors.background,
                reduceMotion = reduceMotion
            ) {
                AppShell(
                    themeMode = mode,
                    onSelectThemeMode = viewModel::setThemeMode
                )
                WhatsNewHost()
            }
        }
    }
}
