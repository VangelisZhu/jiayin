@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.PlaybackHub
import com.eddyizm.tempus.jiayin.ui.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ManagerCorrectionsTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    class Manager(var server: String) : AutoCloseable {
        val socket = ServerSocket(0)
        val address = "http://localhost:${socket.localPort}"
        val calls = CopyOnWriteArrayList<String>()
        var pauseMe = false
        init { thread(isDaemon = true) {
            while (!socket.isClosed) try {
                val client = socket.accept()
                thread(isDaemon = true) { client.use {
                    val input = it.getInputStream().bufferedReader()
                    val line = input.readLine() ?: return@use
                    val path = line.split(' ')[1]
                    val headers = mutableMapOf<String,String>()
                    while (true) { val h = input.readLine(); if (h.isNullOrEmpty()) break; headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim() }
                    val count = headers["content-length"]?.toIntOrNull() ?: 0
                    if (count > 0) { val body = CharArray(count); var n = 0; while (n < count) { val read = input.read(body,n,count-n); if (read < 0) break; n += read } }
                    calls.add(path)
                    if (pauseMe && path.endsWith("/me")) Thread.sleep(800)
                    val member = JsonObject().apply { addProperty("approved",true);addProperty("role","request");addProperty("username","fixture") }
                    val value = JsonObject().apply {
                        when {
                            path.endsWith("/pair") -> { addProperty("token","generated-device-token");addProperty("server",server);add("member",member) }
                            path.endsWith("/me") -> add("member",member)
                            path.endsWith("/preview") -> { addProperty("id","generated-request");addProperty("status","draft") }
                            path.endsWith("/submit") -> addProperty("status","pending")
                            path.contains("/catalog") -> { add("tracks",JsonArray());addProperty("cursor",0);addProperty("has_more",false) }
                        }
                    }
                    val raw = value.toString().toByteArray()
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${raw.size}\r\nConnection: close\r\n\r\n".toByteArray()+raw)
                } }
            } catch (_: Exception) { }
        } }
        override fun close() { socket.close() }
    }

    @Test fun submitDoesNotChangePlaybackQueueAndOldSessionResultsAreRejected() = runBlocking {
        val root = JiayinRepository.get(context)
        MediaPipelineTest.MediaServer(catalog = true).use { music ->
            root.login("http://localhost:${music.port}","fixture","fixture-password")
            val repo = root.scoped()
            Manager(repo.server).use { backend ->
                val client = ManagerCorrections(repo)
                client.pair(backend.address)
                val queue = PlaybackHub.state.value.queue
                client.request("corrections/preview",JsonObject().apply { add("ids",JsonArray().apply { add("one") });add("values",JsonObject().apply { addProperty("title","Generated correction") }) })
                assertEquals("pending",client.request("corrections/generated-request/submit",JsonObject()).asJsonObject.str("status"))
                assertEquals(queue,PlaybackHub.state.value.queue)
                backend.pauseMe = true
                val pending = async(Dispatchers.IO) { runCatching { client.request("me") } }
                delay(150)
                root.profiles.activate(null)
                assertTrue(pending.await().isFailure)
                assertTrue(backend.calls.any { it.endsWith("/submit") })
                root.profiles.logout(repo.account)
                assertNull(repo.vault.get("${repo.account}.manager-member"))
            }
        }
    }

    @Test fun companionIdentityMismatchNeverStoresWriteCredential() = runBlocking {
        val root = JiayinRepository.get(context)
        MediaPipelineTest.MediaServer().use { music ->
            root.login("http://localhost:${music.port}","fixture","fixture-password")
            val repo = root.scoped()
            Manager("https://other.example").use { backend ->
                assertTrue(runCatching { ManagerCorrections(repo).pair(backend.address) }.isFailure)
                assertNull(repo.vault.get("${repo.account}.manager-member"))
            }
        }
    }

    @Test fun correctionPageShowsIndependentPermissionsAndManualEntry() = runBlocking {
        val root = JiayinRepository.get(context)
        MediaPipelineTest.MediaServer(catalog = true).use { music ->
            root.login("http://localhost:${music.port}","fixture","fixture-password")
            val repo = root.scoped()
            Manager(repo.server).use { backend ->
                ManagerCorrections(repo).pair(backend.address)
                val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context,JiayinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                try {
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[JiayinViewModel::class.java]
                        activity.setContent { androidx.compose.material3.MaterialTheme { CorrectionPage(vm,CorrectionTarget(listOf(Track("one","Generated title")))) {} } }
                    }
                    withTimeout(15000) {
                        while (true) {
                            val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                            fun visit(n: android.view.accessibility.AccessibilityNodeInfo) { nodes.add(n);for(i in 0 until n.childCount)n.getChild(i)?.let { visit(it) } }
                            ins.uiAutomation.rootInActiveWindow?.let { visit(it) }
                            val words = nodes.mapNotNull { it.text?.toString() }
                            if (words.any { it.contains("提交修改申请") } && "手动纠正" in words && "检索 QQ / 网易云" in words) break
                            delay(100)
                        }
                    }
                } finally { scenario.close() }
            }
        }
    }
}
