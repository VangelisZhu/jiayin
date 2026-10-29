package com.eddyizm.tempus.jiayin

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.PlaybackHub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaylistInteractionTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    @Test(timeout = 120000) fun realSwipesConfirmCancelAndPickerPreventsDuplicates() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        MediaPipelineTest.MediaServer(supplied = (1..12).map { Track("gesture-$it", "Fixture $it", "Generated artist", duration = 30) }).use { server ->
            val root = JiayinRepository.get(context)
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                await { !vm.state.value.loading && vm.state.value.library.tracks.size == 12 }
                val repo = vm.repo
                val original = listOf("missing", "gesture-1", "gesture-2", "gesture-3", "gesture-2")
                repo.saveLibrary(Library(server.tracks, listOf(MusicList("p", "Gesture list", original), MusicList("empty", "Empty list"), MusicList("readonly", "Read only", readOnly = true)), syncedAt = System.currentTimeMillis()))
                vm.refreshLocal()
                ins.runOnMainSync { vm.start(server.tracks, source = "Generated queue") }
                await { PlaybackHub.state.value.playing }
                ins.runOnMainSync { vm.command("pause") }
                await { !PlaybackHub.state.value.playing }
                val queue = PlaybackHub.state.value.queue
                click("我的"); click("Gesture list"); node("Fixture 2")
                val row = bounds(node("Fixture 2")); val scale = context.resources.displayMetrics.density
                swipe(row.centerX().toFloat(), row.centerY().toFloat(), -28 * scale, 0f)
                assertFalse(nodes().any { it.text?.toString()?.startsWith("从「") == true })
                assertEquals(original, repo.library().playlists.first().tracks)
                swipe(row.centerX().toFloat(), row.centerY().toFloat(), -120 * scale, 0f)
                node("从「Gesture list」移除这首歌？"); shot("playlist-remove-confirm")
                click("取消"); assertEquals(original, repo.library().playlists.first().tracks)
                swipe(row.centerX().toFloat(), row.centerY().toFloat(), -120 * scale, 0f)
                click("移除")
                await { repo.library().playlists.first().tracks.size == original.size - 1 }
                assertEquals(listOf("missing", "gesture-1", "gesture-3", "gesture-2"), repo.library().playlists.first().tracks)
                await { !nodes().any { it.text?.toString()?.startsWith("从「") == true } }
                // Row menu opens the same confirmation; cancel must retain the surviving occurrence.
                SystemClock.sleep(900)
                more("Fixture 1")
                click("从此歌单移除"); click("取消")
                more("Fixture 1")
                click("更多歌曲操作"); click("加入歌单")
                node("✓ 已在歌单 · 待同步"); node("只读歌单"); shot("playlist-membership")
                click("Gesture list")
                node("加入歌单") // Disabled click must leave picker open and write nothing.
                assertEquals(1, repo.library().playlists.first().tracks.count { it == "gesture-2" })
                click("Empty list")
                await { repo.library().playlists.first { it.id == "empty" }.tracks == listOf("gesture-1") }
                assertEquals(queue.entries, PlaybackHub.state.value.queue.entries)
                assertEquals(queue.current?.key, PlaybackHub.state.value.queue.current?.key)
                assertFalse(PlaybackHub.state.value.playing)
                assertEquals(0, server.downloads.get())
            } finally { ins.runOnMainSync { vm.command("pause") }; scenario.close() }
        }
    }
    private fun await(predicate: () -> Boolean) { val end = SystemClock.elapsedRealtime() + 18000; while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Timed out" }; SystemClock.sleep(100) } }
    private fun nodes(): List<AccessibilityNodeInfo> { ins.uiAutomation.clearCache(); val root = ins.uiAutomation.rootInActiveWindow ?: return emptyList(); val all = mutableListOf<AccessibilityNodeInfo>(); val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root); while(q.isNotEmpty()) { val n=q.removeFirst(); all.add(n); repeat(n.childCount) { n.getChild(it)?.let(q::add) } }; return all }
    private fun node(label: String): AccessibilityNodeInfo { repeat(50) { nodes().firstOrNull { (it.text?.toString() == label || it.contentDescription?.toString() == label) && it.isVisibleToUser }?.let { return it }; SystemClock.sleep(100) }; error("Missing $label: ${nodes().mapNotNull { it.text ?: it.contentDescription }}") }
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen)
    private fun click(label: String) {
        // A sheet exposes semantics before its entrance animation settles. Re-read
        // its native bounds instead of tapping the coordinates of a moving label.
        var previous = bounds(node(label))
        var stable = 0
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (stable < 3) {
            check(SystemClock.elapsedRealtime() < deadline) { "Moving control: $label" }
            SystemClock.sleep(100)
            val current = bounds(node(label))
            stable = if (current == previous) stable + 1 else 0
            previous = current
        }
        tap(previous.centerX().toFloat(), previous.centerY().toFloat())
    }
    private fun more(title: String) {
        val y = bounds(node(title)).centerY()
        val icon = nodes().filter { it.contentDescription?.toString() == "歌曲操作" && it.isVisibleToUser }.minBy { kotlin.math.abs(bounds(it).centerY() - y) }
        val r = bounds(icon); tap(r.centerX().toFloat(), r.centerY().toFloat())
    }
    private fun tap(x: Float,y: Float) = swipe(x,y,0f,0f,1)
    private fun swipe(x:Float,y:Float,dx:Float,dy:Float,steps:Int=16) {
        val start=SystemClock.uptimeMillis()
        fun send(action:Int,px:Float,py:Float) { val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,px,py,0); e.source=InputDevice.SOURCE_TOUCHSCREEN; ins.uiAutomation.injectInputEvent(e,true); e.recycle() }
        send(MotionEvent.ACTION_DOWN,x,y)
        repeat(steps) { i -> SystemClock.sleep(16); send(MotionEvent.ACTION_MOVE,x+dx*(i+1)/steps,y+dy*(i+1)/steps) }
        send(MotionEvent.ACTION_UP,x+dx,y+dy); SystemClock.sleep(400)
    }
    private fun shot(name:String) { val dir=File(context.getExternalFilesDir(null),"ui-fixture").apply { mkdirs() }; ins.uiAutomation.takeScreenshot().also { bitmap -> File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() } }
}
