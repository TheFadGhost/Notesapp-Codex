package com.fadghost.notesapp

import com.fadghost.notesapp.ui.editor.normalizeOrganizeName
import org.junit.Assert.assertEquals
import org.junit.Test

class OrganizePanelLogicTest {

    @Test fun normalizeTrimsAndCollapsesWhitespace() {
        assertEquals("Project Alpha", normalizeOrganizeName("  Project\n\tAlpha  "))
    }

    @Test fun normalizeKeepsIntentionalPunctuationAndCase() {
        assertEquals("Mum's plans — 2026", normalizeOrganizeName("Mum's plans — 2026"))
    }

    @Test fun normalizeBlankInputStaysBlank() {
        assertEquals("", normalizeOrganizeName(" \n\t "))
    }
}
