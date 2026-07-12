package com.fadghost.notesapp.data.ai.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fadghost.notesapp.data.ai.AiRepository
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.attach.AttachmentRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Silent background image indexer (M-A P7). Enqueued once per image ingest with a
 * CONNECTED constraint, so it waits for connectivity then OCRs/describes the image via
 * the vision model and stores the result on the attachment row (which re-folds it into
 * the owning note's FTS). This runs SILENTLY — it NEVER shows a toast/pill (V3-DELIGHT
 * silence rule). Failure is non-fatal: transient errors retry with backoff; after a few
 * attempts (or a permanent error / no key) it gives up quietly.
 */
class ImageIndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun aiRepository(): AiRepository
        fun attachments(): AttachmentRepository
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ATTACHMENT_ID, -1L)
        if (id <= 0) return Result.success()
        val ep = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val attachments = ep.attachments()
        val att = attachments.byId(id) ?: return Result.success() // removed since enqueue
        if (!att.isImage) return Result.success()
        // Already indexed (either field set) — nothing to do.
        if (att.ocrText != null || att.description != null) return Result.success()
        // No key yet: give up quietly; a later ingest will re-enqueue when a key exists.
        if (!ep.aiRepository().hasKeyNow()) return Result.success()

        val bytes = attachments.readBytes(att) ?: return Result.success()
        return try {
            val index = ep.aiRepository().indexImage(att.noteId, bytes, att.mime)
            // Store even when empty so the row counts as indexed and won't loop forever.
            attachments.setIndex(id, index.ocrText ?: "", index.description ?: "")
            Result.success()
        } catch (e: OpenRouterError) {
            if (isTransient(e) && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private fun isTransient(e: OpenRouterError): Boolean = when (e) {
        is OpenRouterError.RateLimited -> true
        is OpenRouterError.Network -> true
        is OpenRouterError.Unknown -> e.status in 500..599
        else -> false
    }

    companion object {
        const val KEY_ATTACHMENT_ID = "attachment_id"
        private const val PREFIX = "image_index_"
        private const val MAX_ATTEMPTS = 5

        fun uniqueName(attachmentId: Long) = "$PREFIX$attachmentId"

        /** Queue indexing for [attachmentId], running when the network returns. */
        fun enqueue(context: Context, attachmentId: Long) {
            val request = OneTimeWorkRequestBuilder<ImageIndexWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ATTACHMENT_ID to attachmentId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(attachmentId),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
