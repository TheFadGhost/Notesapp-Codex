package com.fadghost.notesapp.data.backup

import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Small injectable file seam for attachment restore. Production writes are strict;
 * tests can fail a chosen write and prove that the repository rolls back rows and
 * removes every file created earlier in the attempt.
 */
open class AttachmentRestoreFiles @Inject constructor() {
    open fun write(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw IOException("Attachment has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create attachment directory: $parent")
        }
        file.outputStream().use { it.write(bytes) }
        if (file.length() != bytes.size.toLong()) {
            throw IOException("Attachment write was incomplete: $file")
        }
    }

    open fun cleanup(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Could not remove partial attachment: $file")
        file.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.let { parent ->
            if (!parent.delete()) throw IOException("Could not remove empty attachment directory: $parent")
        }
    }
}
