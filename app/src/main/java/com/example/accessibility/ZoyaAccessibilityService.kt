package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ZoyaAccessibilityService : AccessibilityService() {

    companion object {
        var shouldAutoClick = false
            set(value) {
                field = value
                if (value) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        field = false
                    }, 10000) // Reset after 10 seconds to give WhatsApp time to load
                }
            }
        var targetAppName = "whatsapp"
        var instance: ZoyaAccessibilityService? = null

        fun dispatchGestureClick(x: Float, y: Float): Boolean {
            val inst = instance ?: return false
            val path = android.graphics.Path()
            path.moveTo(x, y)
            path.lineTo(x, y)

            val builder = android.accessibilityservice.GestureDescription.Builder()
            builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
            val gesture = builder.build()

            return inst.dispatchGesture(gesture, null, null)
        }

        fun clickTextOnScreen(text: String): Boolean {
            val inst = instance ?: return false
            val root = inst.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                var current: AccessibilityNodeInfo? = node
                while(current != null) {
                    val bounds = android.graphics.Rect()
                    current.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && (current.isClickable || current == node)) {
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        if (dispatchGestureClick(x, y)) return true
                    }
                    current = current.parent
                }
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("ZoyaAccessibility", "Accessibility Service Connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !shouldAutoClick) return
        
        val packageName = event.packageName?.toString() ?: ""
        if (packageName.contains("whatsapp")) {
            
            val rootNode = rootInActiveWindow ?: return
            
            val clicked = searchAndClickSendButton(rootNode)
            if (clicked) {
                Log.d("ZoyaAccessibility", "Successfully clicked send button!")
                shouldAutoClick = false
            }
        }
    }

    private fun searchAndClickSendButton(node: AccessibilityNodeInfo): Boolean {
        // Attempt 1: by common View IDs
        val idsToTry = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send"
        )
        for (id in idsToTry) {
            val sendButtons = node.findAccessibilityNodeInfosByViewId(id)
            if (sendButtons.isNotEmpty()) {
                for (button in sendButtons) {
                    if (performClick(button)) {
                        Log.d("ZoyaAccessibility", "Clicked send button by ID: $id")
                        return true
                    }
                }
            }
        }

        // Attempt 2: Recursive search for Content Description "Send", "Bhejen", etc
        return recursiveSearchAndClick(node)
    }

    private fun recursiveSearchAndClick(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        
        if (desc == "send" || desc == "bheje" || desc == "bhejen" || desc == "envio") {
            if (performClick(node)) {
                Log.d("ZoyaAccessibility", "Clicked send button by content description!")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (recursiveSearchAndClick(child)) {
                    return true
                }
            }
        }
        return false
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        // Real visual click first
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            if (dispatchGestureClick(x, y)) {
                return true
            }
        }

        if (node.isClickable) {
            val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (success) return true
        }
        // Try parent if not clickable
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
            parent = parent.parent
        }
        return false
    }

    override fun onInterrupt() {
        Log.d("ZoyaAccessibility", "Accessibility Service Interrupted")
    }
}
