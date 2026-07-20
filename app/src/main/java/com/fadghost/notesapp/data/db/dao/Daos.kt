package com.fadghost.notesapp.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import com.fadghost.notesapp.data.db.entity.DiaryEntry
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Folder
import com.fadghost.notesapp.data.db.entity.Note
import com.fadghost.notesapp.data.db.entity.NoteTagCrossRef
import com.fadghost.notesapp.data.db.entity.Reminder
import com.fadghost.notesapp.data.db.entity.Tag
import kotlinx.coroutines.flow.Flow

/** Flat row for building a noteId -> tags map for the list without per-note flows. */
data class NoteTagRow(
    val noteId: Long,
    val tagId: Long,
    val name: String,
    val color: Int
)

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    @Query("SELECT * FROM Note WHERE id = :id")
    suspend fun getById(id: Long): Note?

    /** Newest active note whose title contains [q] (case-insensitive), for the
     *  automation webhook's `append_note {titleMatch}`. LIKE is fine here: the match
     *  is a single one-shot lookup, not a search surface. */
    @Query(
        "SELECT * FROM Note WHERE deletedAt IS NULL AND title LIKE '%' || :q || '%' " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun findFirstByTitleLike(q: String): Note?

    @Query("SELECT * FROM Note WHERE id = :id")
    fun observeById(id: Long): Flow<Note?>

    // --- Filter views (PLAN.md §6 chip bar) -------------------------------------

    @Query("SELECT * FROM Note WHERE deletedAt IS NULL AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<Note>>

    @Query("SELECT * FROM Note WHERE deletedAt IS NULL AND archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<Note>>

    @Query("SELECT * FROM Note WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<Note>>

    @Query("SELECT * FROM Note WHERE deletedAt IS NULL AND archived = 0 AND folderId = :folderId ORDER BY pinned DESC, updatedAt DESC")
    fun observeByFolder(folderId: Long): Flow<List<Note>>

    @Query(
        "SELECT n.* FROM Note n JOIN NoteTagCrossRef x ON n.id = x.noteId " +
            "WHERE x.tagId = :tagId AND n.deletedAt IS NULL AND n.archived = 0 " +
            "ORDER BY n.pinned DESC, n.updatedAt DESC"
    )
    fun observeByTag(tagId: Long): Flow<List<Note>>

    @Query(
        "SELECT * FROM Note WHERE deletedAt IS NULL AND archived = 0 " +
            "AND id NOT IN (SELECT noteId FROM NoteTagCrossRef) " +
            "ORDER BY pinned DESC, updatedAt DESC"
    )
    fun observeUntagged(): Flow<List<Note>>

    // --- Mutations --------------------------------------------------------------

    @Query("UPDATE Note SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("UPDATE Note SET archived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    @Query("UPDATE Note SET deletedAt = :deletedAt, updatedAt = :now WHERE id = :id")
    suspend fun setDeletedAt(id: Long, deletedAt: Long?, now: Long)

    @Query("UPDATE Note SET folderId = :folderId, updatedAt = :now WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?, now: Long)

    @Query("DELETE FROM Note WHERE id = :id")
    suspend fun hardDelete(id: Long)

    // --- Trash purge (WorkManager, PLAN.md §6/§7) -------------------------------

    @Query("SELECT id FROM Note WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun expiredTrashIds(cutoff: Long): List<Long>

    // --- Export -----------------------------------------------------------------

    @Query("SELECT * FROM Note WHERE deletedAt IS NULL ORDER BY id")
    suspend fun allForExport(): List<Note>

    /** Every live/archive/trash row; REPLACE restore must not leave Trash behind. */
    @Query("SELECT * FROM Note ORDER BY id")
    suspend fun allForReplace(): List<Note>

    // --- Full-text search (regular FTS5 table, joined back for filtering) --------

    @RawQuery(observedEntities = [Note::class])
    fun searchRaw(query: SimpleSQLiteQuery): Flow<List<Note>>

    /** Active (non-trash, non-archived) notes matching an FTS5 MATCH expression. */
    fun search(match: String): Flow<List<Note>> = searchRaw(
        SimpleSQLiteQuery(
            "SELECT n.* FROM Note n JOIN note_fts f ON n.id = f.rowid " +
                "WHERE f.note_fts MATCH ? AND n.deletedAt IS NULL AND n.archived = 0 " +
                "ORDER BY n.pinned DESC, n.updatedAt DESC",
            arrayOf(match)
        )
    )
}

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: Folder): Long

    @Delete
    suspend fun delete(folder: Folder)

    @Query("DELETE FROM Folder WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE Folder SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("SELECT * FROM Folder WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Folder?

    @Query("SELECT * FROM Folder WHERE id = :id")
    suspend fun getById(id: Long): Folder?

    @Query("SELECT * FROM Folder ORDER BY name")
    fun observeAll(): Flow<List<Folder>>

    @Query("SELECT * FROM Folder ORDER BY name")
    suspend fun all(): List<Folder>
}

@Dao
interface TagDao {
    /** Never REPLACE a unique-name collision: REPLACE would cascade-delete note links. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: Tag): Long

    @Delete
    suspend fun delete(tag: Tag)

    @Query("DELETE FROM Tag WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE Tag SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE Tag SET color = :color WHERE id = :id")
    suspend fun setColor(id: Long, color: Int)

    @Query("SELECT * FROM Tag WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Tag?

    @Query("SELECT * FROM Tag WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByNormalizedName(name: String): Tag?

    @Query("SELECT * FROM Tag WHERE id = :id")
    suspend fun getById(id: Long): Tag?

    @Query("SELECT * FROM Tag ORDER BY name")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM Tag ORDER BY name")
    suspend fun all(): List<Tag>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(crossRef: NoteTagCrossRef)

    @Delete
    suspend fun unlink(crossRef: NoteTagCrossRef)

    @Query("DELETE FROM NoteTagCrossRef WHERE noteId = :noteId")
    suspend fun clearTagsForNote(noteId: Long)

    @Query("SELECT t.* FROM Tag t JOIN NoteTagCrossRef x ON t.id = x.tagId WHERE x.noteId = :noteId ORDER BY t.name")
    fun observeTagsForNote(noteId: Long): Flow<List<Tag>>

    @Query("SELECT t.* FROM Tag t JOIN NoteTagCrossRef x ON t.id = x.tagId WHERE x.noteId = :noteId ORDER BY t.name")
    suspend fun tagsForNote(noteId: Long): List<Tag>

    /** All note<->tag links joined with tag info, for building the list's tag map. */
    @Query(
        "SELECT x.noteId AS noteId, t.id AS tagId, t.name AS name, t.color AS color " +
            "FROM NoteTagCrossRef x JOIN Tag t ON t.id = x.tagId"
    )
    fun observeAllNoteTags(): Flow<List<NoteTagRow>>

    // Tag merge (PLAN.md §6): move source's links onto target, then drop source.
    @Query("UPDATE OR IGNORE NoteTagCrossRef SET tagId = :targetId WHERE tagId = :sourceId")
    suspend fun reassignLinks(sourceId: Long, targetId: Long)

    @Query("DELETE FROM NoteTagCrossRef WHERE tagId = :tagId")
    suspend fun deleteLinksForTag(tagId: Long)
}

@Dao
interface DiaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DiaryEntry): Long

    @Delete
    suspend fun delete(entry: DiaryEntry)

    @Query("SELECT * FROM DiaryEntry WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): DiaryEntry?

    @Query("SELECT * FROM DiaryEntry ORDER BY date DESC")
    fun observeAll(): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM DiaryEntry ORDER BY date")
    suspend fun allForBackup(): List<DiaryEntry>

    @Query("DELETE FROM DiaryEntry")
    suspend fun deleteAll()
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: Event): Long

    @Delete
    suspend fun delete(event: Event)

    @Query("SELECT * FROM Event WHERE startAt BETWEEN :from AND :to ORDER BY startAt")
    fun observeInRange(from: Long, to: Long): Flow<List<Event>>

    @Query("SELECT * FROM Event ORDER BY startAt")
    fun observeAll(): Flow<List<Event>>

    @Query("SELECT * FROM Event WHERE id = :id")
    suspend fun getById(id: Long): Event?

    @Query("SELECT * FROM Event WHERE notificationLeadMinutes IS NOT NULL")
    suspend fun allWithNotifications(): List<Event>

    @Query("SELECT * FROM Event ORDER BY id")
    suspend fun allForBackup(): List<Event>

    @Query("DELETE FROM Event")
    suspend fun deleteAll()

    /** Atomically claim one logical occurrence; returns 1 only for its first delivery. */
    @Query(
        "UPDATE Event SET lastNotifiedOccurrenceAt = :occurrenceAt " +
            "WHERE id = :id AND notificationLeadMinutes IS NOT NULL " +
            "AND (lastNotifiedOccurrenceAt IS NULL OR lastNotifiedOccurrenceAt != :occurrenceAt)"
    )
    suspend fun claimNotification(id: Long, occurrenceAt: Long): Int

    @Query(
        "UPDATE Event SET lastNotifiedOccurrenceAt = NULL " +
            "WHERE id = :id AND lastNotifiedOccurrenceAt = :occurrenceAt"
    )
    suspend fun releaseNotificationClaim(id: Long, occurrenceAt: Long)

    @Query("DELETE FROM Event WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: Reminder): Long

    @Delete
    suspend fun delete(reminder: Reminder)

    @Query("SELECT * FROM Reminder WHERE done = 0 ORDER BY triggerAt")
    fun observePending(): Flow<List<Reminder>>

    @Query("SELECT * FROM Reminder ORDER BY triggerAt")
    fun observeAll(): Flow<List<Reminder>>

    @Query("SELECT * FROM Reminder WHERE id = :id")
    suspend fun getById(id: Long): Reminder?

    /** Not-done reminders — used to reschedule every pending alarm after reboot/update. */
    @Query("SELECT * FROM Reminder WHERE done = 0 AND alarmFired = 0")
    suspend fun allPending(): List<Reminder>

    @Query("SELECT * FROM Reminder ORDER BY id")
    suspend fun allForBackup(): List<Reminder>

    @Query("DELETE FROM Reminder")
    suspend fun deleteAll()

    @Query("UPDATE Reminder SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Query("UPDATE Reminder SET triggerAt = :triggerAt, snoozedUntil = :snoozedUntil WHERE id = :id")
    suspend fun reschedule(id: Long, triggerAt: Long, snoozedUntil: Long?)

    @Query("UPDATE Reminder SET alarmFired = :fired WHERE id = :id")
    suspend fun setAlarmFired(id: Long, fired: Boolean)

    /** Claim the row's current effective trigger exactly once before posting a notification. */
    @Query(
        "UPDATE Reminder SET lastNotifiedTriggerAt = :scheduledAt " +
            "WHERE id = :id AND done = 0 " +
            "AND COALESCE(snoozedUntil, triggerAt) = :scheduledAt " +
            "AND (lastNotifiedTriggerAt IS NULL OR lastNotifiedTriggerAt != :scheduledAt)"
    )
    suspend fun claimNotification(id: Long, scheduledAt: Long): Int

    @Query(
        "UPDATE Reminder SET lastNotifiedTriggerAt = NULL " +
            "WHERE id = :id AND lastNotifiedTriggerAt = :scheduledAt"
    )
    suspend fun releaseNotificationClaim(id: Long, scheduledAt: Long)

    @Query("DELETE FROM Reminder WHERE id = :id")
    suspend fun deleteById(id: Long)
}
