package com.fadghost.notesapp.ui.share

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.fadghost.notesapp.util.Markdown
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local, permission-free PDF export followed by the Android chooser. */
object DocumentShare {
    suspend fun sharePdf(context: Context, title: String, markdown: String) {
        val file = withContext(Dispatchers.IO) { createPdf(context, title, markdown) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "Notesapp export" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share PDF"
            )
        )
    }

    private fun createPdf(context: Context, title: String, markdown: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > DAY_MS }
            ?.forEach { runCatching { it.delete() } }
        val safeName = title.ifBlank { "note" }.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(48).ifBlank { "note" }
        val file = File(dir, "$safeName-${System.currentTimeMillis()}.pdf")
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 48f
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 34, 31); textSize = 12f }
        val titlePaint = Paint(bodyPaint).apply { textSize = 22f; isFakeBoldText = true }
        val lines = wrap(Markdown.strip(markdown), bodyPaint, pageWidth - margin * 2)
        val titleLines = wrap(title, titlePaint, pageWidth - margin * 2)
        try {
            var index = 0
            var pageNumber = 1
            do {
                val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber++).create())
                var y = margin
                if (index == 0 && title.isNotBlank()) {
                    titleLines.forEach { line ->
                        page.canvas.drawText(line, margin, y + 22f, titlePaint)
                        y += 30f
                    }
                    y += 18f
                }
                while (index < lines.size && y + 18f < pageHeight - margin) {
                    page.canvas.drawText(lines[index++], margin, y + 12f, bodyPaint)
                    y += 18f
                }
                document.finishPage(page)
            } while (index < lines.size)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    internal fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val out = mutableListOf<String>()
        text.lineSequence().forEach { paragraph ->
            if (paragraph.isBlank()) { out += ""; return@forEach }
            var line = ""
            paragraph.split(Regex("\\s+")).flatMap { splitLongWord(it, paint, maxWidth) }.forEach { word ->
                val next = if (line.isBlank()) word else "$line $word"
                if (paint.measureText(next) <= maxWidth) line = next
                else {
                    if (line.isNotBlank()) out += line
                    line = word
                }
            }
            if (line.isNotBlank()) out += line
        }
        return out
    }

    private fun splitLongWord(word: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(word) <= maxWidth) return listOf(word)
        val parts = mutableListOf<String>()
        var remaining = word
        while (remaining.isNotEmpty()) {
            var count = 1
            while (count < remaining.length && paint.measureText(remaining.substring(0, count + 1)) <= maxWidth) count++
            parts += remaining.substring(0, count)
            remaining = remaining.substring(count)
        }
        return parts
    }

    private const val DAY_MS = 86_400_000L
}
