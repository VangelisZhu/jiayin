@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.*
import com.eddyizm.tempus.jiayin.ui.Covers
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.Preferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real HTTP, decoder, download cache, registry and Compose lifecycle; intentionally colliding IDs. */
@RunWith(AndroidJUnit4::class)
class ServerSwitchTest {
    @org.junit.Before fun grantFixtureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val ins = InstrumentationRegistry.getInstrumentation()
            ins.uiAutomation.grantRuntimePermission(ins.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val root get() = JiayinRepository.get(context)
    private suspend fun waitFor(label: String, timeout: Long = 20000, condition: () -> Boolean) {
        android.util.Log.i("ServerSwitchCheck", "WAIT $label")
        withTimeout(timeout) { while (!condition()) delay(50) }
        android.util.Log.i("ServerSwitchCheck", "OK $label")
    }
    private suspend fun action(block: () -> Job) {
        val caller = Throwable().stackTrace.firstOrNull { it.fileName == "ServerSwitchTest.kt" && it.methodName != "action" }
        android.util.Log.i("ServerSwitchCheck", "ACTION $caller")
        var job: Job? = null; ins.runOnMainSync { job = block() }
        withTimeout(25_000) { job!!.join() }
        android.util.Log.i("ServerSwitchCheck", "DONE $caller")
    }
    private suspend fun stop() { android.util.Log.i("ServerSwitchCheck", "STOP"); JiayinMediaService.stopForSwitch(context); android.util.Log.i("ServerSwitchCheck", "STOPPED") }

    @Test(timeout = 45000) fun concurrentComponentsPreserveEveryOptimisticFavorite() = runBlocking {
        MediaPipelineTest.MediaServer(64).use { server ->
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            root.saveLibrary(Library(server.tracks)); root.dao.clear(root.account, "outbox")
            withContext(Dispatchers.Default) { server.tracks.map { track -> async { root.scoped().favorite(track) } }.awaitAll() }
            assertEquals(64, root.library().tracks.count { it.starred })
            assertEquals(64, root.list("outbox", PendingOperation::class.java).size)
        }
    }

    @Test(timeout = 180000) fun collidingIdsSwitchPlaybackDownloadsCoversAndRestoreOriginalPosition() = runBlocking {
        val id = "same-${UUID.randomUUID()}"
        val aTrack = Track(id, "海岸示例", "示例歌手 A", cover = "same-cover", duration = 30)
        val bTrack = aTrack.copy(title = "日落示例", artist = "示例歌手 B")
        MediaPipelineTest.MediaServer(supplied = listOf(aTrack), frequency = 330, coverColor = 0xff287c9d.toInt()).use { a ->
            MediaPipelineTest.MediaServer(supplied = listOf(bTrack), frequency = 660, coverColor = 0xffdf784b.toInt()).use { b ->
                root.login("http://localhost:${a.port}", "fixture", "fixture-password")
                val aProfile = root.profiles.active()!!.profile
                root.profiles.rename(aProfile.id, "海岸示例服务器")
                root.saveLibrary(Library(listOf(aTrack)))
                val bProfile = root.saveProfile("http://localhost:${b.port}", "fixture", "fixture-password", "日落示例服务器")
                assertEquals(aProfile.id, root.account) // save only must not switch
                val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java))
                lateinit var vm: JiayinViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
                try {
                    waitFor("A loaded") { !vm.state.value.loading && vm.state.value.library.tracks.firstOrNull()?.title == aTrack.title }
                    val aCover = Covers.load(context, aTrack.cover)!!
                    Preferences.setDownloadWifiOnly(false)
                    action { vm.start(listOf(aTrack)) }
                    waitFor("A decoder") { PlaybackHub.state.value.playing && PlaybackHub.state.value.queue.positionMs > 1500 }
                    action { vm.download(listOf(aTrack)) }
                    ins.runOnMainSync { DownloadUtil.getDownloadManager(context).requirements = Requirements(0) }
                    waitFor("A downloaded") { AccountDownloads.downloaded(context, aProfile.id, id) }
                    val aDownload = AccountDownloads.requestFor(context, aProfile.id, id)!!
                    val old = vm.repo
                    val atSwitch = PlaybackHub.state.value.queue.positionMs
                    action { vm.switchServer(bProfile) }
                    assertNull(vm.servers.value.error)
                    waitFor("B loaded") { !vm.state.value.loading && vm.state.value.library.tracks.firstOrNull()?.title == bTrack.title }
                    assertFalse(PlaybackHub.state.value.playing)
                    assertEquals(aProfile.id, old.account)
                    assertFalse(old.currentSession())
                    assertTrue(runCatching { old.request("ping") }.isFailure)
                    assertFalse(AccountDownloads.downloaded(context, bProfile.id, id))
                    assertNotEquals(aCover.hash, Covers.load(context, bTrack.cover)!!.hash)
                    assertNotEquals(AccountDownloads.mediaItem(context, old, aTrack).localConfiguration!!.customCacheKey, AccountDownloads.mediaItem(context, vm.repo, bTrack).localConfiguration!!.customCacheKey)
                    action { vm.start(listOf(bTrack)) }
                    waitFor("B decoder") { PlaybackHub.state.value.playing && PlaybackHub.state.value.current?.title == bTrack.title && PlaybackHub.state.value.queue.positionMs > 500 }
                    assertTrue(b.streams.get() > 0)
                    action { vm.download(listOf(bTrack)) }
                    ins.runOnMainSync { DownloadUtil.getDownloadManager(context).requirements = Requirements(0) }
                    waitFor("B downloaded") { AccountDownloads.downloaded(context, bProfile.id, id) }
                    assertNotEquals(aDownload.requestId, AccountDownloads.requestFor(context, bProfile.id, id)!!.requestId)
                    action { vm.switchServer(root.profiles.find(aProfile.id)!!) }
                    waitFor("A paused queue restored") { PlaybackHub.state.value.ready && PlaybackHub.state.value.current?.title == aTrack.title }
                    assertFalse(PlaybackHub.state.value.playing)
                    assertTrue(PlaybackHub.state.value.queue.positionMs >= atSwitch - 500)
                    assertTrue(PlaybackHub.state.value.queue.positionMs <= atSwitch + 2000)
                    ins.runOnMainSync { vm.watchDownloads(true) }
                    waitFor("A only downloads") { vm.state.value.downloads.size == 1 }
                    assertEquals(aDownload.requestId, vm.state.value.downloads.single().id)
                    // Explicit offline switch must use A's downloaded bytes without HTTP.
                    a.online = false
                    action { vm.switchServer(root.profiles.find(aProfile.id)!!, offline = true) }
                    val before = a.requests.get()
                    action { vm.start(listOf(aTrack)) }
                    waitFor("A offline decode") { PlaybackHub.state.value.playing && PlaybackHub.state.value.queue.positionMs > 700 }
                    assertEquals(before, a.requests.get())
                    a.online = true
                } finally { scenario.close(); stop() }
            }
        }
    }

    @Test(timeout = 120000) fun logoutRetainsDataRequiresLoginAndRemovalOnlyErasesSelectedAccount() = runBlocking {
        MediaPipelineTest.MediaServer().use { a -> MediaPipelineTest.MediaServer(username = "second").use { b ->
            root.login("http://localhost:${a.port}", "fixture", "fixture-password")
            val first = root.profiles.active()!!.profile
            val second = root.saveProfile("http://localhost:${b.port}", "second", "fixture-password", "第二账户")
            root.put("listening", "retained", Listening("retained", heardMs = 3210))
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                waitFor("loaded") { !vm.state.value.loading }
                action { vm.logoutServer(first) }
                assertFalse(vm.state.value.configured)
                assertNull(root.vault.get("${first.id}.token")); assertNull(root.vault.get("${first.id}.salt"))
                assertFalse(root.profiles.find(first.id)!!.authenticated)
                assertNotNull(root.dao.get(first.id, "listening", "retained"))
                val calls = a.requests.get(); delay(700); assertEquals(calls, a.requests.get())
                action { vm.switchServer(root.profiles.find(first.id)!!, offline = true) }
                assertFalse(vm.state.value.configured)
                assertNotNull(vm.servers.value.error)
                action { vm.saveServer(first.address, first.username, "fixture-password", first.name, true) }
                assertEquals(first.id, vm.repo.account)
                assertEquals(3210L, vm.repo.get("listening", "retained", Listening::class.java)!!.heardMs)
                val sessionBefore = vm.repo.session
                action { vm.logoutServer(second, true) }
                assertEquals(sessionBefore, vm.repo.session)
                assertNull(root.profiles.find(second.id))
                assertNotNull(root.dao.get(first.id, "listening", "retained"))
                assertNotNull(root.vault.get("${first.id}.token"))
            } finally { scenario.close(); stop() }
        } }
    }

    @Test(timeout = 120000) fun delayedOldSyncCannotOverwriteNewAccountAndFailedSwitchKeepsCurrent() = runBlocking {
        MediaPipelineTest.MediaServer().use { a -> MediaPipelineTest.MediaServer().use { b ->
            root.login("http://localhost:${a.port}", "fixture", "fixture-password")
            val first = root.profiles.active()!!.profile
            val second = root.saveProfile("http://localhost:${b.port}", "fixture", "fixture-password")
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                waitFor("A loaded") { !vm.state.value.loading && vm.state.value.library.tracks.size == 2 }
                b.online = false
                action { vm.switchServer(second) }
                assertEquals(first.id, vm.repo.account)
                assertNotNull(vm.servers.value.error)
                assertEquals(second.id, vm.servers.value.offlineTarget?.id)
                b.online = true
                a.searchDelay = 1600
                ins.runOnMainSync { vm.sync() }
                delay(100)
                action { vm.switchServer(second) }
                assertNull(vm.servers.value.error)
                waitFor("B loaded") { !vm.state.value.loading && vm.state.value.library.tracks.firstOrNull()?.id == b.tracks[0].id }
                delay(1800)
                assertEquals(second.id, vm.repo.account)
                assertEquals(b.tracks.map { it.id }, vm.state.value.library.tracks.map { it.id })
                assertTrue(vm.search.value.hits.isEmpty())
                // Repeated submissions are coalesced while the first switch is running.
                var one: Job? = null; var two: Job? = null
                a.searchDelay = 0
                ins.runOnMainSync { one = vm.switchServer(first); two = vm.switchServer(first) }
                assertSame(one, two); one!!.join()
                assertEquals(first.id, vm.repo.account)
            } finally { scenario.close(); stop() }
        } }
    }

    @Test(timeout = 90000) fun sameServerDifferentUsersAndRejectedPasswordRemainIsolated() = runBlocking {
        MediaPipelineTest.MediaServer(allowedUsers = setOf("fixture", "second")).use { server ->
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            val first = root.profiles.active()!!.profile
            root.put("search-history", "only-first", SearchHistory("海岸", 123))
            val second = root.saveProfile(first.address, "second", "fixture-password", "共享 NAS · 第二账户")
            assertNotEquals(first.id, second.id)
            val previousToken = root.vault.get("${first.id}.token")
            assertTrue(runCatching { root.saveProfile(first.address, first.username, "wrong-password") }.exceptionOrNull() is AuthenticationRequired)
            assertEquals(previousToken, root.vault.get("${first.id}.token"))
            assertEquals(first.id, root.account)
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                waitFor("first loaded") { !vm.state.value.loading }
                action { vm.switchServer(second) }
                assertNull(vm.servers.value.error)
                assertEquals("second", vm.repo.user)
                assertNull(vm.repo.get("search-history", "only-first", SearchHistory::class.java))
                assertNotNull(root.dao.get(first.id, "search-history", "only-first"))
                val session = vm.repo.session
                action { vm.logoutServer(first) }
                assertEquals(session, vm.repo.session)
                assertNotNull(root.vault.get("${second.id}.token"))
                assertTrue(vm.repo.testConnection().startsWith("已连接"))
            } finally { scenario.close(); stop() }
        }
    }

    @Test(timeout = 150000) fun incompleteDownloadsPauseAcrossSwitchAndResumeOnlyTheirOwner() = runBlocking {
        MediaPipelineTest.MediaServer().use { a -> MediaPipelineTest.MediaServer().use { b ->
            a.downloadChunkDelay = 200
            root.login("http://localhost:${a.port}", "fixture", "fixture-password")
            val first = root.profiles.active()!!.profile
            val second = root.saveProfile("http://localhost:${b.port}", "fixture", "fixture-password")
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java))
            lateinit var vm: JiayinViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
            try {
                waitFor("first loaded") { !vm.state.value.loading }
                Preferences.setDownloadWifiOnly(false)
                action { vm.download(a.tracks) }
                ins.runOnMainSync { DownloadUtil.getDownloadManager(context).requirements = Requirements(0) }
                waitFor("partial bytes") { (AccountDownloads.requestFor(context, first.id, a.tracks[0].id)?.let { DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(it.requestId)?.bytesDownloaded ?: 0 } ?: 0) > 10000 }
                action { vm.switchServer(second) }
                val aIds = a.tracks.map { AccountDownloads.requestFor(context, first.id, it.id)!!.requestId }
                aIds.forEach { assertEquals(Download.STATE_STOPPED, DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(it)!!.state) }
                val calls = a.downloads.get()
                action { vm.download(listOf(b.tracks[0])) }
                ins.runOnMainSync { DownloadUtil.getDownloadManager(context).requirements = Requirements(0); vm.pauseAllDownloads(false) }
                waitFor("B complete") { AccountDownloads.downloaded(context, second.id, b.tracks[0].id) }
                delay(200)
                assertEquals(calls, a.downloads.get())
                aIds.forEach { assertEquals(Download.STATE_STOPPED, DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(it)!!.state) }
                action { vm.logoutServer(first, true) }
                aIds.forEach { assertNull(DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(it)) }
                assertTrue(AccountDownloads.downloaded(context, second.id, b.tracks[0].id))
                assertEquals(second.id, vm.repo.account)
            } finally { scenario.close(); stop() }
        } }
    }
}
