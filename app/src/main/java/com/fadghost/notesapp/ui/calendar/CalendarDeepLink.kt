package com.fadghost.notesapp.ui.calendar

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface CalendarDeepLinkTarget {
    val id: Long

    data class Reminder(override val id: Long) : CalendarDeepLinkTarget
    data class Event(override val id: Long) : CalendarDeepLinkTarget
}

data class CalendarDeepLinkRequest(val token: Long, val target: CalendarDeepLinkTarget)

/** Tokenized relay so repeated taps and missing/deleted rows are consumed reliably. */
object CalendarDeepLink {
    private val tokens = AtomicLong(0L)
    val pendingRequest = MutableStateFlow<CalendarDeepLinkRequest?>(null)

    fun requestReminder(id: Long) = request(CalendarDeepLinkTarget.Reminder(id))
    fun requestEvent(id: Long) = request(CalendarDeepLinkTarget.Event(id))

    private fun request(target: CalendarDeepLinkTarget) {
        if (target.id > 0L) pendingRequest.value = CalendarDeepLinkRequest(tokens.incrementAndGet(), target)
    }

    fun consume(token: Long) {
        if (pendingRequest.value?.token == token) pendingRequest.value = null
    }
}
