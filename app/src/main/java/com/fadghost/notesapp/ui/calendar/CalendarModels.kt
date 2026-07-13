package com.fadghost.notesapp.ui.calendar

import com.fadghost.notesapp.calendar.RecurrenceMath
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Recurrence
import com.fadghost.notesapp.data.db.entity.Reminder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class CalendarKind { EVENT, REMINDER }

/**
 * One concrete thing to draw on the calendar — a single occurrence of an event or
 * reminder. Recurring rows expand into several of these across the visible window
 * (via [RecurrenceMath]) while [baseId] still points at the one stored row to edit.
 */
data class CalendarItem(
    val baseId: Long,
    val kind: CalendarKind,
    val title: String,
    val startMillis: Long,
    val endMillis: Long?,
    val timezone: String,
    val notes: String?,
    val recurrence: Recurrence,
    val done: Boolean
) {
    val isRecurring: Boolean get() = recurrence != Recurrence.NONE
}

/** Draft used by the create/edit sheet, decoupled from the two entity shapes. */
data class ItemDraft(
    val baseId: Long = 0L,
    val kind: CalendarKind = CalendarKind.EVENT,
    val title: String = "",
    val start: Long,
    val end: Long,
    val notes: String = "",
    val recurrence: Recurrence = Recurrence.NONE,
    val notificationLeadMinutes: Int? = null
)

object CalendarExpand {

    private fun dayOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** All event + reminder occurrences whose start falls in [rangeStart, rangeEnd). */
    fun itemsInRange(
        events: List<Event>,
        reminders: List<Reminder>,
        zone: ZoneId,
        rangeStart: Long,
        rangeEnd: Long
    ): List<CalendarItem> {
        val out = ArrayList<CalendarItem>()
        for (e in events) {
            val eZone = runCatching { ZoneId.of(e.timezone) }.getOrDefault(zone)
            val duration = (e.endAt - e.startAt).coerceAtLeast(0)
            for (start in RecurrenceMath.occurrencesInRange(e.startAt, eZone, e.recurrence, rangeStart, rangeEnd)) {
                out.add(
                    CalendarItem(
                        baseId = e.id, kind = CalendarKind.EVENT, title = e.title,
                        startMillis = start, endMillis = start + duration, timezone = e.timezone,
                        notes = e.notes, recurrence = e.recurrence, done = false
                    )
                )
            }
        }
        for (r in reminders) {
            val rZone = runCatching { ZoneId.of(r.timezone) }.getOrDefault(zone)
            for (start in RecurrenceMath.occurrencesInRange(r.triggerAt, rZone, r.recurrence, rangeStart, rangeEnd)) {
                out.add(
                    CalendarItem(
                        baseId = r.id, kind = CalendarKind.REMINDER, title = r.title,
                        startMillis = start, endMillis = null, timezone = r.timezone,
                        notes = null, recurrence = r.recurrence, done = r.done
                    )
                )
            }
        }
        return out.sortedBy { it.startMillis }
    }

    /** Group occurrences by local day for month dots and the agenda list. */
    fun groupByDay(items: List<CalendarItem>, zone: ZoneId): Map<LocalDate, List<CalendarItem>> =
        items.groupBy { dayOf(it.startMillis, zone) }
            .toSortedMap()
}
