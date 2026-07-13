package com.fadghost.notesapp.ui.diary

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.max
import kotlin.math.min

/**
 * Insert a transcript at the current caret or replace the selected range without
 * gluing words together. The returned caret sits immediately after the inserted
 * text and any separator the helper added.
 */
fun insertTranscriptAtSelection(value: TextFieldValue, rawTranscript: String): TextFieldValue {
    val transcript = rawTranscript.trim()
    if (transcript.isEmpty()) return value

    val start = min(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val end = max(value.selection.start, value.selection.end).coerceIn(start, value.text.length)
    val before = value.text.substring(0, start)
    val after = value.text.substring(end)
    val leading = if (needsLeadingSpace(before, transcript)) " " else ""
    val trailing = if (needsTrailingSpace(transcript, after)) " " else ""
    val inserted = leading + transcript + trailing
    val text = before + inserted + after
    val caret = before.length + inserted.length

    return value.copy(text = text, selection = TextRange(caret), composition = null)
}

private fun needsLeadingSpace(before: String, transcript: String): Boolean =
    before.isNotEmpty() && !before.last().isWhitespace() && transcript.first() !in NO_SPACE_BEFORE

private fun needsTrailingSpace(transcript: String, after: String): Boolean =
    after.isNotEmpty() && !after.first().isWhitespace() &&
        after.first() !in NO_SPACE_BEFORE && transcript.last() !in OPENING_PUNCTUATION

private const val NO_SPACE_BEFORE = ".,!?;:)]}"
private const val OPENING_PUNCTUATION = "([{"
