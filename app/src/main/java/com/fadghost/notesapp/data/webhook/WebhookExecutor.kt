package com.fadghost.notesapp.data.webhook

import com.fadghost.notesapp.alarm.EventAlarm
import com.fadghost.notesapp.alarm.ReminderAlarm
import com.fadghost.notesapp.data.db.dao.DiaryDao
import com.fadghost.notesapp.data.db.dao.EventDao
import com.fadghost.notesapp.data.db.dao.NoteDao
import com.fadghost.notesapp.data.db.dao.ReminderDao
import com.fadghost.notesapp.data.db.entity.DiaryEntry
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Note
import com.fadghost.notesapp.data.db.entity.Reminder
import com.fadghost.notesapp.data.repo.NotesRepository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** A reminder as read back by `list_reminders` (id/title/when/done). */
data class ReminderView(
    val id: Long,
    val title: String,
    /** Trigger time as an ISO-8601 string with the device offset. */
    val whenAt: String,
    val done: Boolean
)

/** Result of running one command, position-preserving via [index]. */
sealed interface ExecResult {
    val index: Int

    data class Ok(
        override val index: Int,
        val id: Long? = null,
        val reminders: List<ReminderView>? = null
    ) : ExecResult

    data class Fail(
        override val index: Int,
        val error: String
    ) : ExecResult
}

/**
 * Runs already-parsed webhook commands against the app's real repositories/DAOs,
 * reusing the exact insert paths the UI uses so nothing bypasses FTS indexing or
 * alarm scheduling:
 *  - notes go through [NotesRepository.saveNote] (keeps the FTS4 index in sync),
 *  - reminders are armed via [ReminderAlarm.scheduleReminder] after the DAO upsert,
 *  - events are (re)armed via [EventAlarm.scheduleEvent],
 *  - diary entries upsert-by-date, preserving an existing day's content.
 *
 * A failure in one command becomes an [ExecResult.Fail]; it never aborts the batch
 * or surfaces as a 500.
 */
@Singleton
class WebhookExecutor @Inject constructor(
    private val notes: NotesRepository,
    private val noteDao: NoteDao,
    private val reminderDao: ReminderDao,
    private val reminderAlarm: ReminderAlarm,
    private val eventDao: EventDao,
    private val eventAlarm: EventAlarm,
    private val diaryDao: DiaryDao
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun execute(parsed: List<CommandParse>): List<ExecResult> =
        parsed.mapIndexed { index, item ->
            when (item) {
                is CommandParse.Err -> ExecResult.Fail(index, item.message)
                is CommandParse.Ok -> runCatching { run(index, item.command) }
                    .getOrElse { t -> ExecResult.Fail(index, t.message ?: t.javaClass.simpleName) }
            }
        }

    private suspend fun run(index: Int, command: WebhookCommand): ExecResult = when (command) {
        is WebhookCommand.CreateNote -> createNote(index, command)
        is WebhookCommand.CreateReminder -> createReminder(index, command)
        is WebhookCommand.CreateEvent -> createEvent(index, command)
        is WebhookCommand.CreateDiary -> createDiary(index, command)
        is WebhookCommand.AppendNote -> appendNote(index, command)
        is WebhookCommand.ListReminders -> listReminders(index, command)
    }

    private suspend fun createNote(index: Int, c: WebhookCommand.CreateNote): ExecResult {
        val now = System.currentTimeMillis()
        val id = notes.saveNote(Note(title = c.title, body = c.body, createdAt = now, updatedAt = now))
        c.tags.forEach { notes.ensureTagOnNote(noteId = id, name = it, color = 0) }
        return ExecResult.Ok(index, id = id)
    }

    private suspend fun createReminder(index: Int, c: WebhookCommand.CreateReminder): ExecResult {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault().id
        // The Reminder row has only a title. When a url/note is supplied we stash it in a
        // linked note (real FTS-indexed insert path) so it is one tap away from the reminder.
        val sourceNoteId: Long? = if (c.url != null || c.note != null) {
            val body = listOfNotNull(c.note, c.url).joinToString("\n\n")
            notes.saveNote(Note(title = c.title, body = body, createdAt = now, updatedAt = now))
        } else null

        val reminderId = reminderDao.upsert(
            Reminder(
                title = c.title,
                triggerAt = c.triggerAt,
                timezone = tz,
                sourceNoteId = sourceNoteId
            )
        )
        // Re-read the stored row (with its real id) before arming the exact alarm.
        reminderDao.getById(reminderId)?.let { reminderAlarm.scheduleReminder(it) }
        return ExecResult.Ok(index, id = reminderId)
    }

    private suspend fun createEvent(index: Int, c: WebhookCommand.CreateEvent): ExecResult {
        val tz = TimeZone.getDefault().id
        val eventId = eventDao.upsert(
            Event(
                title = c.title,
                startAt = c.startAt,
                endAt = c.endAt,
                timezone = tz,
                notes = c.note
                // notificationLeadMinutes stays null => no alert, matching a plain webhook event.
            )
        )
        // Mirror CalendarViewModel.saveEvent's arm step (a no-op while lead is null, but keeps
        // the path identical should notifications be added later).
        eventDao.getById(eventId)?.let(eventAlarm::scheduleEvent)
        return ExecResult.Ok(index, id = eventId)
    }

    private suspend fun createDiary(index: Int, c: WebhookCommand.CreateDiary): ExecResult {
        val now = System.currentTimeMillis()
        val existing = diaryDao.getByDate(c.date)
        val entry = if (existing == null) {
            DiaryEntry(date = c.date, body = c.text, createdAt = now, updatedAt = now)
        } else {
            // One entry per day (date is UNIQUE): append rather than clobber the day's text.
            val merged = if (existing.body.isBlank()) c.text else existing.body + "\n\n" + c.text
            existing.copy(body = merged, updatedAt = now)
        }
        diaryDao.upsert(entry)
        return ExecResult.Ok(index, id = existing?.id ?: diaryDao.getByDate(c.date)?.id)
    }

    private suspend fun appendNote(index: Int, c: WebhookCommand.AppendNote): ExecResult {
        val target: Note? = when {
            c.noteId != null -> notes.getNote(c.noteId)
            c.titleMatch != null -> noteDao.findFirstByTitleLike(c.titleMatch)
            else -> null
        }
        if (target == null) {
            val what = c.noteId?.let { "id $it" } ?: "title matching \"${c.titleMatch}\""
            return ExecResult.Fail(index, "No note found for $what")
        }
        val now = System.currentTimeMillis()
        val sep = if (target.body.isBlank()) "" else "\n\n"
        notes.saveNote(target.copy(body = target.body + sep + c.text, updatedAt = now))
        return ExecResult.Ok(index, id = target.id)
    }

    private suspend fun listReminders(index: Int, c: WebhookCommand.ListReminders): ExecResult {
        // No native range query on ReminderDao; read all and window in memory (low volume).
        val rows = reminderDao.allForBackup()
            .filter { (c.from == null || it.triggerAt >= c.from) && (c.to == null || it.triggerAt <= c.to) }
            .sortedBy { it.triggerAt }
            .map { ReminderView(id = it.id, title = it.title, whenAt = isoOf(it.triggerAt), done = it.done) }
        return ExecResult.Ok(index, reminders = rows)
    }

    private fun isoOf(millis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toString()
}
