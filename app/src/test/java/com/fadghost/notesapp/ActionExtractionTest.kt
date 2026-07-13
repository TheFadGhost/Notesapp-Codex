package com.fadghost.notesapp

import com.fadghost.notesapp.data.ai.parse.ActionExtractionParser
import com.fadghost.notesapp.data.ai.parse.ActionType
import com.fadghost.notesapp.data.ai.parse.ExtractOutcome
import com.fadghost.notesapp.data.ai.parse.ExtractionValidator
import com.fadghost.notesapp.data.ai.parse.RawExtractItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class ActionExtractionTest {

    private val parser = ActionExtractionParser()
    private val zone = ZoneOffset.UTC
    // 2026-07-11T00:00:00Z
    private val now = Instant.parse("2026-07-11T00:00:00Z").toEpochMilli()

    @Test fun parsesCleanJsonIntoProposals() {
        val raw = """{"items":[
            {"type":"event","title":"Dentist","datetime":"2026-07-12T09:00:00Z"},
            {"type":"todo","title":"Buy milk"}
        ]}"""
        val out = parser.parse(raw, now, zone) as ExtractOutcome.Success
        assertEquals(2, out.items.size)
        assertEquals(ActionType.EVENT, out.items[0].type)
        assertEquals(ActionType.TODO, out.items[1].type)
        assertNull(out.items[1].datetimeMillis)
    }

    @Test fun parsesProseWrappedJson() {
        val raw = "Here are the actions: {\"items\":[{\"type\":\"reminder\",\"title\":\"Call\"}]}. Done!"
        val out = parser.parse(raw, now, zone) as ExtractOutcome.Success
        assertEquals(1, out.items.size)
        assertEquals(ActionType.REMINDER, out.items[0].type)
    }

    @Test fun truncatedJsonIsParseFailure() {
        val raw = """{"items":[{"type":"todo","title":"a"""
        assertTrue(parser.parse(raw, now, zone) is ExtractOutcome.ParseFailure)
    }

    @Test fun validatorClampsToMaxTenItems() {
        val items = (1..15).map { RawExtractItem(type = "todo", title = "task $it") }
        val out = ExtractionValidator().validate(items, now, zone)
        assertEquals(10, out.items.size)
        assertTrue(out.warnings.any { it.contains("first 10") })
    }

    @Test fun validatorDropsBlankTitles() {
        val items = listOf(
            RawExtractItem(type = "todo", title = "   "),
            RawExtractItem(type = "todo", title = "keep me")
        )
        val out = ExtractionValidator().validate(items, now, zone)
        assertEquals(1, out.items.size)
        assertEquals("keep me", out.items[0].title)
    }

    @Test fun validatorDropsUnknownTypes() {
        val items = listOf(RawExtractItem(type = "banana", title = "x"))
        val out = ExtractionValidator().validate(items, now, zone)
        assertTrue(out.items.isEmpty())
        assertTrue(out.warnings.any { it.contains("unknown type") })
    }

    @Test fun validatorClampsFarFutureDateToFiveYears() {
        val items = listOf(RawExtractItem(type = "event", title = "far", datetime = "2100-01-01T00:00:00Z"))
        val out = ExtractionValidator(maxYears = 5).validate(items, now, zone)
        val fiveYears = 5L * 365 * 24 * 60 * 60 * 1000
        assertEquals(now + fiveYears, out.items[0].datetimeMillis)
        assertTrue(out.warnings.any { it.contains("Clamped") })
    }

    @Test fun validatorClampsFarPastDate() {
        val items = listOf(RawExtractItem(type = "reminder", title = "old", datetime = "1990-01-01T00:00:00Z"))
        val out = ExtractionValidator(maxYears = 5).validate(items, now, zone)
        val fiveYears = 5L * 365 * 24 * 60 * 60 * 1000
        assertEquals(now - fiveYears, out.items[0].datetimeMillis)
    }

    @Test fun parsesDateOnlyDatetime() {
        val v = ExtractionValidator()
        val millis = v.parseIso("2026-08-01", zone)
        assertEquals(Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(), millis)
    }

    @Test fun parsesSpaceSeparatedDatetime() {
        val v = ExtractionValidator()
        val millis = v.parseIso("2026-08-01 14:30", zone)
        assertEquals(Instant.parse("2026-08-01T14:30:00Z").toEpochMilli(), millis)
    }

    // --- Ramble default-time rule ("remind me at 8" for timeless days) --------------

    @Test fun dateOnlyDefaultsToEightAmWhenConfigured() {
        val v = ExtractionValidator(dateOnlyDefaultTime = LocalTime.of(8, 0))
        val millis = v.parseIso("2026-08-01", zone)
        assertEquals(Instant.parse("2026-08-01T08:00:00Z").toEpochMilli(), millis)
    }

    @Test fun dateOnlyStaysMidnightWithoutDefault() {
        // The normal Extract flow is unchanged: no default → start-of-day (00:00).
        val v = ExtractionValidator()
        val millis = v.parseIso("2026-08-01", zone)
        assertEquals(Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(), millis)
    }

    @Test fun explicitTimeIsNeverOverriddenByDefault() {
        // "gym at 6pm" must stay 18:00 even when the ramble default is set.
        val v = ExtractionValidator(dateOnlyDefaultTime = LocalTime.of(8, 0))
        assertEquals(
            Instant.parse("2026-08-01T18:00:00Z").toEpochMilli(),
            v.parseIso("2026-08-01T18:00:00Z", zone)
        )
        assertEquals(
            Instant.parse("2026-08-01T14:30:00Z").toEpochMilli(),
            v.parseIso("2026-08-01 14:30", zone)
        )
    }

    @Test fun rambleValidatorArmsTimelessReminderAtEight() {
        // End-to-end through validate(): "tomorrow, go gym" (date-only) → 08:00 reminder.
        val v = ExtractionValidator(dateOnlyDefaultTime = LocalTime.of(8, 0))
        val items = listOf(RawExtractItem(type = "reminder", title = "go gym", datetime = "2026-07-12"))
        val out = v.validate(items, now, zone)
        assertEquals(1, out.items.size)
        assertEquals(ActionType.REMINDER, out.items[0].type)
        assertEquals(
            Instant.parse("2026-07-12T08:00:00Z").toEpochMilli(),
            out.items[0].datetimeMillis
        )
    }

    @Test fun ramblerParserWiresDefaultThroughToProposals() {
        // The parser variant the ramble orchestrator constructs.
        val rambleParser = ActionExtractionParser(
            validator = ExtractionValidator(dateOnlyDefaultTime = LocalTime.of(8, 0))
        )
        val raw = """{"items":[{"type":"reminder","title":"call mum","datetime":"2026-07-12"}]}"""
        val out = rambleParser.parse(raw, now, zone) as ExtractOutcome.Success
        assertEquals(
            Instant.parse("2026-07-12T08:00:00Z").toEpochMilli(),
            out.items[0].datetimeMillis
        )
    }
}
