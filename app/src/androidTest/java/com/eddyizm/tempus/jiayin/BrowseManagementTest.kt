package com.eddyizm.tempus.jiayin

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.jiayin.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class BrowseManagementTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test(timeout = 90000) fun artistTabsSearchClassificationFavoritesAndBulkDownloadControls() = runBlocking {
        val repo = JiayinRepository.get(context)
        val component = ComponentName(context, JiayinMediaService::class.java)
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        try { MediaPipelineTest.MediaServer(12, catalog = true).use { server ->
            repo.login("http://localhost:${server.port}", "fixture", "fixture-password")
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                await("catalog") { !vm.state.value.loading && vm.state.value.library.tracks.size == 12 }
                val previousFixtures = mutableListOf<String>()
                com.eddyizm.tempus.util.DownloadUtil.getDownloadManager(context).downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) if (cursor.download.request.id.startsWith("pipeline-")) previousFixtures += cursor.download.request.id
                }
                instrumentation.runOnMainSync { previousFixtures.forEach { id -> androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(context, com.eddyizm.tempus.service.DownloaderService::class.java, id, false) } }
                await("previous generated fixtures removed") { previousFixtures.all { com.eddyizm.tempus.util.DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(it) == null } }
                assertEquals(3, vm.state.value.artists.size)
                val song = vm.state.value.library.tracks.first()
                click("搜索"); click("歌手"); click(song.artist)
                await("popular songs") { vm.state.value.topSongs.values.any { it.isNotEmpty() } }
                node("热门歌曲"); shot("10-artist-songs")
                click("收藏歌手")
                await("artist favorite synchronized") { server.starCalls.get() > 0 && vm.state.value.artists[artistKey(song)]?.starred == true }
                click("搜索歌手作品"); node("搜索这位歌手的歌曲或专辑")
                click("专辑"); shot("11-artist-albums")
                click("设置歌手分类"); click("日本"); click("乐队/组合"); click("保存")
                await("classification") { vm.state.value.artists[artistKey(song)]?.region == "日本" }
                repo.synchronize(); vm.refreshLocal()
                assertEquals("日本", vm.state.value.artists[artistKey(song)]?.region)
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                click("日本"); click("乐队/组合"); node(song.artist); shot("12-artist-categories")
                click("曲风"); node("曲库中的曲风"); click("风格 1"); node("随机播放 · 6 首"); shot("13-genres")
                click("我的"); click("下载与离线")
                // Seed genuine paused Media3 requests. No reflective UI-state injection.
                instrumentation.runOnMainSync {
                    server.tracks.take(3).forEach { t ->
                        val request = androidx.media3.exoplayer.offline.DownloadRequest.Builder(t.id, android.net.Uri.parse("http://localhost:${server.port}/rest/download.view")).build()
                        androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(context, com.eddyizm.tempus.service.DownloaderService::class.java, request, 1, false)
                    }
                }
                await("paused download rows") { vm.state.value.downloads.count { it.id in server.tracks.map { t -> t.id } } == 3 }
                val count = vm.state.value.downloads.size
                click("批量管理"); click("全选"); node("删除所选 $count"); shot("14-downloads-batch")
                click("删除全部"); node("移除 $count 首下载？"); click("取消")
                instrumentation.runOnMainSync { vm.removeDownloads(server.tracks.take(3).map { it.id }.toSet()) }
                server.rejectMetadata = true
                val fallback = artistCollection(song, vm.state.value.library.tracks)
                assertTrue(repo.topSongs(song.artist, fallback.tracks.toSet()).isEmpty())
                val profile = vm.artistProfile(song)
                repo.favoriteArtist(profile) // Pending artistId must not crash song snapshot merging.
                repo.synchronize()
                assertEquals(12, repo.library().tracks.size)
            } finally { scenario.close() }
        } } finally { context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP) }
    }
    private suspend fun await(label: String, check: () -> Boolean) { val end = SystemClock.elapsedRealtime()+12000; while (!check()) { check(SystemClock.elapsedRealtime()<end) { "$label timed out" }; delay(100) } }
    private fun nodes(): List<AccessibilityNodeInfo> { val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList(); val all = mutableListOf<AccessibilityNodeInfo>(); val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root); while (q.isNotEmpty()) { val n=q.removeFirst(); all.add(n); repeat(n.childCount) { n.getChild(it)?.let(q::add) } }; return all }
    private fun node(label: String): AccessibilityNodeInfo { repeat(30) { nodes().firstOrNull { (it.text?.toString()==label || it.contentDescription?.toString()==label) && it.isVisibleToUser }?.let { return it }; SystemClock.sleep(100) }; error("Missing $label: ${nodes().mapNotNull { it.text ?: it.contentDescription }}") }
    private fun click(label: String) { val rect=Rect(); node(label).getBoundsInScreen(rect); val start=SystemClock.uptimeMillis(); listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).forEach { action -> val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,rect.centerX().toFloat(),rect.centerY().toFloat(),0); e.source=InputDevice.SOURCE_TOUCHSCREEN; instrumentation.uiAutomation.injectInputEvent(e,true); e.recycle(); SystemClock.sleep(60) }; SystemClock.sleep(220) }
    private fun shot(name: String) { val folder=File(context.getExternalFilesDir(null),"ui-fixture").apply { mkdirs() }; instrumentation.uiAutomation.takeScreenshot().also { bitmap -> File(folder,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() } }
}
