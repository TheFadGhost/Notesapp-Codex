package com.fadghost.notesapp.ui.attach

import kotlin.math.hypot

/** Pure token move used after a deliberate long-press drag. */
object AttachmentTokenMove {
    fun passedTouchSlop(dx: Float, dy: Float, touchSlop: Float): Boolean =
        hypot(dx.toDouble(), dy.toDouble()) >= touchSlop

    fun move(source: String, attachmentId: Long, targetOffset: Int): String {
        val token = "[[att:$attachmentId]]"
        val start = source.indexOf(token)
        if (start < 0) return source
        var removeStart = start
        var removeEnd = start + token.length
        if (removeEnd < source.length && source[removeEnd] == ' ') removeEnd++
        else if (removeStart > 0 && source[removeStart - 1] == ' ') removeStart--
        val without = source.removeRange(removeStart, removeEnd)
        val adjusted = (targetOffset - if (targetOffset > removeStart) removeEnd - removeStart else 0)
            .coerceIn(0, without.length)
        val lead = adjusted > 0 && !without[adjusted - 1].isWhitespace()
        val trail = adjusted < without.length && !without[adjusted].isWhitespace()
        val insertion = (if (lead) " " else "") + token + (if (trail) " " else "")
        return without.substring(0, adjusted) + insertion + without.substring(adjusted)
    }
}
