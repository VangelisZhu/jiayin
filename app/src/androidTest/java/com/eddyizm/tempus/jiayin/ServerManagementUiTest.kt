@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.JiayinMediaService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServerManagementUiTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        ins.uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        ins.uiAutomation.rootInActiveWindow?.let { queue.add(it) }
        while (queue.isNotEmpty()) { val n = queue.removeFirst(); result += n; for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) } }
        return result
    }
    private fun click(label: String) = EditorTouch.click(label)
    private fun shot(name: String) {
        val folder = File(context.getExternalFilesDir(null), "server-ui").apply { mkdirs() }
        val image = ins.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test(timeout = 90000) fun settingsEntryManagementDialogsAndSwitchResetEntireNavigation() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val repo = JiayinRepository.get(context)
        MediaPipelineTest.MediaServer(catalog = true).use { a -> MediaPipelineTest.MediaServer().use { b ->
            repo.login("http://localhost:${a.port}", "fixture", "fixture-password")
            val first = repo.profiles.active()!!.profile.copy(name = "家里的音乐")
            repo.profiles.upsert(first)
            val second = repo.saveProfile("http://localhost:${b.port}", "fixture", "fixture-password", "共享音乐空间")
            val priorProfiles = repo.settings.getString("server-profiles-v1", null)
            repo.settings.edit().putString("server-profiles-v1", repo.gson.toJson(listOf(first, second))).commit()
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                withTimeout(15000) { while (vm.state.value.loading) delay(50) }
                delay(600); click("我的"); click("设置")
                assertTrue(nodes().any { it.text?.toString() == "音乐服务器 · 切换与退出" })
                shot("01-settings")
                click("音乐服务器 · 切换与退出")
                withTimeout(10000) { while (nodes().none { it.text?.toString() == "当前使用" }) delay(100) }
                assertTrue(nodes().mapNotNull { it.text }.joinToString(" | "), nodes().any { it.text?.toString() == "当前使用" })
                assertTrue(nodes().any { it.text?.toString() == "共享音乐空间" })
                ins.runOnMainSync { vm.consumedMessage() }; delay(500)
                shot("02-servers")
                click("家里的音乐 更多操作"); click("退出登录")
                assertTrue(nodes().any { it.text?.contains("重新登录后恢复") == true })
                shot("03-logout"); click("取消")
                click("家里的音乐 更多操作"); click("移除账户及本地数据")
                assertTrue(nodes().any { it.text?.contains("不删除 NAS 音乐") == true })
                shot("04-remove"); click("取消")
                click("添加服务器")
                withTimeout(10000) { while (nodes().count { it.className?.toString() == "android.widget.EditText" } != 4) delay(100) }
                assertEquals(4, nodes().count { it.className?.toString() == "android.widget.EditText" })
                assertTrue(nodes().any { it.text?.toString() == "保存" })
                shot("05-add"); click("取消")
                click("切换")
                withTimeout(20000) { while (vm.servers.value.busy || vm.state.value.loading) delay(50) }
                assertEquals(second.id, vm.repo.account)
                delay(500)
                EditorTouch.node("音乐在家，随时随地听")
                assertFalse(nodes().any { it.text?.toString() == "当前使用" })
                assertFalse(vm.search.value.active)
                shot("06-switched-home")
            } finally {
                scenario.close(); JiayinMediaService.stopForSwitch(context)
                repo.settings.edit().putString("server-profiles-v1", priorProfiles).commit()
            }
        } }
    }
}
