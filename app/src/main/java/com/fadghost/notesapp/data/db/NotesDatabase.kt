package com.fadghost.notesapp.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fadghost.notesapp.data.ai.cost.AiCallCost
import com.fadghost.notesapp.data.ai.model.CachedModel
import com.fadghost.notesapp.data.db.dao.AiCostDao
import com.fadghost.notesapp.data.db.dao.AttachmentDao
import com.fadghost.notesapp.data.db.dao.AudioAttachmentDao
import com.fadghost.notesapp.data.db.dao.CachedModelDao
import com.fadghost.notesapp.data.db.dao.DiaryDao
import com.fadghost.notesapp.data.db.dao.EventDao
import com.fadghost.notesapp.data.db.dao.FolderDao
import com.fadghost.notesapp.data.db.dao.MemoryDao
import com.fadghost.notesapp.data.db.dao.NoteDao
import com.fadghost.notesapp.data.db.dao.ReminderDao
import com.fadghost.notesapp.data.db.dao.TagDao
import com.fadghost.notesapp.data.db.entity.Attachment
import com.fadghost.notesapp.data.db.entity.AudioAttachment
import com.fadghost.notesapp.data.db.entity.DiaryEntry
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Folder
import com.fadghost.notesapp.data.db.entity.MemoryEntry
import com.fadghost.notesapp.data.db.entity.MemoryLink
import com.fadghost.notesapp.data.db.entity.Note
import com.fadghost.notesapp.data.db.entity.NoteTagCrossRef
import com.fadghost.notesapp.data.db.entity.Reminder
import com.fadghost.notesapp.data.db.entity.Tag

@Database(
    entities = [
        Note::class,
        Folder::class,
        Tag::class,
        NoteTagCrossRef::class,
        DiaryEntry::class,
        Event::class,
        Reminder::class,
        AiCallCost::class,
        CachedModel::class,
        AudioAttachment::class,
        Attachment::class,
        MemoryEntry::class,
        MemoryLink::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun tagDao(): TagDao
    abstract fun diaryDao(): DiaryDao
    abstract fun eventDao(): EventDao
    abstract fun reminderDao(): ReminderDao
    abstract fun aiCostDao(): AiCostDao
    abstract fun cachedModelDao(): CachedModelDao
    abstract fun audioAttachmentDao(): AudioAttachmentDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        const val NAME = "notes.db"

        /** Creates the FTS4 search indexes on first run (framework SQLite has no fts5). */
        val CALLBACK: Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                NotesFts.create(db)
                MemoryFts.create(db)
            }
        }
    }
}
