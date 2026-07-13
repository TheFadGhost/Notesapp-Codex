package com.fadghost.notesapp.alarm

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the [ReminderAlarm] seam to the concrete [AlarmScheduler] singleton. Call
 * sites that only need to schedule/cancel (the AI layer, the application onCreate
 * re-arm) inject [ReminderAlarm]; the receivers and calendar VM keep using the
 * concrete class directly.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmModule {
    @Binds
    @Singleton
    abstract fun bindReminderAlarm(impl: AlarmScheduler): ReminderAlarm

    @Binds
    @Singleton
    abstract fun bindEventAlarm(impl: EventAlarmScheduler): EventAlarm
}
