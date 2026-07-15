package com.fadghost.notesapp

import com.fadghost.notesapp.ui.components.GestureResult
import com.fadghost.notesapp.ui.components.TouchSlopDragClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSlopDragClassifierTest {
    @Test fun `small movement remains a tap`() {
        val classifier = TouchSlopDragClassifier(10f)
        assertFalse(classifier.moveBy(3f, 4f))
        assertEquals(GestureResult.TAP, classifier.finish())
    }

    @Test fun `cumulative movement becomes drag and never becomes tap again`() {
        val classifier = TouchSlopDragClassifier(10f)
        assertFalse(classifier.moveBy(6f, 0f))
        assertTrue(classifier.moveBy(6f, 0f))
        classifier.moveBy(-12f, 0f)
        assertEquals(GestureResult.DRAG, classifier.finish())
    }

    @Test fun `diagonal movement uses radial touch slop`() {
        val classifier = TouchSlopDragClassifier(10f)
        assertTrue(classifier.moveBy(8f, 8f))
    }
}
