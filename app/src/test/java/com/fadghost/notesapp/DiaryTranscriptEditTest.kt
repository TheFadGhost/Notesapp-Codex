package com.fadghost.notesapp

import com.fadghost.notesapp.ui.diary.DiaryTranscriptEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiaryTranscriptEditTest {
    @Test fun appendPreservesExistingEntryAndTracksOnlyRawSpeech() {
        val edit = DiaryTranscriptEdit.append("Earlier paragraph.", " um I went home ")
        assertEquals("Earlier paragraph.\n\num I went home", edit.text)
        assertEquals("um I went home", edit.text.substring(edit.start, edit.end))
    }

    @Test fun cleanupReplacesOnlyUneditedTranscript() {
        val edit = DiaryTranscriptEdit.append("Keep this.", "um went home")
        assertEquals("Keep this.\n\nI went home.", DiaryTranscriptEdit.replaceIfUnchanged(edit.text, edit, "I went home."))
        assertNull(DiaryTranscriptEdit.replaceIfUnchanged(edit.text + " edited", edit.copy(raw = "different"), "clean"))
    }
}
