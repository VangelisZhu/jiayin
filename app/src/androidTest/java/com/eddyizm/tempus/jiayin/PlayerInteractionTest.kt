package com.eddyizm.tempus.jiayin

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.session.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.jiayin.playback.*
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.Preferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Actual injected touches + actual Media3 service. All audio/artwork/lyrics are generated. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerInteractionTest {
    @org.junit.Before fun grantFixtureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val ins = InstrumentationRegistry.getInstrumentation()
            ins.uiAutomation.grantRuntimePermission(ins.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test(timeout = 150000) fun overlayLyricsFavouritePlaylistAndDownloadBatchRegression() = runBlocking {
        val repo = JiayinRepository.get(context)
        val component = ComponentName(context, JiayinMediaService::class.java)
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        MediaPipelineTest.MediaServer(12).use { server ->
            repo.login("http://localhost:${server.port}", "fixture", "fixture-password")
            Preferences.setDownloadWifiOnly(false)
            repo.saveLibrary(Library(server.tracks))
            repo.dao.delete(repo.account, "queue", "active")
            val song = server.tracks[1]
            repo.put("lyrics", song.id, Lyrics(song.id, (0..5).map { LyricLine(it * 4000L, "Fixture lyric line $it", "示例译文 $it") }, "eng", source = "原创测试歌词"))
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            try {
                scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
                await("library sync") { !vm.state.value.loading && vm.state.value.library.tracks.size == 12 }
                repo.saveLibrary(repo.library().copy(playlists = listOf(MusicList("saved", "测试歌单", listOf(server.tracks[0].id)))))
                vm.refreshLocal()
                instrumentation.runOnMainSync {
                    vm.start(server.tracks, source = "触摸回归", index = 1)
                    future = MediaController.Builder(context, SessionToken(context, component)).buildAsync()
                }
                val controller = future!!.get(12, TimeUnit.SECONDS)
                await("playing") { PlaybackHub.state.value.current?.id == song.id && PlaybackHub.state.value.playing }
                instrumentation.runOnMainSync { vm.command("pause"); vm.requestPlayer() }
                await("paused") { !PlaybackHub.state.value.playing }
                SystemClock.sleep(800)
                val queue = PlaybackHub.state.value.queue
                repeat(12) { click(song.title) }
                assertEquals("Title touches changed the queue", queue.current?.key, PlaybackHub.state.value.queue.current?.key)
                assertEquals("Title touches caused downloads", 0, server.downloads.get())
                assertEquals("Title touches enqueued downloads", 0, DownloadUtil.getDownloadManager(context).currentDownloads.count { com.eddyizm.tempus.jiayin.data.AccountDownloads.trackId(context, repo.account, it.request.id) in server.tracks.map { t -> t.id } })
                click("加入歌单"); node("测试歌单")
                click("测试歌单")
                await("playlist persisted") { repo.library().playlists.any { song.id in it.tracks } }
                click("歌词"); node("Fixture lyric line 1")
                val before = PlaybackHub.state.value.queue.positionMs
                click("Fixture lyric line 1")
                node("打开歌词") // A single tap switches views and does not seek.
                assertTrue(kotlin.math.abs(PlaybackHub.state.value.queue.positionMs - before) < 1000)
                click("打开歌词")
                var rect = bounds(node("Fixture lyric line 1"))
                gesture(rect.centerX().toFloat(), rect.centerY().toFloat(), rect.centerX().toFloat(), rect.centerY().toFloat(), 3200)
                assertTrue("Holding without dragging must not seek", kotlin.math.abs(PlaybackHub.state.value.queue.positionMs - before) < 1000)
                rect = bounds(node("Fixture lyric line 1"))
                val target = bounds(node("Fixture lyric line 2"))
                gesture(rect.centerX().toFloat(), rect.centerY().toFloat(), target.centerX().toFloat(), target.centerY().toFloat(), 3200)
                await("long hold and drag seeks") { PlaybackHub.state.value.queue.positionMs >= 7900 }
                click("唱片")
                instrumentation.runOnMainSync {
                    assertTrue(controller.isSessionCommandAvailable(SessionCommand(PlaybackHub.FAVORITE, Bundle.EMPTY)))
                    assertTrue(controller.mediaButtonPreferences.any { it.sessionCommand?.customAction == PlaybackHub.FAVORITE })
                    controller.sendCustomCommand(SessionCommand(PlaybackHub.FAVORITE, Bundle.EMPTY), Bundle.EMPTY)
                }
                await("system favorite updates both local store and UI") { repo.library().tracks.first { it.id == song.id }.starred && vm.state.value.library.tracks.first { it.id == song.id }.starred }
                assertEquals(queue.current?.key, PlaybackHub.state.value.queue.current?.key)
                instrumentation.runOnMainSync { vm.download(server.tracks.take(3)); vm.watchDownloads(true) }
                instrumentation.runOnMainSync { DownloadUtil.getDownloadManager(context).requirements = Requirements(0) }
                await("three completed downloads", 20000) { server.tracks.take(3).all { DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(com.eddyizm.tempus.jiayin.data.AccountDownloads.requestFor(context, repo.account, it.id)!!.requestId)?.state == Download.STATE_COMPLETED } }
                instrumentation.runOnMainSync { vm.pauseAllDownloads(true) }
                await("all downloads paused") { DownloadUtil.getDownloadManager(context).downloadsPaused }
                instrumentation.runOnMainSync { vm.pauseAllDownloads(false) }
                await("all downloads resumed") { !DownloadUtil.getDownloadManager(context).downloadsPaused }
                // A disappeared library song must still be deletable by request ID.
                repo.saveLibrary(repo.library().copy(tracks = repo.library().tracks.drop(1))); vm.refreshLocal()
                val removed = server.tracks.take(3).map { com.eddyizm.tempus.jiayin.data.AccountDownloads.requestFor(context, repo.account, it.id)!!.requestId }.toSet()
                instrumentation.runOnMainSync { vm.removeDownloads(removed) }
                await("batch removed from Media3 and Room", 15000) { removed.all { DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(it) == null && com.eddyizm.tempus.repository.DownloadRepository().getDownload(it) == null } }
            } finally {
                instrumentation.runOnMainSync { vm.command("pause"); future?.let { MediaController.releaseFuture(it) } }
                scenario.close()
                instrumentation.runOnMainSync { context.stopService(Intent(context, JiayinMediaService::class.java)) }
                await("service stopped") { !PlaybackHub.state.value.ready }
            }
        }
    }
    private suspend fun await(label: String, timeout: Long = 12000, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!check()) { check(SystemClock.elapsedRealtime() < end) { "$label timed out: ${PlaybackHub.state.value.error}" }; delay(100) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>(); val pending = ArrayDeque<AccessibilityNodeInfo>(); pending.add(root)
        while (pending.isNotEmpty()) { val n = pending.removeFirst(); result.add(n); repeat(n.childCount) { n.getChild(it)?.let(pending::add) } }
        return result
    }
    private fun node(label: String): AccessibilityNodeInfo {
        repeat(30) { nodes().firstOrNull { (it.text?.toString() == label || it.contentDescription?.toString() == label) && it.isVisibleToUser }?.let { return it }; SystemClock.sleep(100) }
        error("Missing $label; visible=${nodes().mapNotNull { it.text ?: it.contentDescription }}")
    }
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
    private fun click(label: String) { val r = bounds(node(label)); gesture(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), 70); SystemClock.sleep(180) }
    private fun gesture(x: Float, y: Float, endX: Float, endY: Float, hold: Long) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float) { val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, px, py, 0); e.source = InputDevice.SOURCE_TOUCHSCREEN; instrumentation.uiAutomation.injectInputEvent(e, true); e.recycle() }
        send(MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(hold)
        if (x != endX || y != endY) repeat(10) { i -> send(MotionEvent.ACTION_MOVE, x + (endX-x)*(i+1)/10, y + (endY-y)*(i+1)/10); SystemClock.sleep(30) }
        send(MotionEvent.ACTION_UP, endX, endY)
    }
}
