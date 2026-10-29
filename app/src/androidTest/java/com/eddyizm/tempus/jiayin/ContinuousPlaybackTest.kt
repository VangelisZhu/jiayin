@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.session.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Generated audio only. Tests platform tokens, real Media3 commands and foreground sync gating. */
class ContinuousPlaybackTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private suspend fun await(label: String, test: () -> Boolean) = withTimeout(20000) { while (!test()) { delay(50) }; android.util.Log.i("ContinuousCheck", label) }
    private fun main(action: () -> Unit) = ins.runOnMainSync(action)
    private suspend fun fixture(block: suspend (JiayinViewModel, JiayinRepository, MediaPipelineTest.MediaServer, MediaController) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val tracks = (0 until 80).map { Track("fixture-$it", "Generated $it", "Artist ${it % 20}", artistId = "a${it % 20}", duration = 30) }
        MediaPipelineTest.MediaServer(supplied = tracks).use { server ->
            val repo = JiayinRepository.get(context)
            repo.login("http://localhost:${server.port}", "fixture", "fixture-password")
            repo.saveLibrary(Library(tracks, syncedAt = System.currentTimeMillis()))
            repo.dao.delete(repo.account, "queue", "active")
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            var future: ListenableFuture<MediaController>? = null
            try {
                scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
                await("local library available") { !vm.state.value.loading && vm.state.value.library.tracks.size == 80 }
                main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, JiayinMediaService::class.java))).buildAsync() }
                block(vm, repo, server, future!!.get(12, TimeUnit.SECONDS))
            } finally {
                main { vm.command("pause"); future?.let { MediaController.releaseFuture(it) } }
                scenario.close()
                JiayinMediaService.stopForSwitch(context)
            }
        }
    }
    @Test(timeout = 150000) fun fiftySystemNextCommandsRetainMetadataTokenAndOneNotification() = runBlocking {
        fixture { vm, _, server, controller ->
            main { vm.start(server.tracks) }
            await("playing") { PlaybackHub.state.value.playing }
            val empty = AtomicInteger()
            main { controller.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { if (player.mediaItemCount == 0 || player.mediaMetadata.title.isNullOrBlank()) empty.incrementAndGet() }
            }) }
            val manager = context.getSystemService(NotificationManager::class.java)
            await("session notification") { manager.activeNotifications.any { it.id == 1001 && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) } }
            @Suppress("DEPRECATION") val token = manager.activeNotifications.first { it.id == 1001 }.notification.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_MEDIA_SESSION)
            val sample = launch(Dispatchers.IO) {
                repeat(4000) {
                    val notifications = manager.activeNotifications.filter { it.id == 1001 }
                    if (notifications.size != 1 || notifications.single().notification.extras.get(Notification.EXTRA_MEDIA_SESSION) != token) empty.incrementAndGet()
                    delay(10)
                }
            }
            try {
                repeat(50) { n ->
                    main { controller.seekToNextMediaItem() }
                    await("next ${n + 1}") { PlaybackHub.state.value.current?.id == server.tracks[n + 1].id && PlaybackHub.state.value.playing }
                }
            } finally { sample.cancelAndJoin() }
            assertEquals("Empty metadata or missing/replaced session notification during next", 0, empty.get())
            assertEquals(0, server.downloads.get())
            assertEquals("Startup must not full-sync a fresh local library", 0, server.searches.get())
        }
    }
    @Test(timeout = 100000) fun appendPreservesPausePositionManualQueueAndPersistsMode() = runBlocking {
        fixture { vm, repo, server, _ ->
            main { vm.start(server.tracks.take(3)) }
            await("playing") { PlaybackHub.state.value.playing }
            main { vm.command("pause") }
            await("paused") { PlaybackHub.state.value.cause == PauseCause.USER && !PlaybackHub.state.value.playing }
            val before = PlaybackHub.state.value
            main { vm.command("insert") { putString("track", server.tracks[60].id); putBoolean("next", true) } }
            await("manual added") { PlaybackHub.state.value.queue.entries.size == 4 }
            main { vm.command("discovery") { putBoolean("enabled", true) } }
            await("appended while paused") { PlaybackHub.state.value.queue.entries.size >= 24 }
            val after = PlaybackHub.state.value
            assertFalse(after.playing); assertEquals(before.queue.current?.key, after.queue.current?.key)
            assertTrue(kotlin.math.abs(before.queue.positionMs - after.queue.positionMs) < 500)
            assertEquals(server.tracks[60].id, after.queue.entries[1].trackId)
            assertTrue(after.queue.entries[1].manual)
            assertTrue(after.queue.discovery?.enabled == true)
            main { vm.command("repeat") }
            await("repeat disables discovery") { PlaybackHub.state.value.queue.discovery?.enabled == false }
            assertEquals(0, server.downloads.get())
            await("queue persisted") { repo.get("queue", "active", QueueState::class.java)?.discovery?.enabled == false }
            main { vm.command("discovery") { putBoolean("enabled", true) } }
            await("enabled persisted") { repo.get("queue", "active", QueueState::class.java)?.discovery?.enabled == true }
            val saved = repo.get("queue", "active", QueueState::class.java)!!
            JiayinMediaService.stopForSwitch(context)
            var rebound: ListenableFuture<MediaController>? = null
            main { rebound = MediaController.Builder(context, SessionToken(context, ComponentName(context, JiayinMediaService::class.java))).buildAsync() }
            try {
                rebound!!.get(12, TimeUnit.SECONDS)
                await("restored infinite queue") { PlaybackHub.state.value.ready && PlaybackHub.state.value.queue.current?.key == saved.current?.key }
                assertTrue(PlaybackHub.state.value.queue.discovery?.enabled == true)
                assertEquals(saved.discovery?.roundIds, PlaybackHub.state.value.queue.discovery?.roundIds)
                assertFalse(PlaybackHub.state.value.playing)
                assertEquals(saved.entries, PlaybackHub.state.value.queue.entries)
            } finally { main { rebound?.let { MediaController.releaseFuture(it) } } }
        }
    }
    @Test(timeout = 150000) fun tenAutomaticBatchesAppendWithoutResettingCurrentItemAndBoundHistory() = runBlocking {
        fixture { vm, _, server, _ ->
            main { vm.discover() }
            await("discovery playing") { PlaybackHub.state.value.playing && PlaybackHub.state.value.queue.discovery?.enabled == true }
            repeat(11) { n ->
                val old = PlaybackHub.state.value.queue
                val target = (old.entries.lastIndex - 5).coerceAtLeast(0)
                val key = old.entries[target].key
                val tail = old.entries.last().key
                main { vm.command("jump") { putInt("index", target) } }
                await("batch $n") { PlaybackHub.state.value.queue.entries.lastOrNull()?.key != tail && PlaybackHub.state.value.queue.current?.key == key }
                assertTrue(PlaybackHub.state.value.queue.index <= 200)
                assertTrue(PlaybackHub.state.value.queue.entries.size <= 226)
                assertTrue(PlaybackHub.state.value.queue.entries.drop(PlaybackHub.state.value.queue.index + 1).groupBy { it.trackId }.values.none { it.size > 1 })
            }
            assertEquals(0, server.downloads.get())
            assertEquals(0, server.searches.get())
        }
    }
    @Test(timeout = 70000) fun repeatedForegroundIsLocalAndManualSyncStillWorks() = runBlocking {
        fixture { vm, repo, server, _ ->
            repeat(5) { main { vm.foreground() }; delay(100) }
            assertEquals(0, server.searches.get())
            var job: Job? = null
            main { vm.foreground(); job = vm.sync() }; job!!.join()
            assertEquals("Explicit sync must not be swallowed by an in-flight automatic due check", 1, server.searches.get())
            server.online = false
            main { job = vm.sync() }; job!!.join()
            assertEquals(80, repo.library().tracks.size)
            assertTrue(vm.state.value.status.startsWith("更新失败"))
            val failed = repo.scoped().syncPolicy().lastFailure
            assertTrue(failed > 0)
            server.online = true
            repo.saveLibrary(repo.library().copy(syncedAt = System.currentTimeMillis() - 31 * 86_400_000L))
            val calls = server.searches.get()
            main { repeat(5) { vm.foreground() } }; delay(400)
            assertEquals("Failure backoff blocks automatic full sync", calls, server.searches.get())
            repo.dao.delete(repo.account, "sync-meta", "failed")
            main { repeat(5) { vm.foreground() } }
            await("one due update") { repo.syncPolicy().lastSuccess > System.currentTimeMillis() - 60_000 && !vm.state.value.syncing }
            assertEquals(calls + 1, server.searches.get())
        }
    }
    @Test(timeout = 90000) fun offlineDiscoveryOnlyAppendsConfirmedDownloads() = runBlocking {
        fixture { vm, repo, server, _ ->
            val selected = server.tracks.take(3)
            com.eddyizm.tempus.util.Preferences.setDownloadWifiOnly(false)
            main {
                vm.download(selected)
                com.eddyizm.tempus.util.DownloadUtil.getDownloadManager(context).requirements = androidx.media3.exoplayer.scheduler.Requirements(0)
            }
            await("offline files ready") { selected.all { AccountDownloads.downloaded(context, repo.account, it.id) } }
            server.online = false
            var job: Job? = null
            main { job = vm.switchServer(repo.profiles.active()!!.profile, offline = true) }; job!!.join()
            assertTrue(vm.repo.offline)
            val streams = server.streams.get(); val downloads = server.downloads.get()
            main { vm.discover() }
            await("offline discovery playing") { PlaybackHub.state.value.playing && PlaybackHub.state.value.queue.discovery?.enabled == true }
            val tail = PlaybackHub.state.value.queue.entries.last().key
            main { vm.command("jump") { putInt("index", PlaybackHub.state.value.queue.entries.lastIndex) } }
            await("offline batch appended") { PlaybackHub.state.value.queue.entries.last().key != tail }
            assertTrue(PlaybackHub.state.value.queue.entries.all { e -> selected.any { it.id == e.trackId } })
            assertEquals(streams, server.streams.get()); assertEquals(downloads, server.downloads.get())
        }
    }
    @Test(timeout = 45000) fun KoreanJsonIgnoresIncorrectLatinCharsetAndKeepsGoodLines() = runBlocking {
        fixture { _, repo, server, _ ->
            server.jsonCharset = "ISO-8859-1"
            server.lyrics = Lyrics(server.tracks[0].id, listOf(LyricLine(0, "안녕하세요"), LyricLine(1000, "부분\uFFFD"), LyricLine(2000, "日本語 English 한국어")))
            val result = LyricsRepository(repo.scoped(), LyricSearch { emptyList() }).load(server.tracks[0], true)!!
            assertEquals("안녕하세요", result.lines[0].original)
            assertEquals("此行文字异常", result.lines[1].original)
            assertEquals("日本語 English 한국어", result.lines[2].original)
            assertNotNull(result.issue)
        }
    }
}
