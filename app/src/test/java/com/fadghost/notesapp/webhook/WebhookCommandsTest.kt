package com.fadghost.notesapp.webhook

import com.fadghost.notesapp.data.webhook.CommandParse
import com.fadghost.notesapp.data.webhook.WebhookCommand
import com.fadghost.notesapp.data.webhook.WebhookCommands
import com.fadghost.notesapp.data.webhook.WebhookParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Pure parsing of the automation-webhook command envelope. Deterministic: a fixed
 * UTC zone resolves clock-less local ISO strings, and a fixed [today] resolves the
 * default diary date. Mirrors the house test style (JUnit4, injected now/zone).
 */
class WebhookCommandsTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 7, 20)

    private fun parse(body: String): List<CommandParse> =
        WebhookCommands.parse(body, zone, today)

    private fun one(body: String): CommandParse = parse(body).single()

    private fun ok(body: String): WebhookCommand =
        (one(body) as CommandParse.Ok).command

    private fun err(body: String): String =
        (one(body) as CommandParse.Err).message

    // --- Envelope ---------------------------------------------------------------

    @Test fun malformedJsonThrows() {
        assertThrows(WebhookParseException::class.java) { parse("{not json") }
    }

    @Test fun missingCommandsArrayThrows() {
        assertThrows(WebhookParseException::class.java) { parse("""{"foo":1}""") }
    }

    @Test fun commandsNotAnArrayThrows() {
        assertThrows(WebhookParseException::class.java) { parse("""{"commands":{}}""") }
    }

    @Test fun emptyBatchIsEmptyList() {
        assertTrue(parse("""{"commands":[]}""").isEmpty())
    }

    @Test fun nonObjectCommandIsPerItemError() {
        assertEquals(
            "Command must be a JSON object",
            err("""{"commands":[3]}""")
        )
    }

    @Test fun unknownTypeIsPerItemError() {
        assertEquals(
            "Unknown command type: \"frobnicate\"",
            err("""{"commands":[{"type":"frobnicate"}]}""")
        )
    }

    @Test fun missingTypeIsPerItemError() {
        assertEquals("Missing \"type\"", err("""{"commands":[{"title":"x"}]}"""))
    }

    // --- create_note ------------------------------------------------------------

    @Test fun createNoteFull() {
        val c = ok("""{"commands":[{"type":"create_note","title":"Groceries","body":"milk","tags":["home","food"]}]}""")
        c as WebhookCommand.CreateNote
        assertEquals("Groceries", c.title)
        assertEquals("milk", c.body)
        assertEquals(listOf("home", "food"), c.tags)
    }

    @Test fun createNoteBodyAndTagsDefault() {
        val c = ok("""{"commands":[{"type":"create_note","title":"Solo"}]}""") as WebhookCommand.CreateNote
        assertEquals("", c.body)
        assertTrue(c.tags.isEmpty())
    }

    @Test fun createNoteMissingTitle() {
        assertEquals("create_note requires \"title\"", err("""{"commands":[{"type":"create_note"}]}"""))
    }

    @Test fun createNoteBlankTitle() {
        assertEquals(
            "create_note requires \"title\"",
            err("""{"commands":[{"type":"create_note","title":"   "}]}""")
        )
    }

    // --- create_reminder --------------------------------------------------------

    @Test fun createReminderLocalIso() {
        val c = ok("""{"commands":[{"type":"create_reminder","title":"Post TikTok video 1","when":"2026-07-21T12:00","url":"https://x.test/1"}]}""")
        c as WebhookCommand.CreateReminder
        assertEquals("Post TikTok video 1", c.title)
        assertEquals("https://x.test/1", c.url)
        assertEquals(Instant.parse("2026-07-21T12:00:00Z").toEpochMilli(), c.triggerAt)
    }

    @Test fun createReminderOffsetIso() {
        val c = ok("""{"commands":[{"type":"create_reminder","title":"Call","when":"2026-07-21T12:00:00+01:00"}]}""")
        c as WebhookCommand.CreateReminder
        assertEquals(Instant.parse("2026-07-21T11:00:00Z").toEpochMilli(), c.triggerAt)
        assertNull(c.url)
    }

    @Test fun createReminderMissingWhen() {
        assertEquals(
            "create_reminder requires \"when\" (ISO-8601)",
            err("""{"commands":[{"type":"create_reminder","title":"x"}]}""")
        )
    }

    @Test fun createReminderBadIso() {
        assertEquals(
            "create_reminder \"when\" is not a valid ISO-8601 date: \"yesterday\"",
            err("""{"commands":[{"type":"create_reminder","title":"x","when":"yesterday"}]}""")
        )
    }

    // --- create_event -----------------------------------------------------------

    @Test fun createEventWithEnd() {
        val c = ok("""{"commands":[{"type":"create_event","title":"Standup","start":"2026-07-21T09:00","end":"2026-07-21T09:30","note":"daily"}]}""")
        c as WebhookCommand.CreateEvent
        assertEquals(Instant.parse("2026-07-21T09:00:00Z").toEpochMilli(), c.startAt)
        assertEquals(Instant.parse("2026-07-21T09:30:00Z").toEpochMilli(), c.endAt)
        assertEquals("daily", c.note)
    }

    @Test fun createEventEndBeforeStart() {
        assertEquals(
            "create_event \"end\" is before \"start\"",
            err("""{"commands":[{"type":"create_event","title":"x","start":"2026-07-21T09:00","end":"2026-07-21T08:00"}]}""")
        )
    }

    @Test fun createEventBadStart() {
        assertEquals(
            "create_event \"start\" is not a valid ISO-8601 date: \"soon\"",
            err("""{"commands":[{"type":"create_event","title":"x","start":"soon"}]}""")
        )
    }

    // --- create_diary -----------------------------------------------------------

    @Test fun createDiaryDefaultsToToday() {
        val c = ok("""{"commands":[{"type":"create_diary","text":"Felt good"}]}""") as WebhookCommand.CreateDiary
        assertEquals("2026-07-20", c.date)
        assertEquals("Felt good", c.text)
    }

    @Test fun createDiaryExplicitDate() {
        val c = ok("""{"commands":[{"type":"create_diary","date":"2026-01-02","text":"x"}]}""") as WebhookCommand.CreateDiary
        assertEquals("2026-01-02", c.date)
    }

    @Test fun createDiaryDatetimeNormalisedToDate() {
        val c = ok("""{"commands":[{"type":"create_diary","date":"2026-01-02T23:15:00Z","text":"x"}]}""") as WebhookCommand.CreateDiary
        assertEquals("2026-01-02", c.date)
    }

    @Test fun createDiaryMissingText() {
        assertEquals(
            "create_diary requires \"text\"",
            err("""{"commands":[{"type":"create_diary","date":"2026-01-02"}]}""")
        )
    }

    @Test fun createDiaryBadDate() {
        assertEquals(
            "create_diary \"date\" is not a valid ISO date: \"nope\"",
            err("""{"commands":[{"type":"create_diary","date":"nope","text":"x"}]}""")
        )
    }

    // --- append_note (selector rules) ------------------------------------------

    @Test fun appendNoteById() {
        val c = ok("""{"commands":[{"type":"append_note","noteId":42,"text":"more"}]}""") as WebhookCommand.AppendNote
        assertEquals(42L, c.noteId)
        assertNull(c.titleMatch)
        assertEquals("more", c.text)
    }

    @Test fun appendNoteByTitleMatch() {
        val c = ok("""{"commands":[{"type":"append_note","titleMatch":"Shopping","text":"eggs"}]}""") as WebhookCommand.AppendNote
        assertEquals("Shopping", c.titleMatch)
        assertNull(c.noteId)
    }

    @Test fun appendNoteBothSelectorsRejected() {
        assertEquals(
            "append_note requires exactly one of \"noteId\" or \"titleMatch\"",
            err("""{"commands":[{"type":"append_note","noteId":1,"titleMatch":"x","text":"y"}]}""")
        )
    }

    @Test fun appendNoteNeitherSelectorRejected() {
        assertEquals(
            "append_note requires exactly one of \"noteId\" or \"titleMatch\"",
            err("""{"commands":[{"type":"append_note","text":"y"}]}""")
        )
    }

    @Test fun appendNoteMissingText() {
        assertEquals(
            "append_note requires \"text\"",
            err("""{"commands":[{"type":"append_note","noteId":1}]}""")
        )
    }

    // --- list_reminders ---------------------------------------------------------

    @Test fun listRemindersNoBounds() {
        val c = ok("""{"commands":[{"type":"list_reminders"}]}""") as WebhookCommand.ListReminders
        assertNull(c.from)
        assertNull(c.to)
    }

    @Test fun listRemindersRange() {
        val c = ok("""{"commands":[{"type":"list_reminders","from":"2026-07-20T00:00","to":"2026-07-27T00:00"}]}""") as WebhookCommand.ListReminders
        assertEquals(Instant.parse("2026-07-20T00:00:00Z").toEpochMilli(), c.from)
        assertEquals(Instant.parse("2026-07-27T00:00:00Z").toEpochMilli(), c.to)
    }

    @Test fun listRemindersToBeforeFrom() {
        assertEquals(
            "list_reminders \"to\" is before \"from\"",
            err("""{"commands":[{"type":"list_reminders","from":"2026-07-27T00:00","to":"2026-07-20T00:00"}]}""")
        )
    }

    // --- batch semantics --------------------------------------------------------

    @Test fun mixedBatchPreservesOrderAndPerItemErrors() {
        val results = parse(
            """{"commands":[
                {"type":"create_note","title":"ok"},
                {"type":"nope"},
                {"type":"create_reminder","title":"r","when":"2026-07-21T12:00"}
            ]}"""
        )
        assertEquals(3, results.size)
        assertTrue(results[0] is CommandParse.Ok)
        assertTrue(results[1] is CommandParse.Err)
        assertTrue(results[2] is CommandParse.Ok)
    }

    @Test fun fourteenTikTokRemindersAllParse() {
        val cmds = buildString {
            append("""{"commands":[""")
            var n = 1
            for (day in 0 until 7) {
                for (hour in listOf(12, 19)) {
                    val date = LocalDate.of(2026, 7, 21).plusDays(day.toLong())
                    val time = "%02d:00".format(hour)
                    append("""{"type":"create_reminder","title":"Post TikTok video $n","when":"${date}T$time","url":"https://x.test/$n"}""")
                    if (!(day == 6 && hour == 19)) append(",")
                    n++
                }
            }
            append("]}")
        }
        val results = parse(cmds)
        assertEquals(14, results.size)
        assertTrue(results.all { it is CommandParse.Ok })
    }
}
