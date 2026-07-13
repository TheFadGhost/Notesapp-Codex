package com.fadghost.notesapp.ui.capture

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide relay for the "faster than the app" capture paths (PLAN.md §6): the
 * quick-settings tile, app-icon shortcuts, and ACTION_SEND / PROCESS_TEXT share
 * targets. MainActivity translates an incoming intent into a [CaptureRequest]; the
 * shell consumes it and clears it. Kept as a plain object (mirrors CalendarDeepLink)
 * so both the Activity and composables reach it without extra wiring.
 */
object CaptureLaunch {
    val request = MutableStateFlow<CaptureRequest?>(null)

    fun post(req: CaptureRequest) {
        request.value = req
    }

    fun clear() {
        request.value = null
    }
}

sealed interface CaptureRequest {
    /** Open a blank editor, keyboard up (tile + "New note" shortcut). */
    data object NewNote : CaptureRequest

    /** Open an existing source note from a reminder notification. */
    data class OpenNote(val noteId: Long) : CaptureRequest

    /** Open the capture sheet with voice preselected ("Voice ramble" shortcut). */
    data object Voice : CaptureRequest

    /** Jump to today's diary entry ("Today's diary" shortcut). */
    data object TodayDiary : CaptureRequest

    /** Create a note from shared/selected text (ACTION_SEND / PROCESS_TEXT). */
    data class SharedText(val text: String) : CaptureRequest

    /** Create a note from shared image/file content (ACTION_SEND[_MULTIPLE], M-A). */
    data class SharedAttachments(val uris: List<android.net.Uri>) : CaptureRequest
}
