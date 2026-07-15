package com.fadghost.notesapp.ui.diary

data class TranscriptInsertion(val text: String, val start: Int, val end: Int, val raw: String)

/** Pure range bookkeeping so cleanup replaces only the transcript, never nearby diary text. */
object DiaryTranscriptEdit {
    fun append(existing: String, transcript: String): TranscriptInsertion {
        val raw = transcript.trim()
        val separator = when {
            existing.isBlank() -> ""
            existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        val start = existing.length + separator.length
        return TranscriptInsertion(existing + separator + raw, start, start + raw.length, raw)
    }

    fun replaceIfUnchanged(current: String, insertion: TranscriptInsertion, cleaned: String): String? {
        if (insertion.start !in 0..current.length || insertion.end !in insertion.start..current.length) return null
        if (current.substring(insertion.start, insertion.end) != insertion.raw) return null
        return current.replaceRange(insertion.start, insertion.end, cleaned.trim())
    }
}
