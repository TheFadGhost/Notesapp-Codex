package com.fadghost.notesapp.data.db

import androidx.room.TypeConverter
import com.fadghost.notesapp.data.db.entity.Recurrence

class Converters {
    @TypeConverter
    fun recurrenceToString(value: Recurrence): String = value.name

    @TypeConverter
    fun stringToRecurrence(value: String): Recurrence =
        runCatching { Recurrence.valueOf(value) }.getOrDefault(Recurrence.NONE)
}
