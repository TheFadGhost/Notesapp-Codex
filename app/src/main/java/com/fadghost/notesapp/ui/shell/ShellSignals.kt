package com.fadghost.notesapp.ui.shell

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-shot signals fired from the shell (nav pill re-tap, contextual FAB) down to the
 * currently-visible screen. Screens collect [flow], filter by their own [NavTab], and
 * act: SCROLL_TOP scrolls the list to the top; FAB_PRIMARY runs the tab's create
 * action (new diary line / new calendar event). Only the visible tab is ever targeted,
 * so exactly one composed collector reacts.
 *
 * Kept as a plain object (mirrors CaptureLaunch / CalendarDeepLink) — no Hilt wiring.
 */
object ShellSignals {
    data class Msg(val tab: NavTab, val signal: ShellSignal, val nonce: Long)

    private val _flow = MutableSharedFlow<Msg>(extraBufferCapacity = 8)
    val flow: SharedFlow<Msg> = _flow.asSharedFlow()

    /**
     * A note the editor just soft-deleted (P0-2). The editor exits silently; the Notes
     * list — the one place with the universal undo snackbar — picks this up and offers
     * the same "Moved to Trash · Undo" it shows for its own swipe/menu deletes, so the
     * one code path (repo already soft-deleted) surfaces undo without duplicating it.
     */
    data class DeletedMsg(val noteId: Long, val nonce: Long)

    private val _deleted = MutableSharedFlow<DeletedMsg>(extraBufferCapacity = 4)
    val deleted: SharedFlow<DeletedMsg> = _deleted.asSharedFlow()

    private var nonce = 0L

    fun send(tab: NavTab, signal: ShellSignal) {
        _flow.tryEmit(Msg(tab, signal, nonce++))
    }

    fun noteDeleted(id: Long) {
        _deleted.tryEmit(DeletedMsg(id, nonce++))
    }
}

enum class ShellSignal { SCROLL_TOP, FAB_PRIMARY, FOCUS_AI_SETTINGS }
