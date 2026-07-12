package com.fadghost.notesapp.ui.reminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.alarm.AlarmScheduler
import com.fadghost.notesapp.data.db.dao.ReminderDao
import com.fadghost.notesapp.data.db.entity.Reminder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.TimeZone
import javax.inject.Inject

/** Result of validating a quick-reminder before creation (ux.md P1-4). */
enum class ReminderValidation { Ok, BlankTitle, PastTime }

/**
 * Minimal reminder creator for the capture-sheet "Quick reminder" action
 * (PLAN.md §4/§8). Since M3 it also arms the exact alarm so the reminder actually
 * fires, via the shared [AlarmScheduler].
 */
@HiltViewModel
class QuickReminderViewModel @Inject constructor(
    private val reminderDao: ReminderDao,
    private val alarmScheduler: AlarmScheduler
) : ViewModel() {

    companion object {
        /**
         * Pure, side-effect-free gate for the Create button (ux.md P1-4). [nowMillis] is
         * injected so the rule is deterministic under test. A blank title fails first; a
         * trigger at-or-before now is a past time and must be blocked, never silently created.
         */
        fun validate(title: String, whenMillis: Long, nowMillis: Long): ReminderValidation = when {
            title.isBlank() -> ReminderValidation.BlankTitle
            whenMillis <= nowMillis -> ReminderValidation.PastTime
            else -> ReminderValidation.Ok
        }
    }

    fun create(title: String, triggerAt: Long, onDone: () -> Unit) {
        val clean = title.trim().ifBlank { "Reminder" }
        viewModelScope.launch {
            val id = reminderDao.upsert(
                Reminder(title = clean, triggerAt = triggerAt, timezone = TimeZone.getDefault().id)
            )
            reminderDao.getById(id)?.let { alarmScheduler.scheduleReminder(it) }
            onDone()
        }
    }
}
