package com.fadghost.notesapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Simple v1 recurrence model (PLAN.md §8: daily/weekly/monthly only). */
enum class Recurrence { NONE, DAILY, WEEKLY, MONTHLY }

@Entity(
    tableName = "Note",
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("folderId"), Index("updatedAt"), Index("pinned"), Index("archived")]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    /** Markdown body. */
    val body: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Non-null => soft-deleted (30-day trash). */
    val deletedAt: Long? = null,
    val folderId: Long? = null
)

@Entity(tableName = "Folder", indices = [Index(value = ["name"], unique = false)])
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)

@Entity(tableName = "Tag", indices = [Index(value = ["name"], unique = true)])
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB color for the tag chip. */
    val color: Int = 0
)

@Entity(
    tableName = "NoteTagCrossRef",
    primaryKeys = ["noteId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("noteId"), Index("tagId")]
)
data class NoteTagCrossRef(
    val noteId: Long,
    val tagId: Long
)

@Entity(tableName = "DiaryEntry", indices = [Index(value = ["date"], unique = true)])
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO local date (yyyy-MM-dd); one entry per day. */
    val date: String,
    val body: String = "",
    /** Optional mood score. */
    val mood: Int? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "Event",
    indices = [Index("startAt"), Index("notificationLeadMinutes")]
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    /** IANA timezone id (DST-safe scheduling, PLAN.md §8). */
    val timezone: String,
    val notes: String? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    /** Null disables alerts; otherwise minutes before the occurrence (0 = at start). */
    val notificationLeadMinutes: Int? = null,
    /** Logical occurrence start most recently posted, used to make delivery idempotent. */
    val lastNotifiedOccurrenceAt: Long? = null
)

@Entity(
    tableName = "Reminder",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["sourceNoteId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("triggerAt"), Index("done"), Index("sourceNoteId")]
)
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerAt: Long,
    val timezone: String,
    val done: Boolean = false,
    val snoozedUntil: Long? = null,
    /** Simple v1 repeat (PLAN.md §8: none/daily/weekly/monthly). Added in schema v4 (M3). */
    val recurrence: Recurrence = Recurrence.NONE,
    /** Note that produced this reminder (AI/ramble extraction); hard delete clears the link. */
    val sourceNoteId: Long? = null,
    /** Effective trigger/snooze slot most recently posted, preventing duplicate delivery. */
    val lastNotifiedTriggerAt: Long? = null
)
