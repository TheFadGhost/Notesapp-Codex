package com.fadghost.notesapp

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.fadghost.notesapp.ui.diary.insertTranscriptAtSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptInsertionTest {

    @Test fun insertsIntoEmptyEntry() {
        val result = insertTranscriptAtSelection(TextFieldValue(""), "  hello world  ")
        assertEquals("hello world", result.text)
        assertEquals(TextRange(11), result.selection)
    }

    @Test fun appendsWithoutJoiningWords() {
        val result = insertTranscriptAtSelection(
            TextFieldValue("Today was", TextRange(9)),
            "productive"
        )
        assertEquals("Today was productive", result.text)
        assertEquals(TextRange(result.text.length), result.selection)
    }

    @Test fun insertsAtStartWithOneSeparator() {
        val result = insertTranscriptAtSelection(
            TextFieldValue("existing words", TextRange(0)),
            "New thought"
        )
        assertEquals("New thought existing words", result.text)
        assertEquals(TextRange(12), result.selection)
    }

    @Test fun replacesForwardSelection() {
        val result = insertTranscriptAtSelection(
            TextFieldValue("one old three", TextRange(4, 7)),
            "new"
        )
        assertEquals("one new three", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    @Test fun replacesReversedSelection() {
        val result = insertTranscriptAtSelection(
            TextFieldValue("one old three", TextRange(7, 4)),
            "new"
        )
        assertEquals("one new three", result.text)
        assertEquals(TextRange(7), result.selection)
    }

    @Test fun preservesExistingNewlineBoundary() {
        val result = insertTranscriptAtSelection(
            TextFieldValue("First line\nSecond", TextRange(11)),
            "Spoken line"
        )
        assertEquals("First line\nSpoken line Second", result.text)
    }

    @Test fun doesNotInsertSpaceBeforePunctuation() {
        val result = insertTranscriptAtSelection(
            TextFieldValue("It worked!", TextRange(9)),
            "today"
        )
        assertEquals("It worked today!", result.text)
    }

    @Test fun blankTranscriptDoesNothing() {
        val original = TextFieldValue("Keep me", TextRange(4))
        assertEquals(original, insertTranscriptAtSelection(original, " \n "))
    }
}
