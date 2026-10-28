@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.PlaybackHub
import com.eddyizm.tempus.jiayin.ui.OwnershipPage
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ArtistOwnershipAndroidTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    @org.junit.Before fun grantNotifications() { if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS) }
    private val main = ArtistRef("main", "Generated Main")
    private val combo = ArtistRef("combined", "Generated Main/Guest")
    private val songs = listOf(Track("one", "Generated Solo", main.name, artistId = main.id, artists = listOf(main), duration = 30),
        Track("two", "Generated Duet", combo.name, artistId = combo.id, artists = listOf(combo), duration = 30))

    class Backend : AutoCloseable {
        private val socket = ServerSocket(0)
        val address = "http://localhost:${socket.localPort}"
        val calls = CopyOnWriteArrayList<String>()
        var catalog: String = """{"library_id":"generated-library","tracks":[],"cursor":0,"has_more":false}"""
        var old = false
        var secondCatalog: String? = null
        var delayCatalog = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        init { thread(isDaemon = true) {
            while (!socket.isClosed) try {
                val client = socket.accept()
                thread(isDaemon = true) { client.use {
                    val input = it.getInputStream().bufferedReader()
                    val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    var length = 0
                    while (true) { val h = input.readLine(); if (h.isNullOrEmpty()) break; if (h.startsWith("content-length:", true)) length = h.substringAfter(':').trim().toInt() }
                    if (length > 0) { val body = CharArray(length); var at = 0; while (at < length) { val n = input.read(body, at, length - at); if (n < 0) break; at += n } }
                    calls += path
                    if (path.contains("/catalog") && delayCatalog) { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
                    val value = when {
                        path.endsWith("/capabilities") -> if (old) """{"detail":"Not Found"}""" else """{"version":1,"library_id":"generated-library","max_tracks":1000}"""
                        path.contains("/catalog") -> if (path.contains("after=1") && secondCatalog != null) secondCatalog!! else catalog
                        path.endsWith("/submit") -> """{"id":"request-one","status":"pending"}"""
                        else -> "{}"
                    }.toByteArray()
                    val status = if (old && path.endsWith("/capabilities")) "404 Not Found" else "200 OK"
                    it.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${value.size}\r\nConnection: close\r\n\r\n".toByteArray() + value)
                } }
            } catch (_: Exception) { }
        } }
        override fun close() { release.countDown(); socket.close() }
    }

    @Test fun personalBatchIsAccountIsolatedAndSurvivesFullRefreshWithoutMutatingQueue() = runBlocking {
        val root = JiayinRepository.get(context)
        MediaPipelineTest.MediaServer(supplied = songs, allowedUsers = setOf("ownership-a", "ownership-b", "ownership-sync", "ownership-delay", "ownership-touch")).use { music ->
            root.login("http://localhost:${music.port}", "ownership-a", "fixture-password")
            val a = root.scoped(); a.saveLibrary(Library(songs, syncedAt = System.currentTimeMillis()))
            val queue = PlaybackHub.state.value.queue
            val store = OwnershipRepository(a)
            store.save(setOf("two"), main)
            val stamp = store.personal().single().stamp
            assertEquals(main, store.effective()["two"])
            a.saveLibrary(Library(songs, syncedAt = System.currentTimeMillis()))
            assertEquals(stamp, OwnershipRepository(a).personal().single().stamp)
            root.login("http://localhost:${music.port}", "ownership-b", "fixture-password")
            val b = root.scoped(); b.saveLibrary(Library(songs))
            assertTrue(OwnershipRepository(b).personal().isEmpty())
            assertTrue(runCatching { store.save(setOf("one"), combo) }.isFailure)
            root.profiles.activate(a.session!!.profile)
            val resumed = root.scoped()
            assertEquals(main, OwnershipRepository(resumed).effective()["two"])
            OwnershipRepository(resumed).save(setOf("two"), null)
            assertTrue(OwnershipRepository(resumed).effective().isEmpty())
            assertEquals(queue, PlaybackHub.state.value.queue)
            assertEquals(0, music.downloads.get())
        }
    }

    @Test fun sharedPublicationOnlyClearsUnchangedOwnEditAndUndoRestoresOriginal() = runBlocking {
        val root = JiayinRepository.get(context)
        MediaPipelineTest.MediaServer(supplied = songs, allowedUsers = setOf("ownership-a", "ownership-b", "ownership-sync", "ownership-delay", "ownership-touch")).use { music ->
            root.login("http://localhost:${music.port}", "ownership-sync", "fixture-password")
            val repo = root.scoped(); repo.saveLibrary(Library(songs)); val store = OwnershipRepository(repo)
            Backend().use { server ->
                repo.settings.edit().putString("${repo.account}.manager-url", server.address).commit()
                repo.vault.put("${repo.account}.manager-member", "generated-token")
                store.save(setOf("two"), main)
                val p = store.personal()
                store.submit("request-one", p)
                server.catalog = """{"library_id":"generated-library","tracks":[{"track_id":"two","target":{"id":"main","name":"Generated Main"},"sequence":1,"request_id":"request-one"}],"cursor":1,"has_more":false}"""
                store.refresh()
                assertTrue(store.personal().isEmpty()); assertEquals(main, store.effective()["two"])
                store.save(setOf("two"), combo) // A later personal decision remains authoritative.
                server.catalog = """{"library_id":"generated-library","tracks":[{"track_id":"two","target":null,"sequence":2,"request_id":"request-one"}],"cursor":2,"has_more":false}"""
                store.refresh()
                assertEquals(combo, store.effective()["two"])
                store.save(setOf("two"), null)
                assertTrue(store.effective().isEmpty())
                assertEquals(combo, repo.library().tracks.first { it.id == "two" }.performers().single())
            }
        }
    }

    @Test fun delayedSharedResultsAreRejectedAfterAccountSwitchAndOldBackendIsCompatible() = runBlocking {
        val root = JiayinRepository.get(context)
        MediaPipelineTest.MediaServer(supplied = songs, allowedUsers = setOf("ownership-a", "ownership-b", "ownership-sync", "ownership-delay", "ownership-touch")).use { music ->
            root.login("http://localhost:${music.port}", "ownership-delay", "fixture-password")
            val repo = root.scoped(); repo.saveLibrary(Library(songs))
            Backend().use { server ->
                repo.settings.edit().putString("${repo.account}.manager-url", server.address).commit()
                repo.vault.put("${repo.account}.manager-member", "generated-token")
                server.old = true; OwnershipRepository(repo).refresh()
                assertTrue(OwnershipRepository(repo).shared().isEmpty())
                server.old = false; server.delayCatalog = true
                val job = async(Dispatchers.IO) { runCatching { OwnershipRepository(repo).refresh() } }
                assertTrue(withContext(Dispatchers.IO) { server.entered.await(10, TimeUnit.SECONDS) })
                root.profiles.activate(null); server.release.countDown()
                assertTrue(job.await().isFailure)
                assertTrue(repo.list("ownership-shared", SharedOwnership::class.java).isEmpty())
            }
        }
    }
    @Test fun failedSecondPageDoesNotCommitPartialRulesOrAdvanceCursor() = runBlocking {
        val root = JiayinRepository.get(context)
        MediaPipelineTest.MediaServer(supplied = songs).use { music ->
            root.login("http://localhost:${music.port}", "fixture", "fixture-password")
            val repo = root.scoped(); repo.saveLibrary(Library(songs))
            repo.put("ownership-shared", "one", SharedOwnership("one", main, 7))
            Backend().use { server ->
                repo.settings.edit().putString("${repo.account}.manager-url", server.address).commit()
                repo.vault.put("${repo.account}.manager-member", "generated-token")
                server.catalog = """{"library_id":"generated-library","tracks":[{"track_id":"two","target":{"id":"main","name":"Generated Main"},"sequence":1,"request_id":"request-one"}],"cursor":1,"has_more":true}"""
                server.secondCatalog = "generated invalid response"
                assertTrue(runCatching { OwnershipRepository(repo).refresh() }.isFailure)
                assertEquals(listOf("one"), OwnershipRepository(repo).shared().map { it.trackId })
                assertNull(repo.get("ownership-meta", "cursor", java.lang.Long::class.java))
            }
        }
    }

    @Test(timeout = 120000) fun actualTouchSavesOnlySelectedSongsAndRestoreRequiresConfirmation() = runBlocking {
        MediaPipelineTest.MediaServer(supplied = songs, allowedUsers = setOf("ownership-a", "ownership-b", "ownership-sync", "ownership-delay", "ownership-touch")).use { music ->
            val root = JiayinRepository.get(context)
            root.login("http://localhost:${music.port}", "ownership-touch", "fixture-password")
            root.scoped().saveLibrary(Library(songs, syncedAt = System.currentTimeMillis()))
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            try {
                scenario.onActivity { a -> vm = ViewModelProvider(a)[JiayinViewModel::class.java] }
                await { !vm.state.value.loading }
                scenario.onActivity { a -> a.setContent { MaterialThemeForOwnership { OwnershipPage(vm, listOf(songs[1])) {} } } }
                click("搜索目标歌手／新分组名称")
                ins.runOnMainSync { /* Input is filled using accessibility to exercise actual editable semantics. */ }
                val input = nodes().first { it.className?.toString() == "android.widget.EditText" }
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Generated Main") })
                SystemClock.sleep(500)
                ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                click("Generated Main · 1 首")
                click("保存个人整理")
                await { OwnershipRepository(vm.repo).personal().size == 1 }
                assertEquals(main, OwnershipRepository(vm.repo).effective()["two"])
                assertEquals(2, vm.state.value.index.artists.single { it.id == main.key }.tracks.size)
                assertEquals(combo, vm.repo.library().tracks.first { it.id == "two" }.performers().single())
                val dir = java.io.File(context.getExternalFilesDir(null), "ui-fixture").apply { mkdirs() }
                ins.uiAutomation.takeScreenshot().also { bitmap -> java.io.File(dir, "ownership.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                click("选中歌曲恢复默认归属…"); node("恢复 1 首歌曲的默认归属？"); click("取消")
                assertEquals(1, OwnershipRepository(vm.repo).personal().size)
                click("选中歌曲恢复默认归属…"); click("恢复默认")
                await { OwnershipRepository(vm.repo).personal().isEmpty() }
                assertEquals(0, music.downloads.get())
            } finally { scenario.close() }
        }
    }
    @Test(timeout = 60000) fun organizingDuringRealPlaybackKeepsQueuePositionAndDownloadCount() = runBlocking {
        MediaPipelineTest.MediaServer(supplied = songs).use { music ->
            val root = JiayinRepository.get(context)
            root.login("http://localhost:${music.port}", "fixture", "fixture-password")
            root.scoped().saveLibrary(Library(songs, syncedAt = System.currentTimeMillis()))
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            lateinit var vm: JiayinViewModel
            try {
                scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
                await { !vm.state.value.loading }
                ins.runOnMainSync { vm.start(songs) }
                await { PlaybackHub.state.value.playing && PlaybackHub.state.value.queue.positionMs > 500 }
                val before = PlaybackHub.state.value.queue
                OwnershipRepository(vm.repo).save(setOf("two"), main); vm.refreshLocal()
                assertTrue(PlaybackHub.state.value.playing)
                assertEquals(before.entries, PlaybackHub.state.value.queue.entries)
                assertEquals(before.current?.key, PlaybackHub.state.value.queue.current?.key)
                assertTrue(PlaybackHub.state.value.queue.positionMs >= before.positionMs)
                assertEquals(0, music.downloads.get())
            } finally { ins.runOnMainSync { vm.command("pause") }; scenario.close() }
        }
    }
    @androidx.compose.runtime.Composable private fun MaterialThemeForOwnership(content: @androidx.compose.runtime.Composable () -> Unit) { androidx.compose.material3.MaterialTheme(content = content) }
    private fun await(predicate: () -> Boolean) { val end = SystemClock.elapsedRealtime() + 15000; while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Timed out" }; SystemClock.sleep(100) } }
    private fun nodes(): List<AccessibilityNodeInfo> { if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.clearCache(); val root = ins.uiAutomation.rootInActiveWindow ?: return emptyList(); val all = mutableListOf<AccessibilityNodeInfo>(); fun visit(n: AccessibilityNodeInfo) { all += n; repeat(n.childCount) { n.getChild(it)?.let(::visit) } }; visit(root); return all }
    private fun node(label: String): AccessibilityNodeInfo { repeat(60) { nodes().firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }; SystemClock.sleep(100) }; error("Missing $label: ${nodes().mapNotNull { it.text ?: it.contentDescription }}") }
    private fun click(label: String) { val r = Rect().also(node(label)::getBoundsInScreen); val start = SystemClock.uptimeMillis(); for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) { val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, r.centerX().toFloat(), r.centerY().toFloat(), 0); e.source = InputDevice.SOURCE_TOUCHSCREEN; ins.uiAutomation.injectInputEvent(e, true); e.recycle() }; SystemClock.sleep(400) }
}
