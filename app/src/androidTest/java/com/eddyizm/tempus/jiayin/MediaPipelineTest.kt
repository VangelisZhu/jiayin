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
    @org.junit.Before fun grantFixtureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val ins = InstrumentationRegistry.getInstrumentation()
            ins.uiAutomation.grantRuntimePermission(ins.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
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
                        DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(com.eddyizm.tempus.jiayin.data.AccountDownloads.requestFor(context, repo.account, tracks[0].id)!!.requestId)?.let { it.state == Download.STATE_COMPLETED && it.bytesDownloaded > 0 } == true
                    }
                } catch (e: Exception) {
                    val manager = DownloadUtil.getDownloadManager(context)
                    val download = manager.downloadIndex.getDownload(com.eddyizm.tempus.jiayin.data.AccountDownloads.requestFor(context, repo.account, tracks[0].id)!!.requestId)
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

    internal class MediaServer(count: Int = 2, catalog: Boolean = false, supplied: List<Track>? = null, val username: String = "fixture", frequency: Int = 440, coverColor: Int = 0xff3b7777.toInt(), allowedUsers: Set<String> = setOf(username), sampleRate: Int = 16000, bits: Int = 16, channels: Int = 1) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port get() = socket.localPort
        var online = true
        var searchDelay = 0L
        var downloadChunkDelay = 0L
        val requests = AtomicInteger()
        val searches = AtomicInteger()
        var lyrics: Lyrics? = null
        var jsonCharset: String? = null
        val offsets = java.util.concurrent.CopyOnWriteArrayList<Int>()
        var streamDelay = 0L
        val streams = AtomicInteger(); val images = AtomicInteger(); val downloads = AtomicInteger()
        private val scope = UUID.randomUUID().toString()
        val tracks = supplied ?: (1..count).map { Track("pipeline-$scope-$it", "播放测试 $it", if (catalog) "示例歌手 ${it % 3}" else "家音测试", album = if (catalog) "专辑 ${it % 2}" else "", albumId = if (catalog) "album-${it % 2}" else "", artistId = if (catalog) "artist-${it % 3}" else "", duration = 30, genre = if (catalog) "风格 ${it % 2}" else "", releaseDate = if (catalog) 20200101L + it * 10000 else 0) }
        var rejectMetadata = false
        val starCalls = AtomicInteger()
        private val audio: ByteArray = ByteBuffer.allocate(44 + sampleRate * (bits / 8) * channels * 30).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(sampleRate * (bits / 8) * channels); putShort(((bits / 8) * channels).toShort()); putShort(bits.toShort()); put("data".toByteArray()); putInt(capacity() - 44)
            repeat(sampleRate * 30) { n -> repeat(channels) {
                val value = (sin(2 * Math.PI * frequency * n / sampleRate) * if (bits == 24) 512000 else 2000).toInt()
                if (bits == 24) { put(value.toByte()); put((value shr 8).toByte()); put((value shr 16).toByte()) } else putShort(value.toShort())
            } }
        }.array()
        private val png = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(coverColor); compress(Bitmap.CompressFormat.PNG, 100, output); recycle() }
            output.toByteArray()
        }
        private val worker = thread(isDaemon = true, name = "media-pipeline-fixture") {
            while (!socket.isClosed) try { val client = socket.accept()
                thread(isDaemon = true) { client.use {
                    val reader = it.getInputStream().bufferedReader(); val request = reader.readLine() ?: return@use
                    var length = 0; var range = 0
                    while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        if (line.startsWith("Range:", true)) range = line.substringAfter("bytes=").substringBefore('-').toIntOrNull() ?: 0 }
                    val body = CharArray(length); var offset = 0
                    while (offset < length) { val n = reader.read(body, offset, length - offset); if (n < 0) break; offset += n }
                    val auth = request + String(body)
                    requests.incrementAndGet()
                    val parameters = (request.substringAfter("?", "").substringBefore(" HTTP/") + "&" + String(body)).split("&").filter { it.contains("=") }.associate {
                        java.net.URLDecoder.decode(it.substringBefore("="), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter("="), "UTF-8")
                    }
                    val expected = java.security.MessageDigest.getInstance("MD5").digest(("fixture-password" + parameters["s"]).toByteArray()).joinToString("") { "%02x".format(it) }
                    val authenticated = parameters["u"] in allowedUsers && parameters["t"] == expected
                    if (request.contains("/search3") && searchDelay > 0) Thread.sleep(searchDelay)
                    var type = "application/json" + (jsonCharset?.let { "; charset=$it" } ?: "")
                    val result: ByteArray = if (!online || !authenticated) "unavailable".toByteArray() else when {
                        request.contains("/getCoverArt") -> { images.incrementAndGet(); type = "image/png"; png }
                        request.contains("/stream") -> {
                            streams.incrementAndGet(); type = "audio/wav"
                            val seconds = (parameters["timeOffset"]?.toIntOrNull() ?: 0).coerceIn(0, 29)
                            offsets.add(seconds)
                            if (streamDelay > 0) Thread.sleep(streamDelay)
                            if (seconds == 0) audio else {
                                val skip = seconds * sampleRate * (bits / 8) * channels
                                val remaining = audio.size - 44 - skip
                                ByteBuffer.allocate(44 + remaining).order(ByteOrder.LITTLE_ENDIAN).apply {
                                    put(audio, 0, 44); putInt(4, 36 + remaining); putInt(40, remaining); put(audio, 44 + skip, remaining)
                                }.array()
                            }
                        }
                        request.contains("/download") -> { downloads.incrementAndGet(); type = "audio/wav"; audio }
                        else -> Gson().toJson(mapOf("subsonic-response" to when {
                            request.contains("/search3") -> {
                                searches.incrementAndGet()
                                val offset = Regex("songOffset=(\\d+)").find(String(body))?.groupValues?.get(1)?.toInt() ?: 0
                                mapOf("status" to "ok", "searchResult3" to mapOf("song" to tracks.drop(offset).take(500).map { t -> Gson().toJsonTree(t).asJsonObject.apply { addProperty("coverArt", t.cover); if (t.releaseDate > 0) addProperty("year", t.releaseDate / 10000) } }))
                            }
                            request.contains("/getLyricsBySongId") -> mapOf("status" to "ok", "lyricsList" to mapOf("structuredLyrics" to listOf(mapOf("lang" to "kor", "kind" to "original", "line" to lyrics?.lines.orEmpty().map { mapOf("start" to it.timeMs, "value" to it.original) }))))
                            request.contains("/getArtists") && !rejectMetadata -> mapOf("status" to "ok", "artists" to mapOf("index" to listOf(mapOf("artist" to tracks.distinctBy { it.artistId }.filter { it.artistId.isNotBlank() }.map { mapOf("id" to it.artistId, "name" to it.artist) }))))
                            request.contains("/getTopSongs") && !rejectMetadata -> mapOf("status" to "ok", "topSongs" to mapOf("song" to tracks.reversed()))
                            request.contains("/star") || request.contains("/unstar") -> { starCalls.incrementAndGet(); mapOf("status" to "ok") }
                            else -> mapOf("status" to "ok")
                        })).toByteArray()
                    }
                    val start = range.coerceIn(0, result.size)
                    val status = if (!online) "503 Unavailable" else if (!authenticated) "401 Unauthorized" else if (start > 0) "206 Partial Content" else "200 OK"
                    val contentRange = if (start > 0) "Content-Range: bytes $start-${result.size - 1}/${result.size}\r\n" else ""
                    try { it.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${result.size - start}\r\nAccept-Ranges: bytes\r\n${contentRange}Connection: close\r\n\r\n".toByteArray()); if (request.contains("/download") && downloadChunkDelay > 0) {
                        var cursor = start
                        while (cursor < result.size) { val n = (result.size - cursor).coerceAtMost(8192); write(result, cursor, n); flush(); cursor += n; Thread.sleep(downloadChunkDelay) }
                    } else { write(result, start, result.size - start); flush() } } } catch (_: java.io.IOException) { }
                } }
            } catch (e: SocketException) { if (!socket.isClosed) throw e }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }
}
