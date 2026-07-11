package com.fadghost.notesapp.data.di

import android.content.Context
import androidx.room.Room
import com.fadghost.notesapp.data.db.NotesDatabase
import com.fadghost.notesapp.data.db.dao.DiaryDao
import com.fadghost.notesapp.data.db.dao.EventDao
import com.fadghost.notesapp.data.db.dao.FolderDao
import com.fadghost.notesapp.data.db.dao.NoteDao
import com.fadghost.notesapp.data.db.dao.ReminderDao
import com.fadghost.notesapp.data.db.dao.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NotesDatabase =
        Room.databaseBuilder(context, NotesDatabase::class.java, NotesDatabase.NAME)
            .addCallback(NotesDatabase.CALLBACK)
            .build()

    @Provides
    fun provideNoteDao(db: NotesDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideFolderDao(db: NotesDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideTagDao(db: NotesDatabase): TagDao = db.tagDao()

    @Provides
    fun provideDiaryDao(db: NotesDatabase): DiaryDao = db.diaryDao()

    @Provides
    fun provideEventDao(db: NotesDatabase): EventDao = db.eventDao()

    @Provides
    fun provideReminderDao(db: NotesDatabase): ReminderDao = db.reminderDao()
}
