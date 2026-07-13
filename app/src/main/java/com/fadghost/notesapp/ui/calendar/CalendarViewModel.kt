package com.fadghost.notesapp.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.alarm.AlarmScheduler
import com.fadghost.notesapp.alarm.EventAlarm
import com.fadghost.notesapp.calendar.EventNotificationMath
import com.fadghost.notesapp.data.db.dao.EventDao
import com.fadghost.notesapp.data.db.dao.ReminderDao
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Recurrence
import com.fadghost.notesapp.data.db.entity.Reminder
import com.fadghost.notesapp.ui.components.UndoMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Base rows the screen expands into occurrences (see [CalendarExpand]). */
data class CalendarData(
    val events: List<Event> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val loaded: Boolean = false
)

/**
 * Backs the Calendar tab (PLAN.md §8). Streams the raw event + reminder rows;
 * occurrence expansion for the visible window happens in the composables so month
 * swipes never re-hit the DB. Owns create/edit/delete and — for reminders — keeps
 * the exact alarm in sync through [AlarmScheduler].
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val eventDao: EventDao,
    private val reminderDao: ReminderDao,
    private val alarmScheduler: AlarmScheduler,
    private val eventAlarm: EventAlarm
) : ViewModel() {

    val data: StateFlow<CalendarData> =
        combine(eventDao.observeAll(), reminderDao.observeAll()) { events, reminders ->
            CalendarData(events, reminders, loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarData())

    // --- Universal undo snackbar for deletes (ux.md P1-6) -----------------------
    // No soft-delete exists for events/reminders (the DAOs only hard-delete via
    // deleteById), so we capture the full row before removing it and re-insert it
    // through the existing REPLACE upsert on undo — preserving its id and relations.
    private val _snackbar = MutableStateFlow<UndoMessage?>(null)
    val snackbar: StateFlow<UndoMessage?> = _snackbar.asStateFlow()
    private var pendingUndo: (suspend () -> Unit)? = null

    fun canScheduleExact(): Boolean = alarmScheduler.canExact()

    // --- Events -----------------------------------------------------------------

    fun saveEvent(
        baseId: Long,
        title: String,
        startAt: Long,
        endAt: Long,
        timezone: String,
        notes: String?,
        recurrence: Recurrence,
        notificationLeadMinutes: Int? = null
    ) {
        viewModelScope.launch {
            val existing = baseId.takeIf { it > 0L }?.let { eventDao.getById(it) }
            val lead = EventNotificationMath.normalizedLeadMinutes(notificationLeadMinutes)
            val scheduleUnchanged = existing != null &&
                existing.startAt == startAt &&
                existing.timezone == timezone &&
                existing.recurrence == recurrence &&
                existing.notificationLeadMinutes == lead
            val insertedId = eventDao.upsert(
                Event(
                    id = baseId,
                    title = title.trim().ifBlank { "Event" },
                    startAt = startAt,
                    endAt = endAt.coerceAtLeast(startAt),
                    timezone = timezone,
                    notes = notes?.trim()?.takeIf { it.isNotBlank() },
                    recurrence = recurrence,
                    notificationLeadMinutes = lead,
                    lastNotifiedOccurrenceAt = existing?.lastNotifiedOccurrenceAt
                        .takeIf { scheduleUnchanged }
                )
            )
            val effectiveId = if (baseId > 0L) baseId else insertedId
            // Clear any stale pending/displayed alert for the old schedule before
            // arming the freshly persisted settings.
            eventAlarm.cancelEvent(effectiveId)
            eventDao.getById(effectiveId)?.let(eventAlarm::scheduleEvent)
        }
    }

    fun deleteEvent(baseId: Long) {
        viewModelScope.launch {
            val existing = eventDao.getById(baseId)
            eventAlarm.cancelEvent(baseId)
            eventDao.deleteById(baseId)
            if (existing != null) {
                offerUndo("Event deleted") {
                    eventDao.upsert(existing)
                    eventAlarm.scheduleEvent(existing)
                }
            }
        }
    }

    // --- Reminders --------------------------------------------------------------

    fun saveReminder(
        baseId: Long,
        title: String,
        triggerAt: Long,
        timezone: String,
        recurrence: Recurrence
    ) {
        viewModelScope.launch {
            val existing = baseId.takeIf { it > 0L }?.let { reminderDao.getById(it) }
            val scheduleUnchanged = existing != null &&
                existing.triggerAt == triggerAt &&
                existing.timezone == timezone &&
                existing.recurrence == recurrence &&
                existing.snoozedUntil == null
            val id = reminderDao.upsert(
                Reminder(
                    id = baseId,
                    title = title.trim().ifBlank { "Reminder" },
                    triggerAt = triggerAt,
                    timezone = timezone,
                    done = false,
                    snoozedUntil = null,
                    recurrence = recurrence,
                    sourceNoteId = existing?.sourceNoteId,
                    lastNotifiedTriggerAt = existing?.lastNotifiedTriggerAt
                        .takeIf { scheduleUnchanged }
                )
            )
            val effectiveId = if (baseId != 0L) baseId else id
            reminderDao.getById(effectiveId)?.let { alarmScheduler.scheduleReminder(it) }
        }
    }

    fun deleteReminder(baseId: Long) {
        viewModelScope.launch {
            val existing = reminderDao.getById(baseId)
            alarmScheduler.cancelReminder(baseId)
            reminderDao.deleteById(baseId)
            if (existing != null) {
                offerUndo("Reminder deleted") {
                    reminderDao.upsert(existing)
                    if (!existing.done) alarmScheduler.scheduleReminder(existing)
                }
            }
        }
    }

    fun setReminderDone(baseId: Long, done: Boolean) {
        viewModelScope.launch {
            reminderDao.setDone(baseId, done)
            if (done) {
                alarmScheduler.cancelReminder(baseId)
            } else {
                reminderDao.getById(baseId)?.let { alarmScheduler.scheduleReminder(it) }
            }
        }
    }

    // --- Undo plumbing ----------------------------------------------------------

    private fun offerUndo(text: String, undo: suspend () -> Unit) {
        pendingUndo = undo
        _snackbar.value = UndoMessage(text)
    }

    fun undoDelete() {
        val action = pendingUndo ?: return
        pendingUndo = null
        _snackbar.value = null
        viewModelScope.launch { action() }
    }

    fun dismissSnackbar() {
        pendingUndo = null
        _snackbar.value = null
    }
}
