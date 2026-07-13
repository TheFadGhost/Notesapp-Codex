package com.fadghost.notesapp.data.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class AskTextTest {
    private val note = AskSource(
        citation = "note:42",
        label = "June plan",
        excerpt = "Book the train on Friday.",
        kind = AskSourceKind.NOTE,
        noteId = 42
    )
    private val memory = AskSource(
        citation = "gym-schedule",
        label = "Gym schedule",
        excerpt = "Monday and Wednesday at 7am.",
        kind = AskSourceKind.MEMORY,
        memorySlug = "gym-schedule"
    )

    @Test
    fun `citations retain first-seen order and remove duplicates`() {
        assertEquals(
            listOf("gym-schedule", "note:42"),
            AskText.citations("See [[gym-schedule]], [[note:42]], then [[gym-schedule]].")
        )
    }

    @Test
    fun `malformed citations are ignored`() {
        assertEquals(emptyList<String>(), AskText.citations("[[]] [[has spaces]] [[!bad]]"))
    }

    @Test
    fun `cited sources omit model-invented citations`() {
        assertEquals(
            listOf(note),
            AskText.citedSources("From [[note:42]] and [[invented]].", listOf(note, memory))
        )
    }

    @Test
    fun `citation tokens are removed cleanly for chip rendering`() {
        assertEquals(
            "I planned Friday, then Sunday.",
            AskText.withoutCitationTokens("I planned Friday [[note:42]], then Sunday [[gym-schedule]].")
        )
    }

    @Test
    fun `excerpt normalises whitespace and obeys hard character limit`() {
        assertEquals("one two\u2026", AskText.excerpt("  one\n two three  ", maxChars = 8))
        assertEquals("", AskText.excerpt("anything", maxChars = 0))
    }

    @Test
    fun `context block carries exact source markers`() {
        assertEquals(
            "MEMORY CONTEXT:\n[[note:42]] June plan\nBook the train on Friday.",
            AskText.contextBlock(listOf(note))
        )
        assertEquals("MEMORY CONTEXT:\nnone matched", AskText.contextBlock(emptyList()))
    }

    @Test
    fun `only trailing control markers are consumed`() {
        assertEquals(
            AskMarkers(
                visibleText = "I'll keep that and prepare the reminder.",
                saveMemoryFact = "Mum prefers calls after 6pm",
                extractActions = true
            ),
            AskMarkerParser.parse(
                "I'll keep that and prepare the reminder.\n\n" +
                    "SAVE_MEMORY: Mum prefers calls after 6pm\nEXTRACT_ACTIONS"
            )
        )
        assertEquals(
            AskMarkers("The literal EXTRACT_ACTIONS marker is documentation."),
            AskMarkerParser.parse("The literal EXTRACT_ACTIONS marker is documentation.")
        )
    }
}
