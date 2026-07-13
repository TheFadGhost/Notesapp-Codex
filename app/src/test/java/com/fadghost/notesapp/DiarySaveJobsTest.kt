package com.fadghost.notesapp

import com.fadghost.notesapp.ui.diary.DiarySaveJobs
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DiarySaveJobsTest {

    @Test fun differentDatesDoNotCancelEachOther() = runTest {
        val registry = DiarySaveJobs()
        val completed = mutableListOf<String>()
        registry.launch(LocalDate.parse("2026-07-12"), this) {
            delay(100)
            completed += "today"
        }
        registry.launch(LocalDate.parse("2026-07-11"), this) {
            delay(100)
            completed += "past"
        }

        advanceUntilIdle()
        assertEquals(setOf("today", "past"), completed.toSet())
    }

    @Test fun newerSaveForSameDateCancelsOlderOne() = runTest {
        val registry = DiarySaveJobs()
        val completed = mutableListOf<String>()
        val date = LocalDate.parse("2026-07-12")
        registry.launch(date, this) {
            delay(100)
            completed += "old"
        }
        registry.launch(date, this) {
            delay(20)
            completed += "new"
        }

        advanceUntilIdle()
        assertEquals(listOf("new"), completed)
    }
}
