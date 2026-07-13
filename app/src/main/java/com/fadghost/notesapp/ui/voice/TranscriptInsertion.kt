package com.fadghost.notesapp.ui.voice

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

data class TranscriptInsertionResult(
    val value: TextFieldValue,
    /** Exact offsets of the transcript, excluding whitespace added around it. */
    val transcriptStart: Int,
    val transcriptEnd: Int
)

/**
 * Replaces the current editor selection (or inserts at its caret), adds paragraph separation only
 * where needed, and returns stable offsets for the audio attachment anchor.
 */
object TranscriptInsertion {
    fun insert(current: TextFieldValue, transcript: String): TranscriptInsertionResult {
        val spoken = transcript.trim()
        if (spoken.isEmpty()) {
            val caret = current.selection.end.coerceIn(0, current.text.length)
            return TranscriptInsertionResult(current.copy(selection = TextRange(caret)), caret, caret)
        }

        val start = current.selection.min.coerceIn(0, current.text.length)
        val end = current.selection.max.coerceIn(start, current.text.length)
        val before = current.text.substring(0, start)
        val after = current.text.substring(end)
        val leading = paragraphGapAfter(before)
        val trailing = paragraphGapBefore(after)
        val transcriptStart = before.length + leading.length
        val transcriptEnd = transcriptStart + spoken.length
        val merged = before + leading + spoken + trailing + after

        return TranscriptInsertionResult(
            value = current.copy(
                text = merged,
                selection = TextRange(transcriptEnd),
                composition = null
            ),
            transcriptStart = transcriptStart,
            transcriptEnd = transcriptEnd
        )
    }

    private fun paragraphGapAfter(before: String): String = when {
        before.isEmpty() -> ""
        before.endsWith("\n\n") -> ""
        before.endsWith('\n') -> "\n"
        before.last().isWhitespace() -> ""
        else -> "\n\n"
    }

    private fun paragraphGapBefore(after: String): String = when {
        after.isEmpty() -> ""
        after.startsWith("\n\n") -> ""
        after.startsWith('\n') -> "\n"
        after.first().isWhitespace() -> ""
        else -> "\n\n"
    }
}
