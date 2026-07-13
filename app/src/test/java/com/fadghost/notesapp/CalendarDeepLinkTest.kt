package com.fadghost.notesapp

import com.fadghost.notesapp.ui.calendar.CalendarDeepLink
import com.fadghost.notesapp.ui.calendar.CalendarDeepLinkTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarDeepLinkTest {
    @Test fun consumeOnlyClearsTheMatchingRequest() {
        CalendarDeepLink.requestReminder(7)
        val request = requireNotNull(CalendarDeepLink.pendingRequest.value)
        assertEquals(CalendarDeepLinkTarget.Reminder(7), request.target)

        CalendarDeepLink.consume(request.token + 1)
        assertEquals(request, CalendarDeepLink.pendingRequest.value)

        CalendarDeepLink.consume(request.token)
        assertNull(CalendarDeepLink.pendingRequest.value)
    }

    @Test fun repeatedTapOfSameEventGetsANewToken() {
        CalendarDeepLink.requestEvent(8)
        val first = requireNotNull(CalendarDeepLink.pendingRequest.value)
        CalendarDeepLink.requestEvent(8)
        val second = requireNotNull(CalendarDeepLink.pendingRequest.value)

        assertNotEquals(first.token, second.token)
        assertEquals(CalendarDeepLinkTarget.Event(8), second.target)
        CalendarDeepLink.consume(second.token)
    }
}
