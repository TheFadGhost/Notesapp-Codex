package com.fadghost.notesapp

import com.fadghost.notesapp.data.memory.MemoryEntryModel
import com.fadghost.notesapp.data.memory.MemoryFormat
import com.fadghost.notesapp.data.memory.MemoryVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM tests for the memory vault format + file storage (V3-PROMPTS.md §1.1). No
 * Android, no Room — the file layer takes explicit roots so it runs on a temp dir.
 */
class MemoryVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun model(slug: String = "gym-schedule", body: String = "Gym Mon/Wed/Fri 7am, goal 3x weekly.") =
        MemoryEntryModel(
            slug = slug,
            title = "Gym schedule",
            type = "routine",
            tags = listOf("health", "planning"),
            links = listOf("weekly-plan"),
            hook = "Gym Mon/Wed/Fri 7am, goal 3x weekly",
            source = "note:12",
            created = "2026-07-12",
            updated = "2026-07-12",
            body = body
        )

    @Test
    fun frontMatterRoundTrips() {
        val original = model()
        val parsed = MemoryEntryModel.parse(original.toMarkdown())
        assertNotNull(parsed)
        assertEquals(original.slug, parsed!!.slug)
        assertEquals(original.title, parsed.title)
        assertEquals(original.type, parsed.type)
        assertEquals(original.tags, parsed.tags)
        assertEquals(original.links, parsed.links)
        assertEquals(original.hook, parsed.hook)
        assertEquals(original.source, parsed.source)
        assertEquals(original.body, parsed.body)
    }

    @Test
    fun slugSanitizeEnforcesKebabAndLength() {
        assertEquals("gym-schedule", MemoryFormat.sanitizeSlug("Gym Schedule!"))
        assertEquals("weekly-plan", MemoryFormat.sanitizeSlug("  Weekly   Plan  "))
        assertTrue(MemoryFormat.sanitizeSlug("x".repeat(80)).length <= MemoryFormat.SLUG_MAX)
        assertEquals("fact", MemoryFormat.sanitizeType("nonsense"))
        assertEquals("person", MemoryFormat.sanitizeType("PERSON"))
    }

    @Test
    fun indexLineIsStrictAndParsesBack() {
        val line = model().indexLine()
        assertEquals("- gym-schedule | routine | Gym Mon/Wed/Fri 7am, goal 3x weekly", line)
        val parsed = MemoryFormat.parseIndexLine(line)
        assertNotNull(parsed)
        assertEquals("gym-schedule", parsed!!.first)
        assertEquals("routine", parsed.second)
    }

    @Test
    fun bodyClampedTo120Words() {
        val long = (1..200).joinToString(" ") { "w$it" }
        assertEquals(120, MemoryFormat.clampBody(long).split(" ").size)
    }

    @Test
    fun vaultWriteReadDeleteAndDerivedIndex() {
        val dir = tmp.root
        MemoryVault.writeEntries(dir, listOf(model("gym-schedule"), model("weekly-plan")))

        assertEquals(2, MemoryVault.readAllEntries(dir).size)
        assertNotNull(MemoryVault.readEntry(dir, "gym-schedule"))

        // index.md is derived from the entries and lists both, sorted by slug.
        val index = MemoryVault.readIndex(dir)
        assertTrue(index.contains("- gym-schedule |"))
        assertTrue(index.contains("- weekly-plan |"))

        MemoryVault.deleteEntry(dir, "gym-schedule")
        assertNull(MemoryVault.readEntry(dir, "gym-schedule"))
        assertTrue(!MemoryVault.readIndex(dir).contains("- gym-schedule |"))
    }

    @Test
    fun checksumChangesWithContentAndExportRoundTrips() {
        val dir = tmp.root
        MemoryVault.writeEntries(dir, listOf(model("gym-schedule")))
        val c1 = MemoryVault.checksum(dir)
        MemoryVault.writeEntries(dir, listOf(model("weekly-plan")))
        val c2 = MemoryVault.checksum(dir)
        assertTrue(c1 != c2)

        val bytes = MemoryVault.exportBytes(dir)
        assertTrue(bytes.containsKey("memory/index.md"))
        assertTrue(bytes.keys.any { it.startsWith("memory/entries/") })

        // Import into a fresh dir reproduces the same entries.
        val dir2 = tmp.newFolder()
        MemoryVault.importBytes(dir2, bytes, replace = true)
        assertEquals(2, MemoryVault.readAllEntries(dir2).size)
    }

    @Test
    fun backupImportMergePreservesUnrelatedEntriesAndRegeneratesIndex() {
        val dir = tmp.root
        MemoryVault.writeEntries(dir, listOf(model("existing", "old")))
        val imported = model("imported", "new")
        MemoryVault.importBytes(
            dir,
            mapOf(
                "memory/index.md" to "THIS INDEX MUST NOT WIN".toByteArray(),
                "memory/entries/imported.md" to imported.toMarkdown().toByteArray()
            ),
            replace = false
        )

        assertEquals(listOf("existing", "imported"), MemoryVault.readAllEntries(dir).map { it.slug })
        val index = MemoryVault.readIndex(dir)
        assertTrue(index.contains("- existing |"))
        assertTrue(index.contains("- imported |"))
        assertTrue(!index.contains("THIS INDEX MUST NOT WIN"))
    }

    @Test
    fun backupImportReplaceDropsOldEntriesAndRegeneratesIndex() {
        val dir = tmp.root
        MemoryVault.writeEntries(dir, listOf(model("existing", "old")))
        val imported = model("imported", "new")
        MemoryVault.importBytes(
            dir,
            mapOf("memory/entries/imported.md" to imported.toMarkdown().toByteArray()),
            replace = true
        )

        assertEquals(listOf("imported"), MemoryVault.readAllEntries(dir).map { it.slug })
        assertTrue(MemoryVault.readIndex(dir).contains("- imported |"))
        assertTrue(!MemoryVault.readIndex(dir).contains("- existing |"))
    }

    @Test
    fun backupImportRejectsMalformedPathsAndSlugMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            MemoryVault.importBytes(
                tmp.root,
                mapOf("memory/../outside.md" to model("outside").toMarkdown().toByteArray()),
                replace = true
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MemoryVault.importBytes(
                tmp.root,
                mapOf("memory/entries/wrong.md" to model("actual").toMarkdown().toByteArray()),
                replace = true
            )
        }
    }
}
