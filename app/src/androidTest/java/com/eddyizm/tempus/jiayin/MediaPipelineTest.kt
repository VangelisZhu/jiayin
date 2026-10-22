package com.eddyizm.tempus.jiayin

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.jiayin.playback.*
import com.eddyizm.tempus.jiayin.ui.Covers
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.Preferences
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.sin

/** Real Media3 service/decoder/download and HTTP image requests, with non-private generated media. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 90000) fun playPauseNextDownloadAndOfflinePlaybackThroughRealService() = runBlocking {
        val repo = JiayinRepository.get(context)
        val component = ComponentName(context, JiayinMediaService::class.java)
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        MediaServer().use { server ->
            repo.login("http://localhost:${server.port}", "fixture", "fixture-password")
            Preferences.setDownloadWifiOnly(false)
            val tracks = server.tracks
            repo.saveLibrary(Library(tracks))
            repo.dao.delete(repo.account, "queue", "active")
            var model: JiayinViewModel? = null
            var future: ListenableFuture<MediaController>? = null
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java))
            try {
                scenario.onActivity { model = ViewModelProvider(it)[JiayinViewModel::class.java] }
                instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, component)).buildAsync() }
                val controller = future!!.get(12, TimeUnit.SECONDS)
                instrumentation.runOnMainSync { assertTrue("App playback command unavailable", controller.isSessionCommandAvailable(SessionCommand(PlaybackHub.COMMAND, Bundle.EMPTY))) }
                instrumentation.runOnMainSync { model!!.start(tracks, source = "播放全部") }
                await("first song actually playing") { PlaybackHub.state.value.current?.id == tracks[0].id && PlaybackHub.state.value.playing }
                val first = PlaybackHub.state.value.queue.positionMs
                await("decoded playback position advances") { PlaybackHub.state.value.queue.positionMs > first + 1000 }
                assertTrue("No authenticated stream request reached server", server.streams.get() > 0)
                instrumentation.runOnMainSync { model!!.command("pause") }
                await("user pause") { !PlaybackHub.state.value.playing && PlaybackHub.state.value.cause == PauseCause.USER }
                instrumentation.runOnMainSync { model!!.command("next") }
                await("next song") { PlaybackHub.state.value.current?.id == tracks[1].id && PlaybackHub.state.value.playing }
                var downloadJob: Job? = null
                instrumentation.runOnMainSync { downloadJob = model!!.download(listOf(tracks[0])) }
                // Loopback does not need validated internet; leave the production network policy intact.
                instrumentation.runOnMainSync { DownloadUtil.getDownloadManager(context).requirements = Requirements(0) }
                downloadJob!!.join()
                val downloadMessage = model!!.state.value.message
                try {
                    await("download bytes persisted ($downloadMessage)", 12000) {
                        DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(tracks[0].id)?.let { it.state == Download.STATE_COMPLETED && it.bytesDownloaded > 0 } == true
                    }
                } catch (e: Exception) {
                    val manager = DownloadUtil.getDownloadManager(context)
                    val download = manager.downloadIndex.getDownload(tracks[0].id)
                    error("${e.message}; paused=${manager.downloadsPaused}; requirements=${manager.notMetRequirements}; state=${download?.state}; failure=${download?.failureReason}; requests=${server.downloads.get()}")
                }
                assertTrue("No download endpoint reached", server.downloads.get() > 0)
                instrumentation.runOnMainSync { model!!.command("pause") }
                server.online = false
                instrumentation.runOnMainSync { model!!.start(listOf(tracks[0]), source = "离线播放") }
                await("download plays with server unavailable") { PlaybackHub.state.value.current?.id == tracks[0].id && PlaybackHub.state.value.playing && PlaybackHub.state.value.queue.positionMs > 1000 }
            } finally {
                instrumentation.runOnMainSync { model?.command("pause"); future?.let { MediaController.releaseFuture(it) } }
                scenario.close()
                instrumentation.runOnMainSync { context.stopService(Intent(context, JiayinMediaService::class.java)) }
                await("service stopped") { !PlaybackHub.state.value.ready }
            }
        }
    }

    @Test(timeout = 35000) fun eightAuthenticatedCoversLoadConcurrently() = runBlocking {
        val repo = JiayinRepository.get(context)
        MediaServer().use { server ->
            repo.login("http://localhost:${server.port}", "fixture", "fixture-password")
            val images = (0 until 8).map { index -> async(Dispatchers.IO) { Covers.load(context, "pipeline-cover-$index") } }.awaitAll()
            assertEquals("All server images must decode", 8, images.count { it != null })
            assertEquals(8, server.images.get())
        }
    }

    @Test(timeout = 90000) fun fullLibraryCommandWith7509EntriesPlaysAndKeepsItsQueue() = runBlocking {
        val repo = JiayinRepository.get(context)
        val component = ComponentName(context, JiayinMediaService::class.java)
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        MediaServer(7509).use { server ->
            repo.login("http://localhost:${server.port}", "fixture", "fixture-password")
            repo.saveLibrary(Library(server.tracks))
            repo.put("queue", "active", QueueEngine.create(server.tracks.take(100).map { it.id }, "旧的暂停队列", false, server.tracks.associateBy { it.id }))
            val scenario = ActivityScenario.launch<JiayinActivity>(Intent(context, JiayinActivity::class.java))
            var model: JiayinViewModel? = null
            try {
                scenario.onActivity { model = ViewModelProvider(it)[JiayinViewModel::class.java]; model!!.start(server.tracks, source = "7509 首播放全部") }
                await("7509-entry queue starts", 60000) { PlaybackHub.state.value.playing && PlaybackHub.state.value.queue.entries.size == 7509 }
                assertEquals(server.tracks[0].id, PlaybackHub.state.value.current?.id)
                assertTrue(server.streams.get() > 0)
            } finally {
                // Continuous vinyl frames need not reach Instrumentation's idle state.
                instrumentation.runOnMainSync { model?.command("pause") }
                scenario.close()
                instrumentation.runOnMainSync { context.stopService(Intent(context, JiayinMediaService::class.java)) }
                await("service stopped") { !PlaybackHub.state.value.ready }
            }
        }
    }

    private suspend fun await(label: String, timeout: Long = 12000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "$label timed out; current=${PlaybackHub.state.value.current?.id}, playing=${PlaybackHub.state.value.playing}, error=${PlaybackHub.state.value.error}" }
            delay(100)
        }
    }

    private class MediaServer(count: Int = 2) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port get() = socket.localPort
        var online = true
        val streams = AtomicInteger(); val images = AtomicInteger(); val downloads = AtomicInteger()
        private val scope = UUID.randomUUID().toString()
        val tracks = (1..count).map { Track("pipeline-$scope-$it", "播放测试 $it", "家音测试", duration = 30) }
        private val audio: ByteArray = ByteBuffer.allocate(44 + 16000 * 2 * 30).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(16000); putInt(32000); putShort(2); putShort(16); put("data".toByteArray()); putInt(capacity() - 44)
            repeat(16000 * 30) { putShort((sin(2 * Math.PI * 440 * it / 16000) * 2000).toInt().toShort()) }
        }.array()
        private val png = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff3b7777.toInt()); compress(Bitmap.CompressFormat.PNG, 100, output); recycle() }
            output.toByteArray()
        }
        private val worker = thread(isDaemon = true, name = "media-pipeline-fixture") {
            while (!socket.isClosed) try { val client = socket.accept()
                thread(isDaemon = true) { client.use {
                    val reader = it.getInputStream().bufferedReader(); val request = reader.readLine()
                    var length = 0; var range = 0
                    while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        if (line.startsWith("Range:", true)) range = line.substringAfter("bytes=").substringBefore('-').toIntOrNull() ?: 0 }
                    val body = CharArray(length); var offset = 0
                    while (offset < length) { val n = reader.read(body, offset, length - offset); if (n < 0) break; offset += n }
                    val auth = request + String(body)
                    val authenticated = auth.contains("t=") && auth.contains("s=") && auth.contains("u=fixture")
                    var type = "application/json"
                    val result: ByteArray = if (!online || !authenticated) "unavailable".toByteArray() else when {
                        request.contains("/getCoverArt") -> { images.incrementAndGet(); type = "image/png"; png }
                        request.contains("/stream") -> { streams.incrementAndGet(); type = "audio/wav"; audio }
                        request.contains("/download") -> { downloads.incrementAndGet(); type = "audio/wav"; audio }
                        else -> Gson().toJson(mapOf("subsonic-response" to when {
                            request.contains("/search3") -> {
                                val offset = Regex("songOffset=(\\d+)").find(String(body))?.groupValues?.get(1)?.toInt() ?: 0
                                mapOf("status" to "ok", "searchResult3" to mapOf("song" to tracks.drop(offset).take(500)))
                            }
                            else -> mapOf("status" to "ok")
                        })).toByteArray()
                    }
                    val start = range.coerceIn(0, result.size)
                    val status = if (!online || !authenticated) "503 Unavailable" else if (start > 0) "206 Partial Content" else "200 OK"
                    val contentRange = if (start > 0) "Content-Range: bytes $start-${result.size - 1}/${result.size}\r\n" else ""
                    try { it.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${result.size - start}\r\nAccept-Ranges: bytes\r\n${contentRange}Connection: close\r\n\r\n".toByteArray()); write(result, start, result.size - start); flush() } } catch (_: java.io.IOException) { }
                } }
            } catch (e: SocketException) { if (!socket.isClosed) throw e }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }
}
