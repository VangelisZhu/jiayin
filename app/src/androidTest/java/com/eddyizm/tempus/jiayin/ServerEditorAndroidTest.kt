@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.*
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.Preferences
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerEditorAndroidTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val root get() = JiayinRepository.get(context)
    private fun draft(p: ServerProfile) = ServerDraft(p.id, p.name, p.address, p.username)
    private suspend fun waitFor(check: () -> Boolean) = withTimeout(20000) { while (!check()) delay(50) }
    private suspend fun action(block: () -> Job?) { var job: Job? = null; ins.runOnMainSync { job = block() }; withTimeout(30000) { job?.join() } }
    private suspend fun fixture(server: MediaPipelineTest.MediaServer, block: suspend (JiayinViewModel) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) ins.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        root.login("http://localhost:${server.port}", "fixture", "fixture-password")
        root.saveLibrary(Library(server.tracks, syncedAt = System.currentTimeMillis()))
        val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        lateinit var vm: JiayinViewModel
        scenario.onActivity { vm = ViewModelProvider(it)[JiayinViewModel::class.java] }
        try { waitFor { !vm.state.value.loading }; block(vm) }
        finally { scenario.close(); JiayinMediaService.stopForSwitch(context) }
    }

    @Test(timeout = 60000) fun startupSnapshotCannotOverwriteQueueBeforeRestoration() = runBlocking {
        MediaPipelineTest.MediaServer().use { server -> fixture(server) { vm ->
            action { vm.start(server.tracks) }; waitFor { PlaybackHub.state.value.playing }
            ins.runOnMainSync { vm.command("pause") }
            waitFor { !PlaybackHub.state.value.playing && root.get("queue", "active", QueueState::class.java)?.entries?.isNotEmpty() == true }
            val before = root.get("queue", "active", QueueState::class.java)!!
            // Reproduce the lifecycle boundary: the service exists, but startup IO has not
            // installed its persisted queue yet. A shutdown/noisy callback requests a snapshot.
            val type = JiayinMediaService::class.java
            val service = type.getDeclaredField("active").apply { isAccessible = true }.get(null)!!
            val state = type.getDeclaredField("state").apply { isAccessible = true }
            val loaded = type.getDeclaredField("queueLoaded").apply { isAccessible = true }
            val persist = type.getDeclaredMethod("persist").apply { isAccessible = true }
            val storage = type.getDeclaredField("storage").apply { isAccessible = true }.get(service) as CoroutineScope
            lateinit var saved: QueueState
            ins.runOnMainSync {
                saved = state.get(service) as QueueState
                state.set(service, QueueState()); loaded.setBoolean(service, false); persist.invoke(service)
            }
            try {
                storage.launch { }.join()
                assertEquals(before.entries, root.get("queue", "active", QueueState::class.java)!!.entries)
                // A real empty queue after restoration remains a valid change to save.
                ins.runOnMainSync { loaded.setBoolean(service, true); persist.invoke(service) }
                storage.launch { }.join()
                assertTrue(root.get("queue", "active", QueueState::class.java)!!.entries.isEmpty())
            } finally {
                ins.runOnMainSync { state.set(service, saved); loaded.setBoolean(service, true); persist.invoke(service) }
                storage.launch { }.join()
            }
        } }
    }

    @Test(timeout = 90000) fun verificationErrorsStayInEditorAndLateResponseCannotOverwriteNewInput() = runBlocking {
        MediaPipelineTest.MediaServer().use { server -> fixture(server) { vm ->
            val p = vm.repo.session!!.profile; val auth = root.vault.authentication(p.id)
            action { vm.start(server.tracks) }; waitFor { PlaybackHub.state.value.playing }
            ins.runOnMainSync { vm.openServerEditor(p); vm.editServerDraft(draft(p).copy(password = "wrong-password")) }
            EditorTouch.click("测试连接")
            waitFor { vm.serverEditor.value?.busy == false }
            EditorTouch.node("账户或密码不正确，或此账户没有访问权限")
            assertEquals("wrong-password", vm.serverEditor.value!!.draft.password)
            assertEquals(auth, root.vault.authentication(p.id)); assertEquals(p, root.profiles.active()!!.profile)
            assertTrue(PlaybackHub.state.value.playing)
            // Focus opens the keyboard; error and primary actions remain in the same window.
            EditorTouch.nodes().first { it.className?.toString() == "android.widget.EditText" }.let(EditorTouch::tap)
            EditorTouch.node("账户或密码不正确，或此账户没有访问权限"); EditorTouch.node("保存")
            EditorTouch.shot("01-inline-auth-error")
            server.pingDelay = 1200
            ins.runOnMainSync { vm.editServerDraft(draft(p).copy(password = "fixture-password")); vm.submitServerEditor(testOnly = true) }
            delay(100)
            ins.runOnMainSync { vm.editServerDraft(vm.serverEditor.value!!.draft.copy(name = "修改后的名称")) }
            delay(1500)
            assertNull(vm.serverEditor.value!!.message); assertFalse(vm.serverEditor.value!!.success)
            assertEquals("修改后的名称", vm.serverEditor.value!!.draft.name)
            server.pingDelay = 0
            action { vm.submitServerEditor(testOnly = true) }
            assertTrue(vm.serverEditor.value!!.success); assertEquals(p, root.profiles.active()!!.profile)
            EditorTouch.shot("02-connection-test")
            ins.runOnMainSync { vm.closeServerEditor() }; assertNull(vm.serverEditor.value)
        } }
    }

    @Test(timeout = 120000) fun addressEditKeepsNamespaceDownloadsQueueAndNeverRescansOrAutoplays() = runBlocking {
        MediaPipelineTest.MediaServer().use { old -> MediaPipelineTest.MediaServer(supplied = old.tracks).use { next -> fixture(old) { vm ->
            val p = vm.repo.session!!.profile; val beforeGeneration = vm.repo.session!!.generation
            root.put("search-history", "kept", SearchHistory("生成数据", 1))
            Preferences.setDownloadWifiOnly(false)
            action { vm.start(old.tracks) }; waitFor { PlaybackHub.state.value.queue.positionMs > 1000 }
            action { vm.download(old.tracks.take(1)) }
            ins.runOnMainSync { DownloadUtil.getDownloadManager(context).requirements = Requirements(0) }
            waitFor { AccountDownloads.downloaded(context, p.id, old.tracks[0].id) }
            val queueKey = PlaybackHub.state.value.queue.current!!.key
            val changed = draft(p).copy(address = "http://localhost:${next.port}", password = "fixture-password", preserve = true, confirmedSameLibrary = true)
            ins.runOnMainSync { vm.openServerEditor(p); vm.editServerDraft(changed) }
            action { vm.submitServerEditor() }
            assertNull(vm.serverEditor.value)
            assertEquals(p.id, vm.repo.account); assertEquals(changed.address, vm.repo.server)
            assertTrue(vm.repo.session!!.generation > beforeGeneration)
            assertNotNull(vm.repo.get("search-history", "kept", SearchHistory::class.java))
            assertTrue(AccountDownloads.downloaded(context, p.id, old.tracks[0].id))
            assertEquals(queueKey, vm.repo.get("queue", "active", QueueState::class.java)!!.current!!.key)
            assertFalse(PlaybackHub.state.value.playing)
            assertEquals(0, old.searches.get()); assertEquals(0, next.searches.get())
            val oldCalls = old.requests.get()
            old.online = false
            action { vm.command("toggle"); null }
            waitFor { PlaybackHub.state.value.playing }
            delay(300); assertEquals(oldCalls, old.requests.get())
            // Name-only edit works offline and must not interrupt the now playing download.
            val updated = vm.repo.session!!.profile; next.online = false
            ins.runOnMainSync { vm.openServerEditor(updated); vm.editServerDraft(draft(updated).copy(name = "改名后的服务器")) }
            action { vm.submitServerEditor() }
            assertEquals("改名后的服务器", root.profiles.find(p.id)!!.name)
            assertTrue(PlaybackHub.state.value.playing)
        } } }
    }

    @Test(timeout = 90000) fun mismatchedLibraryIsBlockedAndAnotherAccountGetsIndependentStorage() = runBlocking {
        MediaPipelineTest.MediaServer().use { old -> MediaPipelineTest.MediaServer(supplied = old.tracks.map { it.copy(title = "另一首录音") }, allowedUsers = setOf("fixture", "second")).use { next -> fixture(old) { vm ->
            val p = vm.repo.session!!.profile
            val changed = draft(p).copy(address = "http://localhost:${next.port}", password = "fixture-password", preserve = true, confirmedSameLibrary = true)
            ins.runOnMainSync { vm.openServerEditor(p); vm.editServerDraft(changed) }
            action { vm.submitServerEditor() }
            assertTrue(vm.serverEditor.value!!.errors.containsKey("identity")); assertEquals(p, root.profiles.active()!!.profile)
            old.downloadChunkDelay = 150
            Preferences.setDownloadWifiOnly(false)
            action { vm.start(old.tracks) }; waitFor { PlaybackHub.state.value.playing }
            action { vm.download(old.tracks.take(1)) }
            ins.runOnMainSync { DownloadUtil.getDownloadManager(context).requirements = Requirements(0) }
            waitFor { DownloadUtil.getDownloadManager(context).currentDownloads.any { it.state == androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING } }
            ins.runOnMainSync { vm.editServerDraft(changed.copy(preserve = false, username = "second", name = "独立连接")) }
            action { vm.submitServerEditor() }
            assertNull(vm.serverEditor.value); assertEquals(p, root.profiles.active()!!.profile)
            assertTrue(PlaybackHub.state.value.playing)
            assertFalse("Saving another connection must not pause the active account's scheduler", DownloadUtil.getDownloadManager(context).downloadsPaused)
            val created = root.profiles.all().single { it.address == changed.address && it.username == "second" }
            assertNotEquals(p.id, created.id); assertNull(root.dao.get(created.id, "library", "snapshot"))
            ins.runOnMainSync { vm.openServerEditor(); vm.editServerDraft(ServerDraft(address = created.address + "/", username = "second", password = "fixture-password")) }
            action { vm.submitServerEditor() }
            assertTrue(vm.serverEditor.value!!.errors.containsKey("address"))
            EditorTouch.shot("03-duplicate-connection")
        } } }
    }

    @Test(timeout = 60000) fun unverifiedIdentityRequiresExplicitChoiceAndLogoutRequiresNewPassword() = runBlocking {
        MediaPipelineTest.MediaServer().use { old -> MediaPipelineTest.MediaServer(supplied = old.tracks).use { next ->
            root.login("http://localhost:${old.port}", "fixture", "fixture-password")
            root.saveLibrary(Library(old.tracks, syncedAt = System.currentTimeMillis()))
            val p = root.profiles.active()!!.profile
            val changed = draft(p).copy(address = "http://localhost:${next.port}", password = "fixture-password", preserve = true, confirmedSameLibrary = true)
            next.missingSong = true
            assertTrue(runCatching { root.prepareServer(changed) }.exceptionOrNull() is LibraryIdentityUnknown)
            assertEquals(p.id, root.prepareServer(changed.copy(acceptUnverified = true)).profile.id)
            assertEquals(p, root.profiles.active()!!.profile)
            root.profiles.logout(p.id)
            assertTrue(runCatching { root.prepareServer(draft(p)) }.exceptionOrNull() is ServerFieldError)
            val relogin = root.prepareServer(draft(p).copy(password = "fixture-password"))
            assertEquals(p.id, relogin.profile.id); assertTrue(relogin.profile.authenticated)
            root.profiles.commitConnection(relogin.profile, relogin.token!!, relogin.salt!!, true)
        } }
    }

    @Test(timeout = 60000) fun encryptedCommitReplaysIdempotentlyAtEachPersistenceBoundary() = runBlocking {
        MediaPipelineTest.MediaServer().use { server ->
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            val p = root.profiles.active()!!.profile
            for (stage in 0..2) {
                val target = p.copy(name = "恢复记录 $stage")
                val auth = root.vault.authentication(p.id)!!
                val generation = root.profiles.active()!!.generation + 1
                val journal = root.gson.toJson(mapOf("profile" to target, "token" to auth.first, "salt" to auth.second, "activate" to true, "generation" to generation))
                root.vault.put("server-connection-commit-v1", journal)
                if (stage >= 1) root.vault.putAll(mapOf("${p.id}.token" to auth.first, "${p.id}.salt" to auth.second))
                if (stage >= 2) { root.profiles.upsert(target); root.settings.edit().putLong("session-generation", generation).commit() }
                val recovered = ServerProfiles(context).active()!!
                assertEquals(target, recovered.profile); assertEquals(generation, recovered.generation)
                assertNull(root.vault.get("server-connection-commit-v1"))
                assertEquals(recovered, ServerProfiles(context).active())
                assertTrue(root.testConnection().startsWith("已连接"))
            }
        }
    }
}
