package com.fadghost.notesapp.alarm

import com.fadghost.notesapp.data.db.dao.EventDao
import com.fadghost.notesapp.data.db.dao.NoteDao
import com.fadghost.notesapp.data.db.dao.ReminderDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt access point for the broadcast receivers, which can't take constructor
 * injection. Mirrors the AiQueueWorker pattern (data/ai/work) — pull the
 * singletons off the application graph via
 * [dagger.hilt.android.EntryPointAccessors].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmEntryPoint {
    fun noteDao(): NoteDao
    fun eventDao(): EventDao
    fun reminderDao(): ReminderDao
    fun alarmScheduler(): AlarmScheduler
    fun eventAlarm(): EventAlarm
}
