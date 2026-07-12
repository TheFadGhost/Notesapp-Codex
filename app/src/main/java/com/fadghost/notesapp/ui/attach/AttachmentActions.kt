package com.fadghost.notesapp.ui.attach

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.fadghost.notesapp.data.db.entity.Attachment
import java.io.File

/**
 * Share / open-external helpers for an attachment (M-A). Both hand the file to the
 * system chooser as a read-only `content://` uri via [FileProvider] — the user always
 * picks the target app, so nothing is sent on their behalf silently.
 */
object AttachmentActions {

    private fun uriFor(context: Context, att: Attachment) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(att.path)
    )

    fun share(context: Context, att: Attachment) {
        val uri = runCatching { uriFor(context, att) }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = att.mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share attachment").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun openExternally(context: Context, att: Attachment) {
        val uri = runCatching { uriFor(context, att) }.getOrNull() ?: return
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, att.mime.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(view, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
