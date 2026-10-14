package com.rhf.handtrack

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class ControlService : AccessibilityService() {
    companion object {
        @Volatile var instance: ControlService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
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
}
