package com.eddyizm.tempus.jiayin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class PlaylistMutationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun setup(server: Server, username: String = "fixture"): JiayinRepository {
        val root = JiayinRepository.get(context)
        root.login("http://localhost:${server.port}", username, "fixture-password")
        return root.scoped().also { it.saveLibrary(Library(playlists = listOf(MusicList("p", "Generated", server.ids.toList(), "cover"), MusicList("other", "Other")))) }
    }
    @Test fun concurrentAddIsAtomicAndOrderedAndMetadataPreserved() = runBlocking {
        Server().use { server ->
            val repo = setup(server)
            val results = (1..24).map { async(Dispatchers.IO) { repo.addTrackToPlaylist("p", "new") } }.awaitAll()
            assertEquals(1, results.count { it == PlaylistEditResult.SAVED })
            assertEquals(23, results.count { it == PlaylistEditResult.ALREADY_PRESENT })
            assertEquals(listOf("p", "other"), repo.library().playlists.map { it.id })
            assertEquals("cover", repo.library().playlists.first().cover)
            repo.syncPending()
            assertEquals(listOf("a", "b", "a", "new"), server.ids)
            assertEquals(1, server.writes)
            assertFalse(repo.library().playlists.first().pending)
            assertTrue(repo.list("outbox", PendingOperation::class.java).isEmpty())
        }
    }
    @Test fun lostRemovalAndAddRepliesNeverReplayAppliedEdits() = runBlocking {
        Server().use { server ->
            val repo = setup(server)
            repo.removePlaylistEntry(repo.library().playlists.first().entryRef(2))
            server.loseReply = true; repo.syncPending()
            assertEquals(listOf("a", "b"), server.ids)
            assertNotNull(repo.library().playlists.first().syncError)
            repo.syncPending(); assertEquals(1, server.writes)
            repo.addTrackToPlaylist("p", "new")
            server.loseReply = true; repo.syncPending(); repo.syncPending()
            assertEquals(2, server.writes)
            assertEquals(listOf("a", "b", "new"), repo.library().playlists.first().tracks)
            assertFalse(repo.library().playlists.first().pending)
        }
    }
    @Test fun changedOrderStopsRemovalAndFollowingAddsUntilExplicitReload() = runBlocking {
        Server().use { server ->
            val repo = setup(server)
            repo.removePlaylistEntry(repo.library().playlists.first().entryRef(0))
            repo.addTrackToPlaylist("p", "new")
            server.ids = listOf("b", "a", "a")
            repo.syncPending()
            assertEquals(0, server.writes)
            assertEquals(2, repo.list("outbox", PendingOperation::class.java).size)
            assertNotNull(repo.library().playlists.first().syncError)
            repo.reloadPlaylist("p")
            assertEquals(server.ids, repo.library().playlists.first().tracks)
            assertTrue(repo.list("outbox", PendingOperation::class.java).isEmpty())
        }
    }
    @Test fun localConflictReadOnlyMissingAndAccountBoundary() = runBlocking {
        Server().use { server ->
            val repo = setup(server)
            val ref = repo.library().playlists.first().entryRef(1)
            repo.addTrackToPlaylist("p", "new")
            assertEquals(PlaylistEditResult.CHANGED, repo.removePlaylistEntry(ref))
            assertEquals(PlaylistEditResult.MISSING, repo.addTrackToPlaylist("gone", "new"))
            repo.saveLibrary(repo.library().let { it.copy(playlists = it.playlists.map { p -> p.copy(readOnly = true) }) })
            assertEquals(PlaylistEditResult.READ_ONLY, repo.addTrackToPlaylist("p", "z"))
            val other = setup(server, "second")
            assertEquals(PlaylistEditResult.SAVED, other.addTrackToPlaylist("p", "new"))
            assertTrue(runCatching { repo.addTrackToPlaylist("p", "z") }.isFailure)
        }
    }
    @Test fun offlineRemoveReaddPersistsAndSyncsInOrder() = runBlocking {
        Server().use { server ->
            val repo = setup(server)
            server.online = false
            repo.removePlaylistEntry(repo.library().playlists.first().entryRef(1))
            repo.addTrackToPlaylist("p", "b")
            repo.syncPending()
            assertEquals(listOf("a", "a", "b"), repo.library().playlists.first().tracks)
            val persisted = JiayinRepository(context).scoped()
            server.online = true; persisted.syncPending()
            assertEquals(listOf("a", "a", "b"), server.ids)
            assertFalse(persisted.library().playlists.first().pending)
        }
    }
    @Test fun outboxWriteFailureRollsBackPlaylistSnapshot() = runBlocking {
        Server().use { server ->
            val repo = setup(server)
            val db = LocalDatabase.get(context).openHelper.writableDatabase
            db.execSQL("CREATE TEMP TRIGGER fixture_outbox_failure BEFORE INSERT ON local_rows WHEN NEW.kind='outbox' BEGIN SELECT RAISE(ABORT, 'generated write failure'); END")
            try {
                assertTrue(runCatching { repo.addTrackToPlaylist("p", "new") }.isFailure)
                assertEquals(server.ids, repo.library().playlists.first().tracks)
                assertTrue(repo.list("outbox", PendingOperation::class.java).isEmpty())
            } finally { db.execSQL("DROP TRIGGER fixture_outbox_failure") }
        }
    }
    @Test fun localPlaylistMappingPreservesPendingEditOrder() = runBlocking {
        Server().use { server ->
            val repo = setup(server)
            val p = repo.setPlaylist(null, "New", listOf("x", "y"))
            repo.addTrackToPlaylist(p.id, "z")
            repo.removePlaylistEntry(repo.library().playlists.last().entryRef(0))
            repo.syncPending()
            assertEquals(listOf("y", "z"), server.createdIds)
            assertEquals(listOf("y", "z"), repo.library().playlists.last().tracks)
            assertFalse(repo.library().playlists.last().pending)
            assertTrue(repo.list("outbox", PendingOperation::class.java).isEmpty())
        }
    }
    internal class Server : AutoCloseable {
        private val socket = ServerSocket(0)
        val port get() = socket.localPort
        @Volatile var ids = listOf("a", "b", "a")
        @Volatile var createdIds = emptyList<String>()
        @Volatile var writes = 0
        @Volatile var loseReply = false
        @Volatile var online = true
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) try { socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader(); val request = reader.readLine(); var length = 0
                while (true) { val line = reader.readLine(); if (line.isNullOrBlank()) break; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
                val body = CharArray(length); var offset = 0
                while (offset < length) { val n = reader.read(body, offset, length - offset); if (n < 0) break; offset += n }
                val params = String(body).split('&').filter { '=' in it }.groupBy({ URLDecoder.decode(it.substringBefore('='), "UTF-8") }, { URLDecoder.decode(it.substringAfter('='), "UTF-8") })
                val result = mutableMapOf<String, Any>("status" to "ok")
                var status = if (online) "200 OK" else "503 Unavailable"
                val created = params["id"]?.first() == "created" || params["playlistId"]?.first() == "created"
                if (online) when {
                    request.contains("/getPlaylist.") -> result["playlist"] = mapOf("id" to if (created) "created" else "p", "name" to "Generated", "entry" to (if (created) createdIds else ids).map { mapOf("id" to it) })
                    request.contains("/createPlaylist") -> { createdIds = params["songId"].orEmpty(); result["playlist"] = mapOf("id" to "created") }
                    request.contains("/updatePlaylist") -> {
                        writes++
                        var next = if (created) createdIds else ids
                        params["songIndexToRemove"]?.firstOrNull()?.toInt()?.let { index -> next = next.filterIndexed { i, _ -> i != index } }
                        next = next + params["songIdToAdd"].orEmpty()
                        if (created) createdIds = next else ids = next
                        if (loseReply) { loseReply = false; status = "503 Unavailable" }
                    }
                }
                val bytes = Gson().toJson(mapOf("subsonic-response" to result)).toByteArray()
                client.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
            } } catch (e: SocketException) { if (!socket.isClosed) throw e }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }
}
