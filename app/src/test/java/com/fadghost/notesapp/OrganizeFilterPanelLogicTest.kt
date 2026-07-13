package com.fadghost.notesapp

import com.fadghost.notesapp.data.db.entity.Folder
import com.fadghost.notesapp.data.db.entity.Tag
import com.fadghost.notesapp.ui.notes.NoteFilter
import com.fadghost.notesapp.ui.notes.filterForFolder
import com.fadghost.notesapp.ui.notes.filterForTag
import com.fadghost.notesapp.ui.notes.noteFilterSummary
import com.fadghost.notesapp.ui.notes.shouldSearchOrganizeFilters
import com.fadghost.notesapp.ui.notes.visibleOrganizeFolders
import com.fadghost.notesapp.ui.notes.visibleOrganizeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizeFilterPanelLogicTest {

    @Test
    fun summariesDescribeEverySingleFilterState() {
        assertEquals("All notes", noteFilterSummary(NoteFilter.All))
        assertEquals("Unfiled / Untagged", noteFilterSummary(NoteFilter.Untagged))
        assertEquals("Archived notes", noteFilterSummary(NoteFilter.Archived))
        assertEquals("Trash", noteFilterSummary(NoteFilter.Trash))
        assertEquals("Notebook · Work", noteFilterSummary(NoteFilter.InFolder(4, "Work")))
        assertEquals("Tag · #urgent", noteFilterSummary(NoteFilter.WithTag(7, "urgent")))
    }

    @Test
    fun tagAndFolderFactoriesPreserveExactlyOneSelection() {
        val tagFilter = filterForTag(Tag(id = 9, name = "Ideas", color = 0))
        val folderFilter = filterForFolder(Folder(id = 3, name = "Home", createdAt = 1))

        assertEquals(NoteFilter.WithTag(9, "Ideas"), tagFilter)
        assertEquals(NoteFilter.InFolder(3, "Home"), folderFilter)
        assertTrue(tagFilter is NoteFilter.WithTag)
        assertFalse(tagFilter is NoteFilter.InFolder)
        assertTrue(folderFilter is NoteFilter.InFolder)
        assertFalse(folderFilter is NoteFilter.WithTag)
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
    fun folderSearchIsCaseInsensitiveAndKeepsSelectedMatchFirst() {
        val folders = listOf(
            Folder(id = 1, name = "Work Archive", createdAt = 1),
            Folder(id = 2, name = "archive box", createdAt = 2),
            Folder(id = 3, name = "Personal", createdAt = 3)
        )

        val visible = visibleOrganizeFolders(folders, "archive", NoteFilter.InFolder(2, "archive box"))

        assertEquals(listOf(2L, 1L), visible.map { it.id })
    }

    @Test
    fun searchAppearsOnlyWhenCombinedCollectionIsLong() {
        val tags = (1L..5L).map { Tag(id = it, name = "Tag $it") }
        val threeFolders = (1L..3L).map { Folder(id = it, name = "Folder $it", createdAt = it) }
        val fourFolders = (1L..4L).map { Folder(id = it, name = "Folder $it", createdAt = it) }

        assertFalse(shouldSearchOrganizeFilters(tags, threeFolders))
        assertTrue(shouldSearchOrganizeFilters(tags, fourFolders))
    }
}
