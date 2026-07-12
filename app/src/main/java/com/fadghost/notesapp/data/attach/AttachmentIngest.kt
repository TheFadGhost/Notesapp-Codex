package com.fadghost.notesapp.data.attach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.fadghost.notesapp.data.db.entity.Attachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a picked / shared / dropped / pasted [Uri] (or raw bytes) into a stored
 * [Attachment] on a note (M-A ingest). Resolves the display name + mime via the
 * ContentResolver, re-compresses oversized images (>4 MB) keeping their dimensions
 * where possible, then persists through [AttachmentRepository]. Callers insert the
 * `[[att:<id>]]` token at the caret with the returned row's id.
 */
@Singleton
class AttachmentIngest @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AttachmentRepository
) {
    /** Ingest a content [uri] onto [noteId]. Returns the stored row, or null on failure. */
    suspend fun ingest(noteId: Long, uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val mime = cr.getType(uri)
            ?: MIME_BY_EXT[uri.toString().substringAfterLast('.', "").lowercase()]
            ?: "application/octet-stream"
        val name = queryDisplayName(uri) ?: defaultName(mime)
        val raw = runCatching { cr.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@withContext null
        store(noteId, raw, name, mime)
    }

    /** Ingest raw [bytes] (e.g. a decoded clipboard bitmap) onto [noteId]. */
    suspend fun ingestBytes(noteId: Long, bytes: ByteArray, displayName: String, mime: String): Attachment? =
        withContext(Dispatchers.IO) {
            if (bytes.isEmpty()) return@withContext null
            store(noteId, bytes, displayName, mime)
        }

    private suspend fun store(noteId: Long, bytes: ByteArray, name: String, mime: String): Attachment {
        val (finalBytes, finalMime) = maybeCompressImage(bytes, mime)
        val att = repository.store(noteId, finalBytes, name, finalMime)
        // Queue the silent P7 image-index job for images (runs when online).
        if (att.isImage) {
            com.fadghost.notesapp.data.ai.work.ImageIndexWorker.enqueue(context, att.id)
        }
        return att
    }

    /**
     * Re-compress an image over [MAX_IMAGE_BYTES], preserving dimensions first (drop
     * JPEG quality), only downscaling as a last resort. Animated GIFs and non-images
     * pass through untouched. Returns the (possibly new) bytes + mime.
     */
    private fun maybeCompressImage(bytes: ByteArray, mime: String): Pair<ByteArray, String> {
        if (bytes.size <= MAX_IMAGE_BYTES) return bytes to mime
        if (!mime.startsWith("image/") || mime == "image/gif") return bytes to mime

        // Guard against OOM on enormous source images: sub-sample huge ones on decode.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DECODE_PIXELS)
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: return bytes to mime

        try {
            // Keep dimensions; step quality down first.
            for (quality in intArrayOf(85, 72, 60)) {
                val out = encodeJpeg(bitmap, quality)
                if (out.size <= MAX_IMAGE_BYTES) return out to "image/jpeg"
            }
            // Still too big — downscale in steps, re-encoding at a modest quality.
            var scaled = bitmap
            repeat(4) {
                val w = (scaled.width * 0.75f).toInt().coerceAtLeast(1)
                val h = (scaled.height * 0.75f).toInt().coerceAtLeast(1)
                val next = Bitmap.createScaledBitmap(scaled, w, h, true)
                if (scaled !== bitmap) scaled.recycle()
                scaled = next
                val out = encodeJpeg(scaled, 72)
                if (out.size <= MAX_IMAGE_BYTES) {
                    if (scaled !== bitmap) scaled.recycle()
                    return out to "image/jpeg"
                }
            }
            val fallback = encodeJpeg(scaled, 60)
            if (scaled !== bitmap) scaled.recycle()
            return fallback to "image/jpeg"
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun defaultName(mime: String): String {
        val ext = AttachmentStorage.extFor(null, mime).ifBlank { "bin" }
        val stem = if (mime.startsWith("image/")) "image" else "file"
        return "$stem-${System.currentTimeMillis()}.$ext"
    }

    companion object {
        /** Images larger than this are re-compressed on ingest (M-A spec). */
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private const val MAX_DECODE_PIXELS = 24_000_000 // ~24 MP OOM guard

        private val MIME_BY_EXT = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "webp" to "image/webp", "gif" to "image/gif", "pdf" to "application/pdf"
        )

        private fun sampleSizeFor(w: Int, h: Int, maxPixels: Int): Int {
            if (w <= 0 || h <= 0) return 1
            var sample = 1
            while ((w.toLong() * h) / (sample.toLong() * sample) > maxPixels) sample *= 2
            return sample
        }
    }
}
