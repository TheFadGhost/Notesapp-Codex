package com.fadghost.notesapp.calendar

import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Recurrence
import java.time.ZoneId

data class EventAlarmTime(
    val fireAtMillis: Long,
    val occurrenceAtMillis: Long,
    val leadMinutes: Int
)

/** Android-free event alert arithmetic, shared by scheduling and receiver validation. */
object EventNotificationMath {
    const val MAX_LEAD_MINUTES = 7 * 24 * 60
    const val MIN_FUTURE_DELAY_MS = 1_000L

    fun normalizedLeadMinutes(value: Int?): Int? = value?.takeIf { it in 0..MAX_LEAD_MINUTES }

    /**
     * Returns the next unclaimed occurrence alert. If a device/app restart happens after
     * the chosen lead time but before the occurrence starts, the alert is caught up one
     * second from [nowMillis] instead of being silently lost.
     */
    fun nextAlarm(event: Event, nowMillis: Long): EventAlarmTime? {
        val lead = normalizedLeadMinutes(event.notificationLeadMinutes) ?: return null
        val zone = runCatching { ZoneId.of(event.timezone) }.getOrDefault(ZoneId.systemDefault())

        var occurrence = when (event.recurrence) {
            Recurrence.NONE -> {
                if (event.startAt <= nowMillis || event.lastNotifiedOccurrenceAt == event.startAt) return null
                event.startAt
            }
            else -> RecurrenceMath.nextFrom(event.startAt, zone, event.recurrence, nowMillis)
        }
        if (occurrence <= nowMillis) return null // protects against the recurrence walk guard

        // The upcoming occurrence was already delivered; move exactly one slot ahead.
        if (event.lastNotifiedOccurrenceAt == occurrence) {
            if (event.recurrence == Recurrence.NONE) return null
            occurrence = RecurrenceMath.nextOccurrence(occurrence, zone, event.recurrence)
        }
        if (occurrence <= nowMillis) return null

        val leadMillis = lead.toLong() * 60_000L
        val intendedFireAt = subtractSaturated(occurrence, leadMillis)
        val earliestFuture = addSaturated(nowMillis, MIN_FUTURE_DELAY_MS)
        return EventAlarmTime(
            fireAtMillis = maxOf(intendedFireAt, earliestFuture),
            occurrenceAtMillis = occurrence,
            leadMinutes = lead
        )
    }

    /** True only when [occurrenceAt] still belongs to the event's current recurrence. */
    fun isCurrentOccurrence(event: Event, occurrenceAt: Long): Boolean {
        if (normalizedLeadMinutes(event.notificationLeadMinutes) == null) return false
        if (occurrenceAt < event.startAt) return false
        if (event.recurrence == Recurrence.NONE) return occurrenceAt == event.startAt
        val zone = runCatching { ZoneId.of(event.timezone) }.getOrDefault(ZoneId.systemDefault())
        return RecurrenceMath.nextFrom(
            event.startAt,
            zone,
            event.recurrence,
            occurrenceAt - 1L
        ) == occurrenceAt
    }

    private fun addSaturated(a: Long, b: Long): Long =
        runCatching { Math.addExact(a, b) }.getOrElse { Long.MAX_VALUE }

    private fun subtractSaturated(a: Long, b: Long): Long =
        runCatching { Math.subtractExact(a, b) }.getOrElse { Long.MIN_VALUE }
}
