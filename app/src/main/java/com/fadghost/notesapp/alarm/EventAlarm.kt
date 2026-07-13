package com.fadghost.notesapp.alarm

import com.fadghost.notesapp.data.db.entity.Event

interface EventAlarm {
    fun scheduleEvent(event: Event)
    fun cancelEvent(eventId: Long)
    suspend fun rescheduleAll()
    fun canExact(): Boolean
}
