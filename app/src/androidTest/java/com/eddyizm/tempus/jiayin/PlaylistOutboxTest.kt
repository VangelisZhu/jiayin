package com.eddyizm.tempus.jiayin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class PlaylistOutboxTest {
    @Test fun lostCreateReplyIsReconciledWithoutCreatingDuplicate() = runBlocking {
        PlaylistServer(true).use { server ->
            val root = JiayinRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            val repo = root.scoped()
            repo.setPlaylist(null, "示例歌单", listOf("one", "two"))
            repo.syncPending()
            assertEquals(1, server.creates)
            assertEquals(1, repo.list("outbox", PendingOperation::class.java).size)
            repo.syncPending()
            assertEquals(1, server.creates)
            assertTrue(repo.list("outbox", PendingOperation::class.java).isEmpty())
            assertTrue(repo.list("outbox-create-attempt", Boolean::class.java).isEmpty())
            assertEquals("remote", repo.library().playlists.single().id)
            assertFalse(repo.library().playlists.single().pending)
        }
    }
    @Test fun unconfirmedCreateWaitsForExplicitRetryRatherThanBlindlyReplaying() = runBlocking {
        PlaylistServer(false).use { server ->
            val root = JiayinRepository.get(InstrumentationRegistry.getInstrumentation().targetContext)
            root.login("http://localhost:${server.port}", "fixture", "fixture-password")
            val repo = root.scoped()
            repo.setPlaylist(null, "示例歌单", listOf("one", "two"))
            repo.syncPending(); repo.syncPending()
            assertEquals(1, server.creates)
            assertEquals(1, repo.list("outbox", PendingOperation::class.java).size)
            repo.dao.clear(repo.account, "outbox-create-attempt") // User confirmed no server playlist exists.
            repo.syncPending()
            assertEquals(2, server.creates)
            assertTrue(repo.list("outbox", PendingOperation::class.java).isEmpty())
        }
    }
    private class PlaylistServer(private val acceptedFirst: Boolean) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port get() = socket.localPort
        var creates = 0
        private var exists = false
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) try { socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                val request = reader.readLine(); var length = 0
                while (true) { val header = reader.readLine(); if (header.isNullOrBlank()) break; if (header.startsWith("Content-Length:", true)) length = header.substringAfter(":").trim().toInt() }
                val body = CharArray(length); var read = 0
                while (read < length) { val n = reader.read(body, read, length - read); if (n < 0) break; read += n }
                val result = mutableMapOf<String, Any>("status" to "ok")
                var status = "200 OK"
                when {
                    request.contains("/createPlaylist") -> {
                        creates++
                        if (creates == 1) { exists = acceptedFirst; status = "503 Unavailable" }
                        else { exists = true; result["playlist"] = mapOf("id" to "remote") }
                    }
                    request.contains("/getPlaylists") -> result["playlists"] = mapOf("playlist" to if (exists) listOf(mapOf("id" to "remote", "name" to "示例歌单")) else emptyList())
                    request.contains("/getPlaylist") -> result["playlist"] = mapOf("entry" to listOf(mapOf("id" to "one"), mapOf("id" to "two")))
                }
                val bytes = Gson().toJson(mapOf("subsonic-response" to result)).toByteArray()
                client.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
            } } catch (e: SocketException) { if (!socket.isClosed) throw e }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }
}
