package com.fadghost.notesapp

import com.fadghost.notesapp.data.ai.parse.ActionType
import com.fadghost.notesapp.data.ai.parse.ProposedAction
import com.fadghost.notesapp.data.ai.parse.RambleActionNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class RambleActionNormalizerTest {
    @Test fun datedTodoBecomesReminder() {
        val normalized = RambleActionNormalizer.normalize(
            ProposedAction(ActionType.TODO, "Go to the gym", 1_800_000L, null)
        )
        assertEquals(ActionType.REMINDER, normalized.type)
        assertEquals(1_800_000L, normalized.datetimeMillis)
    }

    @Test fun datelessTodoStaysInNoteAndIsNotInsertable() {
        val result = RambleActionNormalizer.actionable(
            listOf(
                ProposedAction(ActionType.TODO, "Someday idea", null, null),
                ProposedAction(ActionType.EVENT, "Dentist", 5_000L, null)
            )
        )
        assertEquals(1, result.datelessTodoCount)
        assertEquals(listOf(ActionType.EVENT), result.items.map { it.type })
    }

    @Test fun existingReminderAndEventAreUnchanged() {
        val items = listOf(
            ProposedAction(ActionType.REMINDER, "Call", 5_000L, "note"),
            ProposedAction(ActionType.EVENT, "Meet", 6_000L, null)
        )
        assertEquals(items, RambleActionNormalizer.normalize(items))
    }
}
