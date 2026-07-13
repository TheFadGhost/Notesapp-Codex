package com.fadghost.notesapp.data.ai

import com.fadghost.notesapp.alarm.ReminderAlarm
import com.fadghost.notesapp.data.ai.parse.ActionType
import com.fadghost.notesapp.data.ai.parse.ProposedAction
import com.fadghost.notesapp.data.db.dao.EventDao
import com.fadghost.notesapp.data.db.dao.ReminderDao
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Reminder
import java.util.TimeZone

/** Identifies an inserted row so an accepted AI action can be undone. */
sealed interface InsertedRow {
    data class EventRow(val id: Long) : InsertedRow
    data class ReminderRow(val id: Long) : InsertedRow
}

/**
 * Inserts accepted extracted actions into Room and — crucially — arms the exact
 * alarm for reminder rows so an AI-extracted reminder actually fires (audit H1: the
 * accept path previously inserted the row but never scheduled). The undo path cancels
 * that alarm. Kept as a small collaborator (rather than inline in [AiRepository]) so
 * the schedule/cancel wiring is unit-testable with fake DAOs and a fake [ReminderAlarm].
 */
class ActionInserter(
    private val eventDao: EventDao,
    private val reminderDao: ReminderDao,
    private val alarm: ReminderAlarm
) {
    /** Insert an accepted proposal. Returns a token identifying the inserted row for undo. */
    suspend fun insert(action: ProposedAction, sourceNoteId: Long? = null): InsertedRow? {
        val tz = TimeZone.getDefault().id
        return when (action.type) {
            ActionType.EVENT -> {
                val start = action.datetimeMillis ?: System.currentTimeMillis()
                val id = eventDao.upsert(
                    Event(
                        title = action.title,
                        startAt = start,
                        endAt = start + 60 * 60 * 1000,
                        timezone = tz,
                        notes = action.notes
                    )
                )
                InsertedRow.EventRow(id)
            }
            ActionType.REMINDER -> {
                val trigger = action.datetimeMillis ?: System.currentTimeMillis()
                val id = reminderDao.upsert(
                    Reminder(
                        title = action.title,
                        triggerAt = trigger,
                        timezone = tz,
                        sourceNoteId = sourceNoteId?.takeIf { it > 0 }
                    )
                )
                // Arm the exact alarm (H1). Re-read the stored row so the alarm carries
                // the real row id; fall back to a synthetic copy if the read misses.
                val stored = reminderDao.getById(id)
                    ?: Reminder(
                        id = id,
                        title = action.title,
                        triggerAt = trigger,
                        timezone = tz,
                        sourceNoteId = sourceNoteId?.takeIf { it > 0 }
                    )
                alarm.scheduleReminder(stored)
                InsertedRow.ReminderRow(id)
            }
            // Todos have no dedicated table yet (calendar UI is M3); nothing to insert.
            ActionType.TODO -> null
        }
    }

    /** Undo a previously [insert]-ed row, cancelling any armed alarm. */
    suspend fun delete(row: InsertedRow) {
        when (row) {
            is InsertedRow.EventRow ->
                eventDao.delete(Event(id = row.id, title = "", startAt = 0, endAt = 0, timezone = ""))
            is InsertedRow.ReminderRow -> {
                alarm.cancelReminder(row.id)
                reminderDao.delete(Reminder(id = row.id, title = "", triggerAt = 0, timezone = ""))
            }
        }
    }
}
