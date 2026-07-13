package com.fadghost.notesapp.data.audio

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable voice-session journal. Each session has its own directory and AtomicFile manifest,
 * which makes a worker/process restart safe without putting transient orchestration state in Room.
 */
@Singleton
class VoiceSessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val lock = Mutex()
    private val root: File get() = AudioStorage.sessionsRoot(context.filesDir)

    private val _sessions = MutableStateFlow(loadAll())
    val sessions: StateFlow<List<VoiceSession>> = _sessions.asStateFlow()

    private val _runtime = MutableStateFlow<VoiceRuntimeState?>(null)
    val runtime: StateFlow<VoiceRuntimeState?> = _runtime.asStateFlow()

    fun sessionDir(sessionId: String): File = AudioStorage.sessionDir(context.filesDir, sessionId)
    fun transientAudioDir(sessionId: String): File = AudioStorage.transientAudioDir(context.filesDir, sessionId)

    suspend fun get(sessionId: String): VoiceSession? = lock.withLock {
        _sessions.value.firstOrNull { it.id == sessionId } ?: read(sessionId)
    }

    suspend fun put(session: VoiceSession): VoiceSession = lock.withLock {
        write(session)
        publish(session)
        session
    }

    suspend fun update(
        sessionId: String,
        transform: (VoiceSession) -> VoiceSession
    ): VoiceSession? = lock.withLock {
        val current = _sessions.value.firstOrNull { it.id == sessionId } ?: read(sessionId)
            ?: return@withLock null
        val updated = transform(current).copy(updatedAt = System.currentTimeMillis())
        require(updated.id == sessionId) { "A voice session id cannot change" }
        write(updated)
        publish(updated)
        updated
    }

    /** Re-scan manifests after a worker/process restart or an external recovery action. */
    suspend fun refresh(): List<VoiceSession> = lock.withLock {
        loadAll().also { _sessions.value = it }
    }

    /** Remove metadata and transient session audio. Note-owned attachment audio is not touched. */
    suspend fun remove(sessionId: String) = lock.withLock {
        requireSafeId(sessionId)
        runCatching { sessionDir(sessionId).deleteRecursively() }
        _sessions.value = _sessions.value.filterNot { it.id == sessionId }
        if (_runtime.value?.sessionId == sessionId) _runtime.value = null
    }

    fun publishRuntime(state: VoiceRuntimeState) {
        requireSafeId(state.sessionId)
        _runtime.value = state
    }

    fun clearRuntime(sessionId: String) {
        if (_runtime.value?.sessionId == sessionId) _runtime.value = null
    }

    private fun manifest(sessionId: String): AtomicFile {
        requireSafeId(sessionId)
        val dir = sessionDir(sessionId)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Could not create voice session directory")
        return AtomicFile(File(dir, MANIFEST))
    }

    private fun write(session: VoiceSession) {
        val atomic = manifest(session.id)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(session).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }

    private fun read(sessionId: String): VoiceSession? {
        val file = File(AudioStorage.sessionDir(context.filesDir, sessionId), MANIFEST)
        // Let AtomicFile inspect/restore its .bak even when the base file vanished between
        // rename and recreation during a process kill.
        return runCatching {
            AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                json.decodeFromString<VoiceSession>(reader.readText())
            }
        }.getOrNull()
    }

    private fun loadAll(): List<VoiceSession> {
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            if (!VoiceSession.SAFE_ID.matches(dir.name)) null else read(dir.name)
        }.sortedByDescending { it.updatedAt }
    }

    private fun publish(session: VoiceSession) {
        _sessions.value = (_sessions.value.filterNot { it.id == session.id } + session)
            .sortedByDescending { it.updatedAt }
    }

    private fun requireSafeId(id: String) {
        require(VoiceSession.SAFE_ID.matches(id)) { "Unsafe voice session id" }
    }

    private companion object {
        const val MANIFEST = "session.json"
    }
}
