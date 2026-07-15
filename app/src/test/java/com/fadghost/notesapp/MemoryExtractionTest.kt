package com.fadghost.notesapp

import com.fadghost.notesapp.data.ai.parse.MemoryExtractOutcome
import com.fadghost.notesapp.data.ai.parse.MemoryExtractionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 MEMORY_EXTRACT_V1 output parsing (V3-PROMPTS.md §1.2). Proves the defensive parse +
 * clamp: prose-wrapped JSON, field limits, op:update, and the empty/skipped path.
 */
class MemoryExtractionTest {

    private val parser = MemoryExtractionParser()
    private val now = System.currentTimeMillis()
    private val todayIso = java.time.Instant.ofEpochMilli(now)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    @Test
    fun parsesProseWrappedEntriesAndClamps() {
        val raw = """
            Sure, here you go:
            {"entries":[
              {"op":"create","slug":"Gym Schedule!","title":"Gym schedule","type":"routine",
               "tags":["Health","planning","health","x","y","z"],"links":["weekly-plan","gym-schedule"],
               "hook":"Gym Mon/Wed/Fri 7am","body":"Trains Mon, Wed, Fri at 7am."}
            ],"skipped_reason":null}
            Hope that helps!
        """.trimIndent()
        val outcome = parser.parse(raw, now, "note:5")
        assertTrue(outcome is MemoryExtractOutcome.Success)
        val entries = (outcome as MemoryExtractOutcome.Success).entries
        assertEquals(1, entries.size)
        val e = entries.first().model
        assertEquals("gym-schedule", e.slug)          // slug kebab-sanitized
        assertEquals("routine", e.type)
        assertEquals(5, e.tags.size)                  // clamped to 5, de-duped
        assertTrue("self-link dropped", !e.links.contains("gym-schedule"))
        assertEquals("note:5", e.source)
        assertEquals(todayIso, e.created)
    }

    @Test
    fun mapsOpUpdate() {
        val raw = """{"entries":[{"op":"update","slug":"weekly-plan","title":"Weekly plan","type":"project","tags":[],"links":[],"hook":"h","body":"Merged body."}],"skipped_reason":null}"""
        val outcome = parser.parse(raw, now, "note:1") as MemoryExtractOutcome.Success
        assertTrue(outcome.entries.first().isUpdate)
    }

    @Test
    fun emptyEntriesCarriesSkippedReason() {
        val raw = """{"entries":[],"skipped_reason":"Nothing durable — just today's mood."}"""
        val outcome = parser.parse(raw, now, "note:1") as MemoryExtractOutcome.Success
        assertTrue(outcome.entries.isEmpty())
        assertEquals("Nothing durable — just today's mood.", outcome.skippedReason)
    }

    @Test
    fun truncatedJsonIsParseFailure() {
        val raw = """{"entries":[{"op":"create","slug":"a" """
        assertTrue(parser.parse(raw, now, "note:1") is MemoryExtractOutcome.ParseFailure)
    }

    @Test
    fun capsAtTenEntries() {
        val items = (1..15).joinToString(",") {
            """{"op":"create","slug":"s$it","title":"t$it","type":"fact","tags":[],"links":[],"hook":"h","body":"b$it"}"""
        }
        val outcome = parser.parse("""{"entries":[$items],"skipped_reason":null}""", now, "note:1") as MemoryExtractOutcome.Success
        assertEquals(10, outcome.entries.size)
    }

    @Test
    fun capitalizedWordsAreNotInferredAsTags() {
        val raw = """{"entries":[{"op":"create","slug":"meeting","title":"Alice Met NASA In London","type":"fact","tags":[],"links":[],"hook":"Meeting summary","body":"Alice met NASA in London."}],"skipped_reason":null}"""
        val outcome = parser.parse(raw, now, "note:2") as MemoryExtractOutcome.Success
        assertTrue(outcome.entries.single().model.tags.isEmpty())
    }

    @Test
    fun tagPromptForbidsCapitalizationInferenceAndHashCharacters() {
        val prompt = com.fadghost.notesapp.data.ai.AiPrompts.MEMORY_EXTRACT_V2
        assertTrue(prompt.contains("Never derive a tag merely because a word is"))
        assertTrue(prompt.contains("Do not put # characters"))
    }
}
