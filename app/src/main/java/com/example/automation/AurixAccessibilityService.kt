package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AurixAccessibility"

data class VisibleScreenSnapshot(
    val packageName: String = "",
    val summaryText: String = "",
    val extractedPosts: List<String> = emptyList(),
    val clickableLabels: List<String> = emptyList(),
    val editableHints: List<String> = emptyList(),
    val capturedAt: Long = System.currentTimeMillis()
)

class AurixAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.i(TAG, "AURIX AccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != packageName) {
            _activeForegroundPackage.value = pkg
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AURIX AccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        Log.i(TAG, "AURIX AccessibilityService destroyed.")
    }

    fun captureVisibleScreen(): VisibleScreenSnapshot {
        val root = rootInActiveWindow ?: return VisibleScreenSnapshot(
            packageName = _activeForegroundPackage.value,
            summaryText = "No active window content is accessible right now."
        )
        return try {
            val pkg = root.packageName?.toString().orEmpty()
            val textLines = LinkedHashSet<String>()
            val clickables = LinkedHashSet<String>()
            val editHints = LinkedHashSet<String>()

            traverseNode(root, textLines, clickables, editHints, depth = 0)

            val posts = textLines
                .filter { it.length >= 16 }
                .take(15)

            val summary = if (textLines.isEmpty()) {
                "Screen has no readable text elements."
            } else {
                textLines.take(35).joinToString("\n")
            }

            VisibleScreenSnapshot(
                packageName = pkg,
                summaryText = summary,
                extractedPosts = posts,
                clickableLabels = clickables.take(20),
                editableHints = editHints.take(10),
                capturedAt = System.currentTimeMillis()
            ).also { _lastSnapshot.value = it }
        } finally {
            root.recycle()
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        texts: MutableSet<String>,
        clickables: MutableSet<String>,
        editHints: MutableSet<String>,
        depth: Int
    ) {
        if (depth > 28 || !node.isVisibleToUser) return

        val nodeText = node.text?.toString()?.trim().orEmpty()
        val nodeDesc = node.contentDescription?.toString()?.trim().orEmpty()
        val hintText = node.hintText?.toString()?.trim().orEmpty()

        if (nodeText.isNotEmpty()) texts.add(nodeText)
        if (nodeDesc.isNotEmpty() && nodeDesc != nodeText) texts.add(nodeDesc)

        if (node.isClickable) {
            val label = nodeDesc.ifEmpty { nodeText }
            if (label.isNotEmpty()) clickables.add(label.take(60))
        }

        if (node.isEditable) {
            val hint = hintText.ifEmpty { nodeDesc.ifEmpty { nodeText.ifEmpty { "Input field" } } }
            editHints.add(hint.take(60))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseNode(child, texts, clickables, editHints, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    fun scrollScreen(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val scrollable = findFirstScrollable(root)
            if (scrollable != null) {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val result = scrollable.performAction(action)
                scrollable.recycle()
                if (result) return true
            }
            // Fallback: dispatch natural vertical swipe gesture
            performVerticalSwipe(forward)
        } finally {
            root.recycle()
        }
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 20 || !node.isVisibleToUser) return null
        if (node.isScrollable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = findFirstScrollable(child, depth + 1)
                if (found != null) return found
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun performVerticalSwipe(scrollDown: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = if (scrollDown) metrics.heightPixels * 0.72f else metrics.heightPixels * 0.30f
        val endY = if (scrollDown) metrics.heightPixels * 0.28f else metrics.heightPixels * 0.72f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeTextIntoEditable(text: String, preferredHint: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val editNodes = mutableListOf<AccessibilityNodeInfo>()
            collectEditableNodes(root, editNodes, 0)

            val targetNode = when {
                editNodes.isEmpty() -> null
                !preferredHint.isNullOrBlank() -> editNodes.firstOrNull { node ->
                    val hint = node.hintText?.toString().orEmpty()
                    val desc = node.contentDescription?.toString().orEmpty()
                    val existing = node.text?.toString().orEmpty()
                    hint.contains(preferredHint, ignoreCase = true) ||
                        desc.contains(preferredHint, ignoreCase = true) ||
                        existing.contains(preferredHint, ignoreCase = true)
                } ?: editNodes.firstOrNull { it.isFocused } ?: editNodes.first()
                else -> editNodes.firstOrNull { it.isFocused } ?: editNodes.first()
            }

            val success = if (targetNode != null) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else {
                false
            }

            editNodes.forEach { it.recycle() }
            success
        } finally {
            root.recycle()
        }
    }

    private fun collectEditableNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 25 || !node.isVisibleToUser) return
        if (node.isEditable) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectEditableNodes(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    fun clickNodeByLabel(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetLower = label.trim().lowercase()
        return try {
            val match = findNodeMatchingText(root, targetLower, 0) ?: return false
            try {
                var current: AccessibilityNodeInfo? = match
                while (current != null) {
                    if (current.isClickable) {
                        return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    val parent = current.parent
                    if (current !== match) current.recycle()
                    current = parent
                }
                // Fallback tap center bounds
                val rect = Rect()
                match.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    tapPoint(rect.exactCenterX(), rect.exactCenterY())
                } else {
                    false
                }
            } finally {
                match.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findNodeMatchingText(
        node: AccessibilityNodeInfo,
        targetLower: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 25 || !node.isVisibleToUser) return null
        val t = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val d = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (t == targetLower || d == targetLower || t.contains(targetLower) || d.contains(targetLower)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = findNodeMatchingText(child, targetLower, depth + 1)
                if (found != null) return found
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun tapPoint(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        var instance: AurixAccessibilityService? = null
            private set

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _activeForegroundPackage = MutableStateFlow("")
        val activeForegroundPackage: StateFlow<String> = _activeForegroundPackage.asStateFlow()

        private val _lastSnapshot = MutableStateFlow<VisibleScreenSnapshot?>(null)
        val lastSnapshot: StateFlow<VisibleScreenSnapshot?> = _lastSnapshot.asStateFlow()
    }
}
