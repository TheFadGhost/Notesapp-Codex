package com.fadghost.notesapp.data.ai.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fadghost.notesapp.data.audio.AudioAttachmentRepository
import com.fadghost.notesapp.data.audio.RecordedSegment
import com.fadghost.notesapp.data.audio.VoiceCommit
import com.fadghost.notesapp.data.audio.VoiceCommitLogic
import com.fadghost.notesapp.data.audio.VoiceTranscriber
import com.fadghost.notesapp.data.repo.NotesRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest

/**
 * Offline voice-transcription queue (PLAN.md §5 — "offline → queue via the existing
 * WorkManager AI queue pattern with a visible queued state"). Recording finishes
 * offline: the segments are already on disk and the target note exists (created up
 * front), so we enqueue with a CONNECTED constraint. When the network returns we
 * transcribe every segment, append the transcript to the note, and record the audio
 * attachment. The recording UI surfaces the queued state via WorkInfo.
 */
class TranscribeQueueWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun transcriber(): VoiceTranscriber
        fun notesRepository(): NotesRepository
        fun voiceCommit(): VoiceCommit
        fun audioAttachments(): AudioAttachmentRepository
    }

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1L)
        val pathsCsv = inputData.getString(KEY_SEGMENT_PATHS).orEmpty()
        val durationsCsv = inputData.getString(KEY_SEGMENT_DURATIONS).orEmpty()
        if (noteId <= 0 || pathsCsv.isBlank()) return Result.failure()

        val paths = pathsCsv.split("\n").filter { it.isNotBlank() }
        val durations = durationsCsv.split("\n").mapNotNull { it.toLongOrNull() }
        val files = paths.map { File(it) }
        if (files.any { !it.isFile || it.length() <= 0L }) return Result.failure()

        val ep = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        ep.notesRepository().getNote(noteId) ?: return Result.failure()

        // Idempotency (audit M2): a retry must not re-bill STT or duplicate the commit.
        // If these exact segments were already committed on a prior attempt, we're done —
        // don't re-transcribe (the costly, billed step) or re-append.
        if (VoiceCommitLogic.existingCommit(ep.audioAttachments().forNote(noteId), paths) != null) {
            return Result.success()
        }

        return runCatching {
            val transcript = ep.transcriber().transcribe(files, noteId)
            val segments = paths.mapIndexed { i, p -> RecordedSegment(p, durations.getOrElse(i) { 0L }) }
            ep.voiceCommit().appendTranscript(noteId, transcript, segments)
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val KEY_NOTE_ID = "note_id"
        const val KEY_SEGMENT_PATHS = "segment_paths"
        const val KEY_SEGMENT_DURATIONS = "segment_durations"
        private const val PREFIX = "voice_transcribe_"

        /**
         * Work is scoped to the actual segment set, not merely the note. A user can make
         * several offline recordings in one note; the old note-only name plus REPLACE
         * silently discarded every earlier pending transcription.
         */
        fun uniqueName(noteId: Long, segments: List<RecordedSegment>): String {
            val bytes = segments.joinToString("\n") { it.path }.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "$PREFIX${noteId}_$digest"
        }

        /** Enqueue transcription of [segments] into [noteId] when the network returns. */
        fun enqueue(context: Context, noteId: Long, segments: List<RecordedSegment>) {
            val request = OneTimeWorkRequestBuilder<TranscribeQueueWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(
                    workDataOf(
                        KEY_NOTE_ID to noteId,
                        KEY_SEGMENT_PATHS to segments.joinToString("\n") { it.path },
                        KEY_SEGMENT_DURATIONS to segments.joinToString("\n") { it.durationMs.toString() }
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(noteId, segments),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
