package com.fadghost.notesapp.ui.attach

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dependency-free image decoding for attachment thumbnails / viewer / annotate (M-A):
 * BitmapFactory with `inSampleSize` down-sampling, so we never add Coil/Glide and never
 * decode a full 12 MP bitmap for a small thumbnail.
 */
object AttachmentImages {

    /** Decode [path] down-sampled to at least [reqW]×[reqH]px. Null if not decodable. */
    fun decodeDownsampled(path: String, reqW: Int, reqH: Int): android.graphics.Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }
}

/** Load a down-sampled thumbnail off the main thread; null while decoding / on failure. */
@Composable
fun rememberThumbnail(path: String, reqPx: Int): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path, reqPx) {
        value = withContext(Dispatchers.IO) {
            AttachmentImages.decodeDownsampled(path, reqPx, reqPx)?.asImageBitmap()
        }
    }.value
