package com.fadghost.notesapp.service

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * Small draggable system overlay for an active Ramble capture. It is deliberately owned by the
 * microphone service, not Compose, so it remains useful after the activity has gone away.
 * Tap toggles pause/resume and long-press stops the capture. It is never shown without the
 * user's explicit Android overlay permission.
 */
internal class RambleOverlayController(
    context: Context,
    private val onTogglePause: () -> Unit,
    private val onStop: () -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    fun show(paused: Boolean) {
        if (!Settings.canDrawOverlays(appContext)) return
        val bubble = view ?: createBubble().also { view = it }
        val layout = params ?: defaultParams().also { params = it }
        if (!attached) {
            runCatching {
                windowManager.addView(bubble, layout)
                attached = true
            }.onFailure { attached = false }
        }
        render(paused)
    }

    fun update(paused: Boolean) {
        if (attached) render(paused)
    }

    fun dismiss() {
        val bubble = view ?: return
        if (attached) {
            runCatching { windowManager.removeViewImmediate(bubble) }
            attached = false
        }
    }

    private fun createBubble(): TextView = TextView(appContext).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        minWidth = dp(76)
        minHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        contentDescription = "Ramble controls. Tap to pause or resume. Long press to finish recording."
        isClickable = true
        isLongClickable = true
        setOnClickListener { onTogglePause() }
        setOnLongClickListener {
            onStop()
            true
        }
        setOnTouchListener(DragListener())
    }

    private fun render(paused: Boolean) {
        val bubble = view ?: return
        bubble.text = if (paused) "RESUME" else "REC"
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(if (paused) Color.rgb(83, 94, 105) else Color.rgb(185, 77, 62))
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }
    }

    private fun defaultParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        dp(48),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (appContext.resources.displayMetrics.widthPixels - dp(100)).coerceAtLeast(dp(12))
        y = dp(180)
    }

    private inner class DragListener : View.OnTouchListener {
        private var rawX = 0f
        private var rawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rawX = event.rawX
                rawY = event.rawY
                startX = params?.x ?: 0
                startY = params?.y ?: 0
                dragging = false
                false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - rawX).toInt()
                val dy = (event.rawY - rawY).toInt()
                if (!dragging && abs(dx) + abs(dy) > dp(8)) dragging = true
                if (dragging) {
                    params?.let { layout ->
                        layout.x = (startX + dx).coerceAtLeast(0)
                        layout.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(v, layout) }
                    }
                    true
                } else false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging
            else -> false
        }
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()
}
