package com.fadghost.notesapp.ui.components

import kotlin.math.hypot

/**
 * Small, platform-independent tap/drag classifier used by floating controls.
 * Once movement crosses [touchSlopPx] the gesture is permanently a drag, so an ACTION_UP
 * after repositioning can never leak through as a click.
 */
class TouchSlopDragClassifier(private val touchSlopPx: Float) {
    private var distanceX = 0f
    private var distanceY = 0f

    var isDragging: Boolean = false
        private set

    fun moveBy(dx: Float, dy: Float): Boolean {
        distanceX += dx
        distanceY += dy
        if (!isDragging && hypot(distanceX, distanceY) > touchSlopPx) isDragging = true
        return isDragging
    }

    fun finish(): GestureResult = if (isDragging) GestureResult.DRAG else GestureResult.TAP
}

enum class GestureResult { TAP, DRAG }
