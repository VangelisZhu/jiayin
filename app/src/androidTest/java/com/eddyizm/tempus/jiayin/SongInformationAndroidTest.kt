@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongInformationAndroidTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private suspend fun waitFor(check: () -> Boolean) = withTimeout(18000) { while (!check()) delay(50) }
    @Test(timeout = 180000) fun songInfoNavigationRestoresLyricsAndBrowseWithoutChangingPlayback() = runBlocking {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val a = ArtistRef("artist-one", "晨光"); val b = ArtistRef("artist-two", "远山")
        val duet = Track("duet", "海岸合唱", "晨光 / 远山", album = "海岸", albumId = "coast", cover = "cover", duration = 300, artists = listOf(a, b), albumArtists = listOf(a))
        val other = duet.copy(id = "other", title = "晚风", artist = "晨光", artists = listOf(a), albumId = "another-album")
        MediaPipelineTest.MediaServer(supplied = listOf(duet, other), durationSeconds = 300).use { server ->
            val root = JiayinRepository.get(context)
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            root.saveLibrary(Library(server.tracks, syncedAt = System.currentTimeMillis()))
            root.put("ownership-personal", duet.id, PersonalOwnership(duet.id, a, "fixture"))
            root.put("lyrics", duet.id, Lyrics(duet.id, (0..5).map { LyricLine(it * 4000L, "海风与日光 $it") }, source = "生成测试歌词"))
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                waitFor { !vm.state.value.loading }
                EditorTouch.click("我的")
                ins.runOnMainSync { vm.openSearch(); vm.updateSearch("海岸"); vm.start(server.tracks); vm.requestPlayer() }
                waitFor { PlaybackHub.state.value.playing && PlaybackHub.state.value.current?.id == duet.id }
                delay(600)
                val queue = PlaybackHub.state.value.queue.entries
                repeat(3) { EditorTouch.click("晨光 / 远山"); EditorTouch.click("海岸合唱") }
                EditorTouch.node("正在播放")
                EditorTouch.click("歌词"); EditorTouch.node("歌词区域：轻点切换唱片，长按三秒后拖动并松手跳转")
                EditorTouch.click("歌曲信息")
                EditorTouch.node("演唱者"); EditorTouch.node("远山")
                EditorTouch.shot("04-song-information")
                EditorTouch.click("远山")
                EditorTouch.node("搜索歌手作品") // The actual artist page is visible, not behind the player.
                EditorTouch.node("海岸合唱")
                assertFalse(EditorTouch.nodes().any { it.text?.toString() == "正在播放" })
                EditorTouch.shot("05-performer-detail")
                EditorTouch.click("搜索歌手作品")
                ins.runOnMainSync { vm.updateSearch("海岸") }
                waitFor { !vm.search.value.busy }
                assertTrue(vm.search.value.scope.originalPerformer)
                assertTrue(vm.search.value.hits.any { it.kind == SearchKind.SONGS && it.id == duet.id })
                ins.runOnMainSync { vm.updateSearch("晚风") }
                waitFor { !vm.search.value.busy }
                assertTrue(vm.search.value.hits.isEmpty())
                ins.runOnMainSync { vm.hideSearch() }
                // Nested album browsing returns through the artist, then the exact player mode.
                EditorTouch.click("参与作品"); EditorTouch.click("海岸")
                EditorTouch.node("播放全部")
                EditorTouch.click("返回"); EditorTouch.node("搜索歌手作品")
                EditorTouch.click("返回"); EditorTouch.node("歌词区域：轻点切换唱片，长按三秒后拖动并松手跳转")
                assertEquals("海岸", vm.search.value.query)
                assertNull(vm.search.value.scope.artist)
                assertTrue(PlaybackHub.state.value.playing)
                assertEquals(queue, PlaybackHub.state.value.queue.entries); assertEquals(0, server.downloads.get())
                EditorTouch.click("歌曲信息"); EditorTouch.click("海岸")
                EditorTouch.node("播放全部")
                assertFalse(EditorTouch.nodes().any { it.text?.toString() == "晚风" }) // Same album title, different ID.
                EditorTouch.click("返回"); EditorTouch.node("歌词区域：轻点切换唱片，长按三秒后拖动并松手跳转")
                // Snapshot stays on the opened song even when playback advances underneath.
                EditorTouch.click("歌曲信息")
                ins.runOnMainSync { vm.command("next") }
                waitFor { PlaybackHub.state.value.current?.id == other.id }
                EditorTouch.node("海岸合唱"); EditorTouch.node("远山")
                EditorTouch.click("关闭"); EditorTouch.node("晚风")
                EditorTouch.click("收起播放器")
                assertTrue(vm.search.value.active)
                ins.runOnMainSync { vm.hideSearch() }
                EditorTouch.node("留住每一次喜欢")
                assertEquals(queue, PlaybackHub.state.value.queue.entries)
                assertEquals(0, server.downloads.get())
            } finally { scenario.close(); JiayinMediaService.stopForSwitch(context) }
        }
    }
}
