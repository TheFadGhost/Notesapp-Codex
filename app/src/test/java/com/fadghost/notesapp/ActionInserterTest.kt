package com.fadghost.notesapp

import com.fadghost.notesapp.alarm.ReminderAlarm
import com.fadghost.notesapp.data.ai.ActionInserter
import com.fadghost.notesapp.data.ai.InsertedRow
import com.fadghost.notesapp.data.ai.parse.ActionType
import com.fadghost.notesapp.data.ai.parse.ProposedAction
import com.fadghost.notesapp.data.db.dao.EventDao
import com.fadghost.notesapp.data.db.dao.ReminderDao
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Reminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extracted-reminder scheduling (audit H1). Accepting an extracted REMINDER must arm
 * the exact alarm; the undo path must cancel it. Events don't schedule; todos insert
 * nothing. Uses fake DAOs and a recording [ReminderAlarm] — no Android context.
 */
class ActionInserterTest {

    private class RecordingAlarm : ReminderAlarm {
        val scheduled = mutableListOf<Reminder>()
        val cancelled = mutableListOf<Long>()
        override fun scheduleReminder(reminder: Reminder) { scheduled += reminder }
        override fun cancelReminder(reminderId: Long) { cancelled += reminderId }
        override suspend fun rescheduleAll() {}
        override fun canExact(): Boolean = true
    }

    private class FakeReminderDao : ReminderDao {
        val rows = LinkedHashMap<Long, Reminder>()
        private var seq = 0L
        override suspend fun upsert(reminder: Reminder): Long {
            val id = if (reminder.id != 0L) reminder.id else ++seq
            rows[id] = reminder.copy(id = id)
            return id
        }
        override suspend fun delete(reminder: Reminder) { rows.remove(reminder.id) }
        override fun observePending(): Flow<List<Reminder>> = emptyFlow()
        override fun observeAll(): Flow<List<Reminder>> = emptyFlow()
        override suspend fun getById(id: Long): Reminder? = rows[id]
        override suspend fun allPending(): List<Reminder> = rows.values.filter { !it.done }
        override suspend fun allForBackup(): List<Reminder> = rows.values.toList()
        override suspend fun deleteAll() { rows.clear() }
        override suspend fun claimNotification(id: Long, scheduledAt: Long): Int {
            val row = rows[id] ?: return 0
            val effectiveAt = row.snoozedUntil ?: row.triggerAt
            if (row.done || effectiveAt != scheduledAt || row.lastNotifiedTriggerAt == scheduledAt) return 0
            rows[id] = row.copy(lastNotifiedTriggerAt = scheduledAt)
            return 1
        }
        override suspend fun releaseNotificationClaim(id: Long, scheduledAt: Long) {
            rows[id]?.takeIf { it.lastNotifiedTriggerAt == scheduledAt }?.let {
                rows[id] = it.copy(lastNotifiedTriggerAt = null)
            }
        }
        override suspend fun setDone(id: Long, done: Boolean) {
            rows[id]?.let { rows[id] = it.copy(done = done) }
        }
        override suspend fun reschedule(id: Long, triggerAt: Long, snoozedUntil: Long?) {
            rows[id]?.let { rows[id] = it.copy(triggerAt = triggerAt, snoozedUntil = snoozedUntil) }
        }
        override suspend fun deleteById(id: Long) { rows.remove(id) }
    }

    private class FakeEventDao : EventDao {
        val rows = LinkedHashMap<Long, Event>()
        private var seq = 0L
        override suspend fun upsert(event: Event): Long {
            val id = if (event.id != 0L) event.id else ++seq
            rows[id] = event.copy(id = id)
            return id
        }
        override suspend fun delete(event: Event) { rows.remove(event.id) }
        override fun observeInRange(from: Long, to: Long): Flow<List<Event>> = emptyFlow()
        override fun observeAll(): Flow<List<Event>> = emptyFlow()
        override suspend fun getById(id: Long): Event? = rows[id]
        override suspend fun allWithNotifications(): List<Event> =
            rows.values.filter { it.notificationLeadMinutes != null }
        override suspend fun allForBackup(): List<Event> = rows.values.toList()
        override suspend fun deleteAll() { rows.clear() }
        override suspend fun claimNotification(id: Long, occurrenceAt: Long): Int {
            val row = rows[id] ?: return 0
            if (row.notificationLeadMinutes == null || row.lastNotifiedOccurrenceAt == occurrenceAt) return 0
            rows[id] = row.copy(lastNotifiedOccurrenceAt = occurrenceAt)
            return 1
        }
        override suspend fun releaseNotificationClaim(id: Long, occurrenceAt: Long) {
            rows[id]?.takeIf { it.lastNotifiedOccurrenceAt == occurrenceAt }?.let {
                rows[id] = it.copy(lastNotifiedOccurrenceAt = null)
            }
        }
        override suspend fun deleteById(id: Long) { rows.remove(id) }
    }

    private fun reminder(at: Long) =
        ProposedAction(ActionType.REMINDER, "Call dentist", datetimeMillis = at, notes = null)

    @Test fun accepting_reminder_arms_the_alarm() = runTest {
        val alarm = RecordingAlarm()
        val reminders = FakeReminderDao()
        val inserter = ActionInserter(FakeEventDao(), reminders, alarm)

        val row = inserter.insert(reminder(1_800_000_000_000L))

        assertTrue(row is InsertedRow.ReminderRow)
        val id = (row as InsertedRow.ReminderRow).id
        assertEquals(1, alarm.scheduled.size)
        assertEquals(id, alarm.scheduled[0].id)
        assertEquals(1_800_000_000_000L, alarm.scheduled[0].triggerAt)
    }

    @Test fun extracted_reminder_keeps_its_source_note() = runTest {
        val alarm = RecordingAlarm()
        val reminders = FakeReminderDao()
        val inserter = ActionInserter(FakeEventDao(), reminders, alarm)

        val row = inserter.insert(reminder(1_800_000_000_000L), sourceNoteId = 42L)

        val id = (row as InsertedRow.ReminderRow).id
        assertEquals(42L, reminders.rows.getValue(id).sourceNoteId)
        assertEquals(42L, alarm.scheduled.single().sourceNoteId)
    }

    @Test fun undoing_reminder_cancels_the_alarm() = runTest {
        val alarm = RecordingAlarm()
        val reminders = FakeReminderDao()
        val inserter = ActionInserter(FakeEventDao(), reminders, alarm)

        val row = inserter.insert(reminder(1_800_000_000_000L))!!
        inserter.delete(row)

        val id = (row as InsertedRow.ReminderRow).id
        assertEquals(listOf(id), alarm.cancelled)
        assertTrue(reminders.rows.isEmpty())
    }

    @Test fun accepting_event_does_not_schedule() = runTest {
        val alarm = RecordingAlarm()
        val inserter = ActionInserter(FakeEventDao(), FakeReminderDao(), alarm)

        val row = inserter.insert(
            ProposedAction(ActionType.EVENT, "Dentist", datetimeMillis = 1_800_000_000_000L, notes = null)
        )

        assertTrue(row is InsertedRow.EventRow)
        assertTrue(alarm.scheduled.isEmpty())
    }

    @Test fun todo_inserts_nothing() = runTest {
        val alarm = RecordingAlarm()
        val inserter = ActionInserter(FakeEventDao(), FakeReminderDao(), alarm)

        val row = inserter.insert(ProposedAction(ActionType.TODO, "Buy milk", datetimeMillis = null, notes = null))

        assertNull(row)
        assertTrue(alarm.scheduled.isEmpty())
    }
}
