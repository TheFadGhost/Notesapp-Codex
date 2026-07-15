package com.fadghost.notesapp.ui.overlay

import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Binder
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fadghost.notesapp.MainActivity
import com.fadghost.notesapp.data.audio.VoiceRecordingSession
import com.fadghost.notesapp.data.audio.VoiceSessionState
import com.fadghost.notesapp.ui.components.GestureResult
import com.fadghost.notesapp.ui.components.TouchSlopDragClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Small system recording control owned by a service, not by an Activity window.
 * Dragging crosses Android's configured touch slop before movement begins; after that
 * threshold ACTION_UP is permanently classified as drag and cannot expand the panel.
 */
class RecordingOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var root: LinearLayout? = null
    private var bubble: FrameLayout? = null
    private var panel: LinearLayout? = null
    private var pauseButton: TextView? = null
    private var timerLabel: TextView? = null
    private var sessionId: String? = null
    private var panelOpen = false
    private var windowAttached = false
    private var attachmentFailed = false
    private val binder = Binder()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        scope.launch {
            VoiceRecordingSession.state.collectLatest(::renderState)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> removeOverlay(stop = true)
            ACTION_SHOW -> {
                attachmentFailed = false
                if (!Settings.canDrawOverlays(this)) stopSelf()
                else if (VoiceRecordingSession.state.value.isOverlayActive()) ensureOverlay()
            }
        }
        return START_NOT_STICKY
    }

    private fun renderState(state: VoiceSessionState) {
        when (state) {
            is VoiceSessionState.Starting -> {
                sessionId = state.sessionId
                if (Settings.canDrawOverlays(this)) ensureOverlay()
                else {
                    attachmentFailed = true
                    removeOverlay(stop = true)
                    return
                }
                pauseButton?.apply {
                    text = "Starting"
                    isEnabled = false
                    alpha = 0.55f
                }
                timerLabel?.text = "0:00"
            }
            is VoiceSessionState.Recording -> {
                sessionId = state.sessionId
                if (Settings.canDrawOverlays(this)) ensureOverlay()
                else {
                    attachmentFailed = true
                    removeOverlay(stop = true)
                    return
                }
                pauseButton?.apply {
                    text = if (state.paused) "Resume" else "Pause"
                    isEnabled = true
                    alpha = 1f
                }
                timerLabel?.text = formatElapsed(state.elapsedMs)
            }
            else -> removeOverlay(stop = true)
        }
    }

    private fun ensureOverlay() {
        if (root != null || attachmentFailed) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val metrics = windowManager.currentWindowMetrics
        val safeInsets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(
                KEY_X,
                (metrics.bounds.width() - safeInsets.right - dp(72)).coerceAtLeast(safeInsets.left)
            )
            y = prefs.getInt(KEY_Y, safeInsets.top + dp(96))
        }

        val controls = buildPanel().also { panel = it }
        val controlBubble = buildBubble().also { bubble = it }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(controls)
            addView(controlBubble)
        }
        clampPosition(dp(56), dp(56))
        root = container
        if (!safeAddView(container)) {
            attachmentFailed = true
            resetOverlayReferences(stop = true)
        }
    }

    private fun buildBubble(): FrameLayout {
        val size = dp(56)
        val dot = TextView(this).apply {
            text = "●"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Voice recording controls. Drag to reposition."
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            background = rounded(Color.rgb(112, 68, 255), dp(18).toFloat(), Color.argb(90, 255, 255, 255))
            setOnClickListener { togglePanel() }
        }
        return FrameLayout(this).apply {
            addView(dot, FrameLayout.LayoutParams(size, size))
            installDragTouch(dot)
        }
    }

    private fun buildPanel(): LinearLayout {
        timerLabel = TextView(this).apply {
            text = "0:00"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        pauseButton = control("Pause") {
            sessionId?.let { VoiceRecordingSession.togglePause(this, it) }
        }
        val primaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(pauseButton)
            addView(control("Stop") { sessionId?.let { VoiceRecordingSession.stop(this@RecordingOverlayService, it) } })
        }
        val secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(control("Discard", danger = true) { sessionId?.let { VoiceRecordingSession.discard(this@RecordingOverlayService, it) } })
            addView(control("Open") { openApp() })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            minimumWidth = dp(120)
            background = rounded(Color.argb(242, 31, 29, 35), dp(18).toFloat(), Color.argb(70, 255, 255, 255))
            addView(timerLabel)
            addView(primaryRow)
            addView(secondaryRow)
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
    }

    private fun control(label: String, danger: Boolean = false, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (danger) Color.rgb(255, 130, 130) else Color.WHITE)
            minWidth = dp(52)
            minHeight = dp(48)
            contentDescription = label
            setOnClickListener { action() }
        }

    private fun installDragTouch(target: View) {
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        var classifier = TouchSlopDragClassifier(touchSlop)
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var lastRawX = 0f
        var lastRawY = 0f
        target.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    classifier = TouchSlopDragClassifier(touchSlop)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = downRawX
                    lastRawY = downRawY
                    startX = params.x
                    startY = params.y
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dragging = classifier.moveBy(event.rawX - lastRawX, event.rawY - lastRawY)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    if (dragging) {
                        params.x = startX + (event.rawX - downRawX).roundToInt()
                        params.y = startY + (event.rawY - downRawY).roundToInt()
                        clampPosition(root?.width ?: dp(56), root?.height ?: dp(56))
                        root?.let(::safeUpdateView)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (classifier.finish() == GestureResult.TAP) view.performClick() else persistPosition()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    if (classifier.isDragging) persistPosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePanel() {
        val controls = panel ?: return
        val container = root ?: return
        if (panelOpen) {
            val panelWidth = controls.width
            controls.visibility = View.GONE
            params.x += panelWidth
            panelOpen = false
            container.post { updateClampedLayout() }
        } else {
            controls.visibility = View.VISIBLE
            panelOpen = true
            controls.post {
                // Panel is before the bubble, so compensate its width to keep the bubble
                // under the user's finger when controls expand.
                params.x -= controls.width
                updateClampedLayout()
            }
        }
    }

    private fun updateClampedLayout() {
        val container = root ?: return
        clampPosition(container.width.coerceAtLeast(dp(56)), container.height.coerceAtLeast(dp(56)))
        if (safeUpdateView(container)) persistPosition()
    }

    private fun clampPosition(viewWidth: Int, viewHeight: Int) {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val minX = insets.left
        val minY = insets.top
        val maxX = (bounds.width() - insets.right - viewWidth).coerceAtLeast(minX)
        val maxY = (bounds.height() - insets.bottom - viewHeight).coerceAtLeast(minY)
        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(minY, maxY)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation, folding and multi-window changes can all alter both bounds and safe
        // insets. Re-measure the expanded/collapsed surface and clamp it immediately.
        attachmentFailed = false
        root?.post { updateClampedLayout() }
    }

    private fun persistPosition() {
        // Persist the bubble coordinate, not the expanded window's left edge, so the next
        // recording reopens the compact control where the user actually left it.
        val bubbleX = params.x + if (panelOpen) (panel?.width ?: 0) else 0
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_X, bubbleX)
            .putInt(KEY_Y, params.y)
            .apply()
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun removeOverlay(stop: Boolean) {
        val attachedRoot = root
        // Clear first: WindowManager callbacks or a concurrent state emission cannot try
        // to update a view that is being detached.
        root = null
        if (attachedRoot != null && windowAttached) {
            try {
                windowManager.removeView(attachedRoot)
            } catch (_: WindowManager.BadTokenException) {
                // Permission/token may have been revoked between state and detach.
            } catch (_: IllegalArgumentException) {
                // View was already detached by WindowManager.
            } catch (_: SecurityException) {
                // Overlay permission was revoked; recorder lifetime is independent.
            }
        }
        windowAttached = false
        clearViewReferences()
        if (stop) stopSelf()
    }

    private fun safeAddView(view: View): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        return try {
            windowManager.addView(view, params)
            windowAttached = true
            true
        } catch (_: WindowManager.BadTokenException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun safeUpdateView(view: View): Boolean {
        if (!windowAttached || root !== view || !Settings.canDrawOverlays(this)) {
            resetOverlayReferences(stop = true)
            return false
        }
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (_: WindowManager.BadTokenException) {
            attachmentFailed = true
            resetOverlayReferences(stop = true)
            false
        } catch (_: IllegalArgumentException) {
            attachmentFailed = true
            resetOverlayReferences(stop = true)
            false
        } catch (_: SecurityException) {
            attachmentFailed = true
            resetOverlayReferences(stop = true)
            false
        }
    }

    private fun resetOverlayReferences(stop: Boolean) {
        val attachedRoot = root
        root = null
        if (attachedRoot != null && windowAttached) {
            try {
                windowManager.removeView(attachedRoot)
            } catch (_: WindowManager.BadTokenException) {
            } catch (_: IllegalArgumentException) {
            } catch (_: SecurityException) {
            }
        }
        windowAttached = false
        clearViewReferences()
        if (stop) stopSelf()
    }

    private fun clearViewReferences() {
        bubble = null
        panel = null
        pauseButton = null
        timerLabel = null
        panelOpen = false
        sessionId = null
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun formatElapsed(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    override fun onDestroy() {
        removeOverlay(stop = false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SHOW = "com.fadghost.notesapp.overlay.SHOW"
        const val ACTION_HIDE = "com.fadghost.notesapp.overlay.HIDE"
        private const val PREFS = "recording_overlay"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}

private fun VoiceSessionState.isOverlayActive(): Boolean =
    this is VoiceSessionState.Starting || this is VoiceSessionState.Recording
