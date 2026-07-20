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
import com.fadghost.notesapp.data.ai.AiRepository
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.ai.parse.ExtractOutcome
import com.fadghost.notesapp.data.ai.parse.RambleActionNormalizer
import com.fadghost.notesapp.data.ai.parse.RambleNote
import com.fadghost.notesapp.data.audio.PendingVoiceAction
import com.fadghost.notesapp.data.audio.VoiceCommit
import com.fadghost.notesapp.data.audio.VoiceDestination
import com.fadghost.notesapp.data.audio.VoiceSession
import com.fadghost.notesapp.data.audio.VoiceSessionPhase
import com.fadghost.notesapp.data.audio.VoiceSessionStore
import com.fadghost.notesapp.data.audio.VoiceTranscriber
import com.fadghost.notesapp.data.repo.NotesRepository
import com.fadghost.notesapp.data.repo.SystemTags
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Durable feature-A pipeline. Expensive outputs are persisted after each stage, note writes are
 * overwrite-idempotent, and [VoiceCommit.attachOnly] deduplicates the attachment on worker retry.
 */
class RamblePipelineWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun sessionStore(): VoiceSessionStore
        fun transcriber(): VoiceTranscriber
        fun aiRepository(): AiRepository
        fun notesRepository(): NotesRepository
        fun voiceCommit(): VoiceCommit
    }

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)
            ?.takeIf { VoiceSession.SAFE_ID.matches(it) }
            ?: return Result.failure()
        val ep = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val store = ep.sessionStore()
        var session = store.get(sessionId) ?: return Result.failure()

        if (session.destination != VoiceDestination.RAMBLE_NOTE) return Result.failure()
        if (session.phase == VoiceSessionPhase.COMPLETE ||
            session.phase == VoiceSessionPhase.AWAITING_CONFIRMATION
        ) return Result.success()

        val noteId = session.targetNoteId ?: return fail(store, sessionId, "missing_note", "Voice note is missing.")
        val files = session.segments.map { File(it.path) }
        if (files.isEmpty() || files.any { !it.isFile || it.length() <= 0L }) {
            return fail(store, sessionId, "missing_audio", "One or more audio segments are missing.")
        }
        val zone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(ZoneId.systemDefault())

        try {
            val transcript = session.transcript?.takeIf { it.isNotBlank() } ?: run {
                store.update(sessionId) {
                    it.copy(phase = VoiceSessionPhase.TRANSCRIBING, errorCode = null, errorMessage = null)
                }
                val text = ep.transcriber().transcribe(files, noteId).trim()
                if (text.isBlank()) {
                    return fail(store, sessionId, "empty_transcript", "The recording did not contain readable speech.")
                }
                session = requireNotNull(store.update(sessionId) {
                    it.copy(phase = VoiceSessionPhase.ORGANIZING, transcript = text)
                })
                text
            }

            if (session.rewrittenBody == null || session.rewrittenTitle == null) {
                val rewrite = rewriteOrFallback(ep.aiRepository(), transcript, noteId, session, zone)
                session = requireNotNull(store.update(sessionId) {
                    it.copy(
                        phase = VoiceSessionPhase.ORGANIZING,
                        rewrittenTitle = rewrite.title,
                        rewrittenBody = rewrite.body,
                        warnings = (it.warnings + listOfNotNull(rewrite.warning)).distinct()
                    )
                })
            }

            if (!session.noteCommitted) {
                val note = ep.notesRepository().getNote(noteId)
                    ?: return fail(store, sessionId, "missing_note", "Voice note was removed before processing finished.")
                ep.notesRepository().saveNote(
                    note.copy(
                        title = requireNotNull(session.rewrittenTitle).take(MAX_TITLE),
                        body = requireNotNull(session.rewrittenBody),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                // A Ramble note always starts under this ordinary tag. It remains removable by
                // the user; retries merely guarantee the just-created voice note is classified.
                ep.notesRepository().ensureTagOnNote(
                    noteId = noteId,
                    name = SystemTags.RAMBLER,
                    color = SystemTags.RAMBLER_COLOR
                )
                session = requireNotNull(store.update(sessionId) { it.copy(noteCommitted = true) })
            }

            if (!session.audioCommitted) {
                val committed = ep.voiceCommit().attachOnly(
                    noteId = noteId,
                    segments = session.segments,
                    transcriptStart = 0,
                    transcriptEnd = session.rewrittenBody.orEmpty().length
                )
                if (committed == null) {
                    return fail(store, sessionId, "audio_commit", "Couldn't attach the recording to its note.")
                }
                session = requireNotNull(store.update(sessionId) { it.copy(audioCommitted = true) })
            }

            if (!session.actionsExtracted) {
                session = extractActions(store, ep.aiRepository(), session, transcript, noteId, zone)
            }

            val next = if (session.pendingActions.isNotEmpty() || session.actionError != null) {
                VoiceSessionPhase.AWAITING_CONFIRMATION
            } else VoiceSessionPhase.COMPLETE
            store.update(sessionId) {
                it.copy(phase = next, errorCode = null, errorMessage = null)
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            store.update(sessionId) {
                it.copy(
                    phase = VoiceSessionPhase.ERROR,
                    errorCode = errorCode(t),
                    errorMessage = friendly(t)
                )
            }
            return if (isTransient(t)) Result.retry() else Result.failure()
        }
    }

    private suspend fun rewriteOrFallback(
        ai: AiRepository,
        transcript: String,
        noteId: Long,
        session: VoiceSession,
        zone: ZoneId
    ): RewriteResult {
        return try {
            val markdown = ai.rewriteOnce(transcript, noteId, session.capturedAt, zone).trim()
            val body = RambleNote.bodyWithoutTitle(markdown).ifBlank { transcript }
            RewriteResult(
                title = RambleNote.titleFrom(markdown)?.take(MAX_TITLE) ?: fallbackTitle(session, zone),
                body = body
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (isTransient(t)) throw t
            // STT already succeeded: a rewrite outage must never strand or erase the user's words.
            RewriteResult(
                title = fallbackTitle(session, zone),
                body = transcript,
                warning = "The transcript was saved verbatim because organising it failed."
            )
        }
    }

    private suspend fun extractActions(
        store: VoiceSessionStore,
        ai: AiRepository,
        session: VoiceSession,
        transcript: String,
        noteId: Long,
        zone: ZoneId
    ): VoiceSession {
        return try {
            when (val outcome = ai.extractRambleActionsV2(transcript, noteId, session.capturedAt, zone)) {
                is ExtractOutcome.Success -> {
                    val actionable = RambleActionNormalizer.actionable(outcome.items)
                    val todoWarning = if (actionable.datelessTodoCount > 0) {
                        listOf(
                            "${actionable.datelessTodoCount} dateless to-do" +
                                if (actionable.datelessTodoCount == 1) " was kept in the note." else "s were kept in the note."
                        )
                    } else emptyList()
                    requireNotNull(store.update(session.id) {
                        it.copy(
                            actionsExtracted = true,
                            pendingActions = actionable.items.mapIndexed { index, action ->
                                PendingVoiceAction(
                                    id = (index + 1).toLong(),
                                    type = action.type.name,
                                    title = action.title,
                                    datetimeMillis = action.datetimeMillis,
                                    notes = action.notes
                                )
                            },
                            warnings = (it.warnings + outcome.warnings + todoWarning).distinct(),
                            actionError = null
                        )
                    })
                }
                is ExtractOutcome.ParseFailure -> requireNotNull(store.update(session.id) {
                    it.copy(
                        actionsExtracted = true,
                        actionError = "Couldn't read the proposed reminders. Retry from the review sheet."
                    )
                })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Note creation is still a success. Persist a retryable confirm state rather than
            // failing/re-running STT and rewrite merely because action extraction failed.
            requireNotNull(store.update(session.id) {
                it.copy(
                    actionsExtracted = true,
                    actionError = "Couldn't propose reminders just now. Your voice note is safe."
                )
            })
        }
    }

    private suspend fun fail(
        store: VoiceSessionStore,
        sessionId: String,
        code: String,
        message: String
    ): Result {
        store.update(sessionId) {
            it.copy(phase = VoiceSessionPhase.ERROR, errorCode = code, errorMessage = message)
        }
        return Result.failure()
    }

    private fun fallbackTitle(session: VoiceSession, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(session.capturedAt).atZone(zone).toLocalDate()
        return "Voice note · ${date.format(FALLBACK_DATE)}"
    }

    private fun isTransient(t: Throwable): Boolean = when (t) {
        is OpenRouterError.Network, is OpenRouterError.RateLimited -> true
        is OpenRouterError.Unknown -> t.status >= 500
        else -> false
    }

    private fun errorCode(t: Throwable): String = when (t) {
        is OpenRouterError.InvalidKey -> "invalid_key"
        is OpenRouterError.NoCredit -> "no_credit"
        is OpenRouterError.RateLimited -> "rate_limited"
        is OpenRouterError.ModelUnavailable -> "model_unavailable"
        is OpenRouterError.Network -> "network"
        is OpenRouterError.Parse -> "parse"
        is OpenRouterError.BudgetReached -> "budget_reached"
        else -> "pipeline"
    }

    private fun friendly(t: Throwable): String = when (t) {
        is OpenRouterError.InvalidKey -> "Check your OpenRouter key in Settings."
        is OpenRouterError.NoCredit -> "Your OpenRouter account is out of credit."
        is OpenRouterError.RateLimited -> "Rate limited — this voice note will retry."
        is OpenRouterError.ModelUnavailable -> "The selected voice or text model is unavailable."
        is OpenRouterError.Network -> "Couldn't reach OpenRouter — this voice note will retry."
        is OpenRouterError.Parse -> "Couldn't read the transcription response."
        is OpenRouterError.BudgetReached -> "Monthly AI budget reached — raise it in Settings → AI. The audio is safe."
        else -> "Couldn't finish this voice note. The audio is safe."
    }

    private data class RewriteResult(val title: String, val body: String, val warning: String? = null)

    companion object {
        const val KEY_SESSION_ID = "voice_session_id"
        private const val PREFIX = "ramble_pipeline_"
        private const val MAX_TITLE = 120
        private val FALLBACK_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

        fun uniqueName(sessionId: String): String = "$PREFIX$sessionId"

        fun enqueue(context: Context, sessionId: String, replace: Boolean = false) {
            require(VoiceSession.SAFE_ID.matches(sessionId)) { "Unsafe voice session id" }
            val request = OneTimeWorkRequestBuilder<RamblePipelineWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_SESSION_ID to sessionId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(sessionId),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
