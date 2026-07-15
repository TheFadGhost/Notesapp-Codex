package com.fadghost.notesapp

import com.fadghost.notesapp.ui.calendar.EventEndTime
import org.junit.Assert.assertEquals
import org.junit.Test

class EventEndTimeTest {
    @Test fun enablingEndGetsSensibleOneHourDefault() {
        assertEquals(4_600_000L, EventEndTime.defaultFor(1_000_000L))
    }

    @Test fun movingStartRetainsChosenDuration() {
        val oldStart = 10_000L
        val twoHourEnd = oldStart + 7_200_000L
        val newStart = 50_000L
        assertEquals(newStart + 7_200_000L, EventEndTime.moveWithStart(oldStart, newStart, twoHourEnd))
    }

    @Test fun enabledEndIsAlwaysStrictlyAfterStart() {
        val start = 1_000_000L
        assertEquals(start + EventEndTime.MIN_DURATION_MS, EventEndTime.validEnd(start, start))
        assertEquals(start + EventEndTime.MIN_DURATION_MS, EventEndTime.validEnd(start, start - 1L))
    }

    @Test fun validChosenEndIsPreserved() {
        assertEquals(9_000_000L, EventEndTime.validEnd(1_000_000L, 9_000_000L))
    }
}
