package com.eddyizm.tempus.jiayin

import android.database.CursorWindow
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.util.UUID
import kotlin.concurrent.thread

/** Exercises Android's actual SQLite/CursorWindow and Room, not a JVM mock. */
@RunWith(AndroidJUnit4::class)
class StorageRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val gson = Gson()
    private fun row(kind: String, payload: String, account: String = "a") = LocalRow().apply {
        this.account = account; this.kind = kind; id = "snapshot"; this.payload = payload; updated = 123456789L
    }

    @Test fun migratesAlreadyOversizedV1RowsWithoutLosingAnyAccountOrDocument() {
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        // Astral characters deliberately straddle chunk boundaries; SQLite substr uses code points.
        val huge = "a".repeat(LocalDao.CHUNK_CHARS - 1) + "🎵中文 / 日本語 / 한국어".repeat(150000)
        val expected = listOf(row("library", huge), row("queue", huge.reversed().reversed()),
            row("outbox", huge, "b"), row("listening", "{\"heardMs\":123}"), row("lyrics", ""))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE local_rows(account TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(account,kind,id))")
            expected.forEach { r -> old.execSQL("INSERT INTO local_rows VALUES(?,?,?,?,?)", arrayOf(r.account, r.kind, r.id, r.payload, r.updated)) }
            old.version = 1
            // Prove this fixture hits the exact original failure on a normal 2 MiB window.
            try {
                (old.rawQuery("SELECT * FROM local_rows WHERE kind='library'", null) as SQLiteCursor).use {
                    it.window = CursorWindow("regression-2MiB", 2L * 1024 * 1024)
                    it.moveToFirst(); it.getString(it.getColumnIndexOrThrow("payload"))
                }
                fail("The legacy oversized row must reproduce SQLiteBlobTooBigException")
            } catch (_: SQLiteBlobTooBigException) { /* expected before migration */ }
        }
        try {
            Room.databaseBuilder(context, LocalDatabase::class.java, name).addMigrations(LocalDatabase.MIGRATION_1_2).build().useDatabase { db ->
                expected.forEach { r ->
                    val actual = db.rows().get(r.account, r.kind, r.id)!!
                    assertEquals(r.payload, actual.payload); assertEquals(r.updated, actual.updated)
                }
                assertBoundedRows(db)
            }
            Room.databaseBuilder(context, LocalDatabase::class.java, name).addMigrations(LocalDatabase.MIGRATION_1_2).build().useDatabase { reopened ->
                assertEquals(huge, reopened.rows().get("a", "library", "snapshot")!!.payload)
                assertEquals(huge, reopened.rows().list("b", "outbox").single().payload)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun chunkReplacementDeletionAndRollbackAreAtomic() {
        Room.inMemoryDatabaseBuilder(context, LocalDatabase::class.java).build().useDatabase { db ->
            val dao = db.rows()
            val huge = "a".repeat(LocalDao.CHUNK_CHARS - 1) + "🎵" + "歌词😀".repeat(300000)
            dao.put(row("queue", huge)); dao.put(row("queue", huge, "b"))
            assertEquals(huge, dao.get("a", "queue", "snapshot")!!.payload)
            assertBoundedRows(db)
            try {
                db.runInTransaction { dao.put(row("queue", "replacement")); throw IllegalStateException("simulated interruption") }
                fail("Expected rollback")
            } catch (_: IllegalStateException) { }
            assertEquals(huge, dao.get("a", "queue", "snapshot")!!.payload)
            dao.put(row("queue", "short"))
            assertEquals("short", dao.get("a", "queue", "snapshot")!!.payload)
            assertEquals(0L, count(db, "SELECT count(*) FROM local_payload_chunks WHERE account='a'"))
            assertEquals(huge, dao.get("b", "queue", "snapshot")!!.payload)
            dao.clear("b", "queue"); dao.delete("a", "queue", "snapshot")
            assertEquals(0L, count(db, "SELECT count(*) FROM local_payload_chunks"))
            assertNull(dao.get("a", "queue", "snapshot"))
        }
    }

    @Test(timeout = 120000) fun sync7509TracksTwiceThenReadFreshRepositoryAndFullQueue() = runBlocking {
        val tracks = (0 until 7509).map { n -> Track("track-$n", "家庭曲库·长歌曲名称 / Evening / 歌詞 $n", "歌手 ${n % 282}",
            album = "专辑 ${n % 1040}", albumId = "album-${n % 1040}", artistId = "artist-${n % 282}",
            cover = "cover-$n", duration = 240, genre = "流行", starred = n % 17 == 0,
            path = "music/" + "中英文测试目录🎵/".repeat(12) + "$n.flac", number = n % 12 + 1) }
        val library = Library(tracks, listOf(MusicList("p1", "我的歌单", tracks.take(1000).map { it.id })))
        assertTrue(gson.toJson(library).toByteArray().size > 2 * 1024 * 1024)
        val repo = JiayinRepository.get(context)
        val prior = repo.settings.all
        val scope = "storage-regression-${UUID.randomUUID()}"
        try {
            ReadOnlyServer(tracks).use { server ->
                repo.settings.edit().putString("account", scope).putString("server", "http://127.0.0.1:${server.port}").putString("user", "fixture").commit()
                repo.vault.put("$scope.token", "fixture-not-a-credential"); repo.vault.put("$scope.salt", "fixture")
                repeat(2) {
                    val synced = repo.synchronize()
                    assertEquals(tracks, synced.tracks)
                    assertEquals(library.playlists.single().tracks, synced.playlists.single().tracks)
                    assertEquals(synced, JiayinRepository(context).library())
                }
                assertEquals(32, server.searchPages)
                // Same storage path also carries large full-library queues and playlist outbox entries.
                val queue = QueueState(entries = (0 until 30000).map { QueueEntry("occurrence-$it", "track-${it % tracks.size}", it) }, index = 7000, positionMs = 12345)
                repo.put("queue", "snapshot", queue)
                assertEquals(queue, repo.get("queue", "snapshot", QueueState::class.java))
                val op = PendingOperation("large", "replacePlaylist", mapOf("songId" to (0 until 100000).map { "track-$it" }))
                repo.put("outbox", "large", op)
                assertEquals(op, repo.list("outbox", PendingOperation::class.java).single())
                assertBoundedRows(LocalDatabase.get(context))
            }
        } finally {
            listOf("library", "queue", "outbox").forEach { repo.dao.clear(scope, it) }
            val editor = repo.settings.edit()
            listOf("account", "server", "user").forEach { key -> editor.putString(key, prior[key] as? String) }
            editor.commit()
        }
    }

    private inline fun LocalDatabase.useDatabase(block: (LocalDatabase) -> Unit) { try { block(this) } finally { close() } }
    private fun count(db: LocalDatabase, sql: String): Long = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }
    private fun assertBoundedRows(db: LocalDatabase) {
        assertTrue(count(db, "SELECT coalesce(max(length(CAST(payload AS BLOB))),0) FROM local_rows") <= LocalDao.CHUNK_CHARS * 4)
        assertTrue(count(db, "SELECT coalesce(max(length(CAST(payload AS BLOB))),0) FROM local_payload_chunks") <= LocalDao.CHUNK_CHARS * 4)
        assertEquals(0L, count(db, "SELECT count(*) FROM pragma_foreign_key_check"))
    }

    /** Loopback Subsonic fixture: the sync flow performs only search3/getPlaylists/getPlaylist. */
    private class ReadOnlyServer(private val tracks: List<Track>) : Closeable {
        private val socket = ServerSocket(0)
        val port get() = socket.localPort
        @Volatile var searchPages = 0; private set
        private val worker = thread(name = "library-regression-fixture", isDaemon = true) {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream().bufferedReader()
                    val request = input.readLine()
                    var length = 0
                    while (true) { val header = input.readLine(); if (header.isNullOrEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt() }
                    val body = CharArray(length); var read = 0
                    while (read < length) { val n = input.read(body, read, length - read); check(n > 0); read += n }
                    val params = String(body).split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                    val result = JsonObject().apply { addProperty("status", "ok") }
                    val gson = Gson()
                    when {
                        request.contains("/search3.view") -> {
                            searchPages++
                            val batch = tracks.drop(params.getValue("songOffset").toInt()).take(params.getValue("songCount").toInt())
                            val songs = batch.map { track -> gson.toJsonTree(track).asJsonObject.apply {
                                addProperty("coverArt", track.cover); if (track.starred) addProperty("starred", "2026-10-22") else remove("starred")
                                addProperty("track", track.number); addProperty("discNumber", track.disc)
                            } }
                            result.add("searchResult3", JsonObject().apply { add("song", gson.toJsonTree(songs)) })
                        }
                        request.contains("/getArtists.view") -> result.add("artists", JsonObject())
                        request.contains("/getPlaylists.view") -> result.add("playlists", JsonObject().apply { add("playlist", gson.toJsonTree(listOf(mapOf("id" to "p1", "name" to "我的歌单")))) })
                        request.contains("/getPlaylist.view") -> result.add("playlist", JsonObject().apply { add("entry", gson.toJsonTree(tracks.take(1000).map { mapOf("id" to it.id) })) })
                        else -> error("Unexpected mutation or request: ${request.substringBefore('?')}")
                    }
                    val bytes = gson.toJson(mapOf("subsonic-response" to result)).toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (e: SocketException) { if (!socket.isClosed) throw e }
        }
        override fun close() { socket.close(); worker.join(3000) }
    }
}
