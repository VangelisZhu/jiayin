package com.eddyizm.tempus.jiayin

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal object EditorTouch {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    fun nodes(): List<AccessibilityNodeInfo> {
        ins.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>(); val queue = ArrayDeque<AccessibilityNodeInfo>()
        ins.uiAutomation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) { val n = queue.removeFirst(); result += n; repeat(n.childCount) { n.getChild(it)?.let(queue::add) } }
        return result
    }
    fun node(label: String): AccessibilityNodeInfo {
        repeat(40) { nodes().firstOrNull { (it.text?.toString() == label || it.contentDescription?.toString() == label) && it.isVisibleToUser }?.let { return it }; SystemClock.sleep(100) }
        error("Missing $label; nodes=${nodes().mapNotNull { it.text ?: it.contentDescription }}")
    }
    fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)
    fun tap(n: AccessibilityNodeInfo) {
        val r = bounds(n); val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, r.exactCenterX(), r.exactCenterY(), 0)
            ins.uiAutomation.injectInputEvent(event, true); event.recycle(); SystemClock.sleep(50)
        }
        SystemClock.sleep(300)
    }
    fun click(label: String) {
        var target = node(label)
        repeat(3) { SystemClock.sleep(100); target = node(label) }
        tap(target)
    }
    fun shot(name: String) {
        val folder = File(ins.targetContext.getExternalFilesDir(null), "editor-ui").apply { mkdirs() }
        ins.uiAutomation.takeScreenshot().let { image -> File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle() }
    }
}
