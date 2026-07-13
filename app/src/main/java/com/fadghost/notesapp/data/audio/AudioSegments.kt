package com.fadghost.notesapp.data.audio

import kotlinx.serialization.Serializable

/**
 * Pure segment-split bookkeeping for voice recording (PLAN.md §3: "clip cap ~5 min
 * per segment with auto-chunking"). Kept free of Android/MediaRecorder types so the
 * roll-over maths and file naming can be exercised in plain JVM unit tests. The
 * Android [AudioRecorder] drives the actual recorder; this only decides *when* to
 * start a fresh segment and *what to call it*.
 */
object AudioSegments {

    /** Hard cap per segment (PLAN.md §3 — ~5 min, keeps each upload well under the STT ceiling). */
    const val MAX_SEGMENT_MS: Long = 5L * 60 * 1000

    /** Zero-padded, sortable file name for the [index]-th segment (0-based). */
    fun fileName(index: Int): String = "segment_%03d.m4a".format(index)

    /** True once the current segment has reached the cap and should roll over. */
    fun shouldRollover(currentSegmentElapsedMs: Long, maxSegmentMs: Long = MAX_SEGMENT_MS): Boolean =
        currentSegmentElapsedMs >= maxSegmentMs

    /**
     * Split a total recording duration into per-segment durations of at most
     * [maxSegmentMs]. e.g. 11 min @ 5 min cap -> [5m, 5m, 1m]. A zero/negative total
     * yields an empty list.
     */
    fun splitDurations(totalMs: Long, maxSegmentMs: Long = MAX_SEGMENT_MS): List<Long> {
        if (totalMs <= 0 || maxSegmentMs <= 0) return emptyList()
        val out = ArrayList<Long>()
        var remaining = totalMs
        while (remaining > 0) {
            val take = minOf(remaining, maxSegmentMs)
            out += take
            remaining -= take
        }
        return out
    }

    /** Number of segments a total recording of [totalMs] would occupy. */
    fun segmentCount(totalMs: Long, maxSegmentMs: Long = MAX_SEGMENT_MS): Int =
        splitDurations(totalMs, maxSegmentMs).size
}

/** One captured audio segment: its file path and measured duration. */
@Serializable
data class RecordedSegment(val path: String, val durationMs: Long)

/**
 * Accumulates finished [RecordedSegment]s during a recording session and hands out
 * the next segment index. Pure/testable — no file or recorder I/O.
 */
class SegmentAccumulator(private val maxSegmentMs: Long = AudioSegments.MAX_SEGMENT_MS) {
    private val segments = ArrayList<RecordedSegment>()

    val recorded: List<RecordedSegment> get() = segments.toList()
    val count: Int get() = segments.size
    val totalDurationMs: Long get() = segments.sumOf { it.durationMs }

    /** Index to use for the next segment file (0-based). */
    fun nextIndex(): Int = segments.size

    fun add(path: String, durationMs: Long) {
        segments += RecordedSegment(path, durationMs)
    }

    fun paths(): List<String> = segments.map { it.path }
}
