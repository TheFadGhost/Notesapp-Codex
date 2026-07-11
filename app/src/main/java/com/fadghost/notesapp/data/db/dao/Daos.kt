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

    @Query("SELECT * FROM Note WHERE deletedAt IS NULL AND archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<Note>>

    @Query("SELECT * FROM Note WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<Note>>

    /**
     * Full-text search over the FTS5 external-content table [note_fts].
     * Uses [RawQuery] so Room does not compile-time validate the virtual table
     * (which is created via the database callback, not as a Room entity).
     */
    @RawQuery(observedEntities = [Note::class])
    fun searchRaw(query: SimpleSQLiteQuery): Flow<List<Note>>

    fun search(match: String): Flow<List<Note>> = searchRaw(
        SimpleSQLiteQuery(
            "SELECT n.* FROM Note n JOIN note_fts f ON n.id = f.rowid " +
                "WHERE f.note_fts MATCH ? AND n.deletedAt IS NULL " +
                "ORDER BY rank",
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

    @Query("SELECT * FROM Folder ORDER BY name")
    fun observeAll(): Flow<List<Folder>>
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: Tag): Long

    @Delete
    suspend fun delete(tag: Tag)

    @Query("SELECT * FROM Tag ORDER BY name")
    fun observeAll(): Flow<List<Tag>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(crossRef: NoteTagCrossRef)

    @Delete
    suspend fun unlink(crossRef: NoteTagCrossRef)

    @Query("SELECT t.* FROM Tag t JOIN NoteTagCrossRef x ON t.id = x.tagId WHERE x.noteId = :noteId")
    fun observeTagsForNote(noteId: Long): Flow<List<Tag>>
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
}
