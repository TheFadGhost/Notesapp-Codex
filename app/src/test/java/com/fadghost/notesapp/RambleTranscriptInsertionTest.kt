package com.fadghost.notesapp

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.fadghost.notesapp.ui.voice.TranscriptInsertion
import org.junit.Assert.assertEquals
import org.junit.Test

class RambleTranscriptInsertionTest {
    @Test fun insertsIntoEmptyEditorAndAnchorsExactSpeech() {
        val result = TranscriptInsertion.insert(TextFieldValue(""), "  hello there  ")
        assertEquals("hello there", result.value.text)
        assertEquals(0, result.transcriptStart)
        assertEquals(11, result.transcriptEnd)
        assertEquals(TextRange(11), result.value.selection)
    }

    @Test fun insertsAtCaretWithParagraphGaps() {
        val result = TranscriptInsertion.insert(
            TextFieldValue("AlphaBeta", selection = TextRange(5)),
            "spoken"
        )
        assertEquals("Alpha\n\nspoken\n\nBeta", result.value.text)
        assertEquals(7, result.transcriptStart)
        assertEquals(13, result.transcriptEnd)
        assertEquals(TextRange(13), result.value.selection)
    }

    @Test fun replacesReversedSelectionWithoutDuplicatingExistingNewlines() {
        val result = TranscriptInsertion.insert(
            TextFieldValue("Before\n\nold\n\nAfter", selection = TextRange(13, 8)),
            "new"
        )
        assertEquals("Before\n\nnew\n\nAfter", result.value.text)
        assertEquals(8, result.transcriptStart)
        assertEquals(11, result.transcriptEnd)
    }

    @Test fun blankTranscriptLeavesTextUntouched() {
        val original = TextFieldValue("Keep me", selection = TextRange(2, 5))
        val result = TranscriptInsertion.insert(original, " \n ")
        assertEquals("Keep me", result.value.text)
        assertEquals(TextRange(5), result.value.selection)
        assertEquals(5, result.transcriptStart)
        assertEquals(5, result.transcriptEnd)
    }
}
