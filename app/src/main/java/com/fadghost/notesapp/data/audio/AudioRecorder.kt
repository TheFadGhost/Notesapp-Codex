package com.fadghost.notesapp.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File

/**
 * Thin [MediaRecorder] wrapper that captures a voice ramble as AAC/M4A, mono, 16 kHz,
 * ~48 kbps (PLAN.md §3 audio row — never WAV, keeps every upload far under the STT
 * ceiling). Long recordings auto-split into sequential `segment_NNN.m4a` files at the
 * [AudioSegments.MAX_SEGMENT_MS] cap; the segment roll-over is seamless to the user
 * (a fresh recorder starts immediately). All rollover/naming maths lives in the pure
 * [AudioSegments] helpers so this class only owns the Android recorder lifecycle.
 *
 * Not thread-safe: drive it from a single coroutine (the recording view-model).
 */
class AudioRecorder(
    private val context: Context,
    private val noteDir: File,
    private val maxSegmentMs: Long = AudioSegments.MAX_SEGMENT_MS
) {
    private val accumulator = SegmentAccumulator(maxSegmentMs)
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var segmentStart = 0L
    private var pausedTotal = 0L
    private var pauseMark = 0L

    var isPaused = false
        private set
    var isRecording = false
        private set

    /** Elapsed ms in the segment currently being captured (excludes paused time). */
    fun currentSegmentElapsedMs(): Long {
        if (!isRecording) return 0L
        val paused = pausedTotal + if (isPaused) SystemClock.elapsedRealtime() - pauseMark else 0L
        return SystemClock.elapsedRealtime() - segmentStart - paused
    }

    /** Total ms across finished segments plus the one in progress. */
    fun totalElapsedMs(): Long = accumulator.totalDurationMs + currentSegmentElapsedMs()

    /** Latest peak amplitude (0..32767) for the live waveform. 0 while paused/stopped. */
    fun amplitude(): Int =
        if (isRecording && !isPaused) runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) else 0

    fun start() {
        if (isRecording) return
        if (!noteDir.exists() && !noteDir.mkdirs()) {
            throw IllegalStateException("Could not create recording directory")
        }
        startSegment(accumulator.nextIndex())
        isRecording = true
    }

    /** Roll over to a fresh segment if the cap is reached. Returns true if it rolled. */
    fun maybeRollover(): Boolean {
        if (!isRecording || isPaused) return false
        if (!AudioSegments.shouldRollover(currentSegmentElapsedMs(), maxSegmentMs)) return false
        finalizeSegment()
        try {
            startSegment(accumulator.nextIndex())
        } catch (t: Throwable) {
            isRecording = false
            isPaused = false
            throw t
        }
        return true
    }

    fun pause(): Boolean {
        if (!isRecording || isPaused) return false
        val activeRecorder = recorder ?: return false
        val paused = runCatching { activeRecorder.pause() }.isSuccess
        if (!paused) return false
        pauseMark = SystemClock.elapsedRealtime()
        isPaused = true
        return true
    }

    fun resume(): Boolean {
        if (!isRecording || !isPaused) return false
        val activeRecorder = recorder ?: return false
        val resumed = runCatching { activeRecorder.resume() }.isSuccess
        if (!resumed) return false
        pausedTotal += SystemClock.elapsedRealtime() - pauseMark
        isPaused = false
        return true
    }

    /** Finish recording and return every captured segment (in order). */
    fun stop(): List<RecordedSegment> {
        if (!isRecording) return accumulator.recorded
        finalizeSegment()
        isRecording = false
        isPaused = false
        return accumulator.recorded
    }

    /** Stop and delete every file captured this session (discard). */
    fun discard() {
        runCatching { stop() }
        accumulator.paths().forEach { runCatching { File(it).delete() } }
        // Remove the note dir if we left it empty.
        runCatching { if (noteDir.listFiles()?.isEmpty() == true) noteDir.delete() }
    }

    private fun startSegment(index: Int) {
        val file = AudioStorage.segmentFile(noteDir, index)
        // A session is never resumed into an existing filename. Refusing to overwrite is
        // safer than silently corrupting an attachment after a duplicated START command.
        check(!file.exists()) { "Recording segment already exists: ${file.name}" }
        val rec = buildRecorder()
        try {
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
        } catch (t: Throwable) {
            runCatching { rec.reset() }
            runCatching { rec.release() }
            runCatching { file.delete() }
            throw t
        }
        recorder = rec
        currentFile = file
        segmentStart = SystemClock.elapsedRealtime()
        pausedTotal = 0L
        isPaused = false
    }

    /** Finalise the current segment, retaining it only when MediaRecorder produced a real file. */
    private fun finalizeSegment(): Boolean {
        val duration = currentSegmentElapsedMs()
        val file = currentFile
        val activeRecorder = recorder
        val stoppedCleanly = activeRecorder != null && runCatching { activeRecorder.stop() }.isSuccess
        runCatching { activeRecorder?.release() }
        recorder = null
        currentFile = null
        val valid = stoppedCleanly && file != null && duration > 0L && file.isFile && file.length() > 0L
        if (valid) {
            accumulator.add(file!!.absolutePath, duration)
        } else {
            runCatching { file?.delete() }
        }
        return valid
    }

    @Suppress("DEPRECATION")
    private fun buildRecorder(): MediaRecorder {
        val rec = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        return rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)                 // MONO (PLAN.md §3)
            setAudioSamplingRate(16_000)        // 16 kHz
            setAudioEncodingBitRate(48_000)     // ~48 kbps
        }
    }
}
