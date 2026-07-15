package com.fadghost.notesapp

import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.ui.notes.NoteFilter
import com.fadghost.notesapp.ui.notes.filterForTag
import com.fadghost.notesapp.ui.notes.noteFilterSummary
import com.fadghost.notesapp.ui.notes.shouldSearchOrganizeFilters
import com.fadghost.notesapp.ui.notes.visibleOrganizeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeFilterPanelLogicTest {

    @Test
    fun summariesDescribeEverySingleFilterState() {
        assertEquals("All notes", noteFilterSummary(NoteFilter.All))
        assertEquals("Untagged", noteFilterSummary(NoteFilter.Untagged))
        assertEquals("Archived notes", noteFilterSummary(NoteFilter.Archived))
        assertEquals("Trash", noteFilterSummary(NoteFilter.Trash))
        assertEquals("Tag · #urgent", noteFilterSummary(NoteFilter.WithTag(7, "urgent")))
    }

    @Test
    fun tagFactoryPreservesExactlyOneSelection() {
        val tagFilter = filterForTag(Tag(id = 9, name = "Ideas", color = 0))

        assertEquals(NoteFilter.WithTag(9, "Ideas"), tagFilter)
        assertTrue(tagFilter is NoteFilter.WithTag)
    }

    @Test
    fun tagSearchIsCaseInsensitiveAndKeepsSelectedMatchFirst() {
        val tags = listOf(
            Tag(id = 1, name = "Project Alpha"),
            Tag(id = 2, name = "alpha errands"),
            Tag(id = 3, name = "Later")
        )

        val visible = visibleOrganizeTags(tags, " ALPHA ", NoteFilter.WithTag(2, "alpha errands"))

        assertEquals(listOf(2L, 1L), visible.map { it.id })
    }

    @Test
    fun searchAppearsOnlyWhenTagCollectionIsLong() {
        val eightTags = (1L..8L).map { Tag(id = it, name = "Tag $it") }
        val nineTags = (1L..9L).map { Tag(id = it, name = "Tag $it") }

        assertFalse(shouldSearchOrganizeFilters(eightTags))
        assertTrue(shouldSearchOrganizeFilters(nineTags))
    }
}
