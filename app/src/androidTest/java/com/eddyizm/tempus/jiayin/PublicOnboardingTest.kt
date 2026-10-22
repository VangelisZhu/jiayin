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
    @Test(timeout = 30000) fun freshConfigurationIsBlankAndOnlineRepairRequiresOptIn() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repo = JiayinRepository.get(context)
        val keys = listOf("account", "server", "user", "auto-repair-lyrics")
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
                assertEquals(3, fields.size)
                assertTrue(fields.all { it.text.isNullOrBlank() })
                val checkboxes = nodes.filter { it.isCheckable }
                assertEquals(1, checkboxes.size); assertFalse(checkboxes.single().isChecked)
                val connect = nodes.first { it.text?.toString() == "连接我的音乐" }
                var ancestor: AccessibilityNodeInfo? = connect
                var disabled = !connect.isEnabled
                repeat(4) { ancestor = ancestor?.parent; disabled = disabled || ancestor?.isEnabled == false }
                assertTrue("Blank credentials must not trigger a request", disabled)
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
