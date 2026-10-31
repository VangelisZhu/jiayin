package com.eddyizm.tempus.jiayin

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PublicOnboardingTest {
    @Test(timeout = 90000) fun freshConfigurationIsBlankAndOnlineRepairRequiresOptIn() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val repo = JiayinRepository.get(context)
        val keys = listOf("account", "server", "user", "auto-repair-lyrics", "server-profiles-v1")
        val saved = repo.settings.all.filterKeys { it in keys }
        repo.settings.edit().apply { keys.forEach { remove(it) } }.commit()
        try {
            assertFalse(repo.configured); assertEquals("", repo.server); assertEquals("", repo.user)
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            try {
                SystemClock.sleep(1500)
                var activeRoot = instrumentation.uiAutomation.rootInActiveWindow
                repeat(40) {
                    if (activeRoot?.packageName?.toString() != context.packageName) {
                        SystemClock.sleep(125); activeRoot = instrumentation.uiAutomation.rootInActiveWindow
                    }
                }
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(checkNotNull(activeRoot) { "Onboarding window not ready" })
                while (pending.isNotEmpty()) {
                    val node = pending.removeFirst(); nodes.add(node)
                    for (i in 0 until node.childCount) node.getChild(i)?.let { pending.add(it) }
                }
                val fields = nodes.filter { it.className?.toString() == "android.widget.EditText" }
                assertEquals("Visible onboarding nodes: " + nodes.mapNotNull { it.text?.toString() }.joinToString(" | "), 4, fields.size)
                assertTrue(fields.all { it.text.isNullOrBlank() })
                repeat(3) { if (EditorTouch.nodes().none { it.isCheckable }) { EditorTouch.nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); SystemClock.sleep(300) } }
                val checkboxes = EditorTouch.nodes().filter { it.isCheckable }
                assertEquals(1, checkboxes.size); assertFalse(checkboxes.single().isChecked)
                // Empty submit is a local field error in the same editor; no connection is created.
                EditorTouch.click("保存并切换")
                EditorTouch.node("请填写完整地址，例如 https://music.example.com")
                assertFalse(repo.configured); assertTrue(repo.profiles.all().isEmpty())
                val folder = File(context.getExternalFilesDir(null), "public-onboarding").apply { mkdirs() }
                val image = instrumentation.uiAutomation.takeScreenshot()
                File(folder, "onboarding.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            } finally { scenario.close() }
        } finally {
            repo.settings.edit().apply {
                keys.forEach { remove(it) }
                saved.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value) } }
            }.commit()
        }
    }
}
