package com.rhf.handtrack

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class ControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: ControlService? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private var cursor: View? = null
    private var cursorLp: WindowManager.LayoutParams? = null
    private var dot: GradientDrawable? = null
    private var size = 0
    private var lastPinched = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        hideCursor()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun run(path: Path, ms: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun tap(x: Float, y: Float) {
        run(Path().apply { moveTo(x, y) }, 70)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        run(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 220)
    }

    fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun showCursor(x: Float, y: Float, pinched: Boolean) {
        ui.post {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                if (cursor == null) {
                    size = (34 * resources.displayMetrics.density).toInt()
                    val d = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xCCFF3B30.toInt())
                        setStroke(4, 0xFFFFFFFF.toInt())
                    }
                    val v = View(this).apply { background = d }
                    val params = WindowManager.LayoutParams(
                        size, size,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    )
                    params.gravity = Gravity.TOP or Gravity.START
                    dot = d
                    cursor = v
                    cursorLp = params
                    wm.addView(v, params)
                }
                val lp = cursorLp!!
                lp.x = (x - size / 2f).toInt()
                lp.y = (y - size / 2f).toInt()
                wm.updateViewLayout(cursor, lp)
                if (pinched != lastPinched) {
                    lastPinched = pinched
                    dot?.setColor(if (pinched) 0xCC34C759.toInt() else 0xCCFF3B30.toInt())
                }
            } catch (e: Exception) {
            }
        }
    }

    fun hideCursor() {
        ui.post {
            try {
                cursor?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
            } catch (e: Exception) {
            }
            cursor = null
            cursorLp = null
            dot = null
            lastPinched = false
        }
    }
}
