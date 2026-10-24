@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run this same interaction test at each external wm size/density/font configuration.
 * Uses native touch events and a loopback generated catalog, never a personal server. */
@RunWith(AndroidJUnit4::class)
class AdaptiveUiTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>(); val queue = ArrayDeque<AccessibilityNodeInfo>()
        ins.uiAutomation.rootInActiveWindow?.let { queue.add(it) }
        while (queue.isNotEmpty()) { val n = queue.removeFirst(); n.refresh(); result += n; for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) } }
        return result
    }
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
    private fun find(label: String) = nodes().lastOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) && bounds(it).height() > 0 }
    private fun scrollTo(label: String): AccessibilityNodeInfo {
        repeat(32) { attempt ->
            find(label)?.let { n ->
                n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                SystemClock.sleep(300)
                find(label)?.let { return it }
            }
            val scrollers = nodes().filter { it.isScrollable && it.isVisibleToUser }
            val direction = if (attempt in 8..23) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val moved = scrollers.map { it.performAction(direction) }.any { it }
            SystemClock.sleep(if (moved) 300 else 150)
        }
        error("Unreachable control: $label; visible=" + nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "))
    }
    private fun click(label: String) {
        val r = bounds(scrollTo(label)); android.util.Log.i("AdaptiveUiTest", "tap $label $r"); val screen = bounds(ins.uiAutomation.rootInActiveWindow)
        assertTrue("$label outside screen: $r / $screen", screen.contains(r))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("input tap ${r.centerX()} ${r.centerY()}")).use { it.readBytes() }
        SystemClock.sleep(900)
        ins.waitForIdleSync()
    }
    private fun back() { ins.uiAutomation.executeShellCommand("input keyevent 4").close(); SystemClock.sleep(450) }
    private fun fingerScrollTo(label: String) {
        repeat(8) {
            val scrollers = nodes().filter { it.isScrollable && it.isVisibleToUser }
            val target = find(label)
            if (target != null && (scrollers.any { bounds(it).contains(bounds(target)) } ||
                scrollers.isEmpty() && bounds(ins.uiAutomation.rootInActiveWindow).contains(bounds(target)))) return
            val scroll = scrollers.maxByOrNull { bounds(it).width() * bounds(it).height() }
                ?: error("No touch-scroll container for $label")
            val r = bounds(scroll)
            // Stay inside the scroll viewport and outside the seek bar / action buttons.
            val x = r.right - 8
            val start = r.bottom - (r.height() * .15f).toInt()
            val end = r.top + (r.height() * .15f).toInt()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("input swipe $x $start $x $end 400")).use { it.readBytes() }
            SystemClock.sleep(400)
        }
        error("Real finger scrolling could not reach $label")
    }
    private fun shot(name: String) {
        ins.waitForIdleSync(); SystemClock.sleep(300)
        val folder = File(context.getExternalFilesDir(null), "adaptive-ui").apply { mkdirs() }
        val image = ins.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test(timeout = 180000) fun controlsRemainReachableWithoutChangingQueueOrDownloading() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        MediaPipelineTest.MediaServer(catalog = true).use { server ->
            val root = JiayinRepository.get(context)
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            root.settings.edit().putBoolean("auto-repair-lyrics", false).putBoolean("rotate", false).commit()
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                withTimeout(20000) { while (vm.state.value.loading || vm.state.value.library.tracks.isEmpty()) delay(100) }
                val tracks = vm.state.value.library.tracks
                vm.repo.put("lyrics", tracks.first().id, Lyrics(tracks.first().id, (0..15).map { LyricLine(it * 1500L, "海へ続く道を歩こう — A long original line with enough words to wrap naturally", "沿着通往海边的路慢慢走，让每一段长句都能够完整阅读。") }, "jpn", source = "生成测试歌词"))
                ins.runOnMainSync { vm.start(tracks, source = "布局测试") }
                withTimeout(15000) { while (!PlaybackHub.state.value.playing) delay(100) }
                ins.runOnMainSync { vm.command("pause") }
                withTimeout(5000) { while (PlaybackHub.state.value.playing) delay(50) }
                delay(4500) // Allow the completed-sync snackbar to leave touch targets.
                val queue = PlaybackHub.state.value.queue
                shot("home")
                click("迷你唱片"); shot("player")
                fingerScrollTo("下载"); shot("player-controls")
                listOf("下一首", "加入歌单", "下载").forEach { scrollTo(it) }
                click("打开歌词"); delay(500); shot("lyrics")
                assertTrue(nodes().any { it.contentDescription?.contains("歌词区域") == true })
                click("收起播放器")
                click("曲库"); click("歌手"); shot("artists")
                click("字母"); assertNotNull(find("选择字母")); shot("letter-picker"); click("取消")
                click("文件夹"); shot("folders")
                click("搜索当前曲库分类")
                scrollTo("搜索关键词")
                val field = nodes().first { it.isEditable }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Fixture") }))
                withTimeout(5000) { while (vm.search.value.query != "Fixture") delay(50) }
                delay(500); shot("search-keyboard")
                withTimeout(5000) { while (vm.search.value.busy) delay(50) }
                val firstHit = vm.search.value.hits.firstOrNull()
                fingerScrollTo(if (firstHit == null) "当前范围没有找到" else "搜索结果：${firstHit.kind}:${firstHit.id}")
                shot("search-results-touch")
                click("取消")
                click("我的"); click("下载与离线"); shot("downloads")
                click("批量管理"); scrollTo("删除全部"); shot("bulk-downloads")
                back(); repeat(5) { nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }; SystemClock.sleep(200) }; click("设置"); click("音乐服务器 · 切换与退出"); shot("servers")
                click("添加服务器"); shot("server-dialog")
                scrollTo("仅保存"); scrollTo("保存并切换"); click("取消")
                val after = PlaybackHub.state.value
                assertEquals("Layout/navigation replaced the queue", queue, after.queue)
                assertFalse("Layout/navigation resumed music", after.playing)
                assertEquals("Layout/navigation started a download", 0, server.downloads.get())
            } catch (error: Throwable) { shot("failure"); throw error } finally { scenario.close(); JiayinMediaService.stopForSwitch(context) }
        }
    }
    @Test(timeout = 90000) fun rotationAndRecreationPreserveAccountSearchAndPausedQueue() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        MediaPipelineTest.MediaServer(catalog = true).use { server ->
            val root = JiayinRepository.get(context)
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                withTimeout(20000) { while (vm.state.value.loading || vm.state.value.library.tracks.isEmpty()) delay(100) }
                ins.runOnMainSync { vm.start(vm.state.value.library.tracks, source = "恢复测试") }
                withTimeout(15000) { while (!PlaybackHub.state.value.playing) delay(100) }
                ins.runOnMainSync { vm.command("pause") }
                withTimeout(5000) { while (PlaybackHub.state.value.playing) delay(50) }
                val queue = PlaybackHub.state.value.queue
                val account = vm.repo.account
                ins.runOnMainSync { vm.openSearch(SearchScope()); vm.updateSearch("播放测试") }
                delay(500)
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                delay(1500)
                scenario.recreate()
                scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
                assertEquals(account, vm.repo.account)
                assertTrue(vm.search.value.active)
                assertEquals("播放测试", vm.search.value.query)
                val restored = PlaybackHub.state.value.queue
                assertEquals("Rotation replaced the paused queue", queue.copy(positionMs = restored.positionMs), restored)
                // Media3 may publish the final pause position one callback after isPlaying=false.
                assertTrue("Rotation lost the paused position", kotlin.math.abs(queue.positionMs - restored.positionMs) <= 2000)
                assertFalse(PlaybackHub.state.value.playing)
                assertEquals(0, server.downloads.get())
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                Unit
            } catch (error: Throwable) { shot("failure"); throw error } finally {
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                scenario.close(); JiayinMediaService.stopForSwitch(context)
            }
        }
    }

}
