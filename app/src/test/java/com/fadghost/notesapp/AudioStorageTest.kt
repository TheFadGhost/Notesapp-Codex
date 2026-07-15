package com.fadghost.notesapp

import com.fadghost.notesapp.data.audio.AudioStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Attachment orphan detection + storage totals (PLAN.md §6). */
class AudioStorageTest {
    @Test fun recordingSessionsUseDifferentPathsAndPreserveEarlierFiles() {
        val filesDir = createTempDir(prefix = "voice_sessions_")
        val noteDir = AudioStorage.noteDir(filesDir, 9)
        val firstDir = AudioStorage.sessionDir(noteDir, "session-a")
        val secondDir = AudioStorage.sessionDir(noteDir, "session-b")
        val first = AudioStorage.segmentFile(firstDir, 0).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = AudioStorage.segmentFile(secondDir, 0)
        assertTrue(first.absolutePath != second.absolutePath)
        assertTrue(first.exists())
        filesDir.deleteRecursively()
    }

    @Test fun nestedEmptySessionDirectoriesArePrunedButRootRemains() {
        val filesDir = createTempDir(prefix = "voice_prune_")
        val root = AudioStorage.root(filesDir).apply { mkdirs() }
        val empty = AudioStorage.sessionDir(AudioStorage.noteDir(filesDir, 3), "gone").apply { mkdirs() }
        assertTrue(empty.exists())
        AudioStorage.pruneEmptyDirectories(root)
        assertTrue(!empty.exists())
        assertTrue(root.exists())
        filesDir.deleteRecursively()
    }

    @get:Rule val tmp = TemporaryFolder()

    private fun write(dir: File, name: String, bytes: Int): File {
        dir.mkdirs()
        return File(dir, name).apply { writeBytes(ByteArray(bytes)) }
    }

    @Test fun findsOrphansNotReferencedByAnyRow() {
        val filesDir = tmp.newFolder("files")
        val root = AudioStorage.root(filesDir)
        val note1 = AudioStorage.noteDir(filesDir, 1)
        val note2 = AudioStorage.noteDir(filesDir, 2)
        val live = write(note1, "segment_000.m4a", 10)
        val orphanA = write(note1, "segment_001.m4a", 20) // discarded segment, no row
        val orphanB = write(note2, "segment_000.m4a", 30) // purged note's leftovers

        val orphans = AudioStorage.findOrphans(root, referenced = setOf(live.absolutePath))
        val paths = orphans.map { it.absolutePath }.toSet()
        assertEquals(2, orphans.size)
        assertTrue(orphanA.absolutePath in paths)
        assertTrue(orphanB.absolutePath in paths)
        assertTrue(live.absolutePath !in paths)
    }

    @Test fun noOrphansWhenAllReferenced() {
        val filesDir = tmp.newFolder("files")
        val root = AudioStorage.root(filesDir)
        val note1 = AudioStorage.noteDir(filesDir, 1)
        val a = write(note1, "segment_000.m4a", 10)
        val b = write(note1, "segment_001.m4a", 10)
        val orphans = AudioStorage.findOrphans(root, setOf(a.absolutePath, b.absolutePath))
        assertTrue(orphans.isEmpty())
    }

    @Test fun missingRootHasNoOrphansAndZeroBytes() {
        val filesDir = tmp.newFolder("files")
        val root = AudioStorage.root(filesDir) // never created
        assertTrue(AudioStorage.findOrphans(root, emptySet()).isEmpty())
        assertEquals(0L, AudioStorage.totalBytes(root))
    }

    @Test fun totalBytesSumsAllFiles() {
        val filesDir = tmp.newFolder("files")
        val root = AudioStorage.root(filesDir)
        write(AudioStorage.noteDir(filesDir, 1), "segment_000.m4a", 100)
        write(AudioStorage.noteDir(filesDir, 2), "segment_000.m4a", 250)
        assertEquals(350L, AudioStorage.totalBytes(root))
        assertEquals(100L, AudioStorage.noteBytes(filesDir, 1))
    }

    @Test fun formatSizeRendersKbAndMb() {
        assertEquals("0 KB", AudioStorage.formatSize(0))
        assertEquals("1 KB", AudioStorage.formatSize(1024))
        assertEquals("1.0 MB", AudioStorage.formatSize(1024 * 1024))
    }

    @Test fun eachCaptureGetsAnIsolatedNonOverwritingDirectory() {
        val filesDir = tmp.newFolder("files")
        val first = AudioStorage.recordingDir(filesDir, noteId = 9L, sessionId = "session_one")
        val second = AudioStorage.recordingDir(filesDir, noteId = 9L, sessionId = "session_two")
        assertTrue(first.absolutePath.endsWith("attachments${File.separator}9${File.separator}voice_sessions${File.separator}session_one"))
        assertTrue(second.absolutePath.endsWith("attachments${File.separator}9${File.separator}voice_sessions${File.separator}session_two"))
        assertTrue(first != second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalInSessionId() {
        val filesDir = tmp.newFolder("files")
        AudioStorage.recordingDir(filesDir, noteId = 9L, sessionId = "../escape")
    }

    @Test fun pruningRemovesEmptyNestedCaptureDirectoriesBottomUp() {
        val filesDir = tmp.newFolder("files")
        val note = AudioStorage.noteDir(filesDir, 9L)
        val capture = AudioStorage.recordingDir(filesDir, 9L, "finished")
        capture.mkdirs()
        assertEquals(3, AudioStorage.pruneEmptyDirectories(note, deleteRoot = true))
        assertTrue(!capture.exists())
        assertTrue(!note.exists())
    }
}
