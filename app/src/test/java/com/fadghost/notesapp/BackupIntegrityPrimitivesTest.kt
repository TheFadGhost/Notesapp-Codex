package com.fadghost.notesapp

import com.fadghost.notesapp.data.backup.AttachmentRestoreFiles
import com.fadghost.notesapp.data.repo.TagNames
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupIntegrityPrimitivesTest {
    @Test fun tagNormalizationTrimsAndCollapsesWhitespace() {
        assertEquals("Work plans", TagNames.normalize("  Work\t  plans  "))
    }

    @Test fun attachmentWriterIsStrictAndCleanupRemovesItsDirectory() {
        val root = Files.createTempDirectory("notesapp-restore").toFile()
        try {
            val file = File(root, "42/file.bin")
            val store = AttachmentRestoreFiles()
            store.write(file, byteArrayOf(1, 2, 3))
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())

            val noteDirectory = file.parentFile!!
            store.cleanup(file)
            assertFalse(file.exists())
            assertFalse(noteDirectory.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun attachmentWriterSurfacesParentPathFailure() {
        val root = Files.createTempDirectory("notesapp-restore-failure").toFile()
        try {
            val parentAsFile = File(root, "blocked").apply { writeText("not a directory") }
            assertThrows(IOException::class.java) {
                AttachmentRestoreFiles().write(File(parentAsFile, "file.bin"), byteArrayOf(1))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
