@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.offline.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadMigrationTest {
    @Test fun interruptedCacheCopyResumesLocallyAndScrubsAuthentication() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = JiayinRepository.get(context)
        val previous = repo.profiles.active()
        val id = "migration-${UUID.randomUUID()}"
        val profile = ServerProfile(id, "迁移示例", "http://localhost:9", "fixture")
        repo.vault.putAll(mapOf("$id.token" to "fixture-token", "$id.salt" to "fixture-salt"))
        repo.profiles.upsert(profile); repo.profiles.activate(profile)
        val helper = object : SQLiteOpenHelper(context, "$id.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {}
            override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
        }
        val provider = object : DatabaseProvider {
            override fun getReadableDatabase() = helper.readableDatabase
            override fun getWritableDatabase() = helper.writableDatabase
        }
        val directory = File(context.cacheDir, id)
        val cache = SimpleCache(directory, NoOpCacheEvictor(), provider)
        val oldUri = "http://localhost:9/rest/download.view?u=fixture&t=fixture-token&s=fixture-salt&id=$id"
        val newKey = "jy-download|$id|$id|original"
        val bytes = ByteArray(65536) { (it % 251).toByte() }
        fun write(key: String, value: ByteArray) {
            val hole = cache.startReadWrite(key, 0, value.size.toLong())
            try { val file = cache.startFile(key, 0, value.size.toLong()); file.writeBytes(value); cache.commitFile(file, value.size.toLong()) }
            finally { cache.releaseHoleSpan(hole) }
        }
        try {
            write(oldUri, bytes)
            write(newKey, bytes.copyOf(257)) // A previous process ended in the middle of copying a span.
            val index = DefaultDownloadIndex(provider)
            val request = DownloadRequest.Builder(id, Uri.parse(oldUri)).build()
            index.putDownload(Download(request, Download.STATE_COMPLETED, 0, 1, bytes.size.toLong(), 0, Download.FAILURE_REASON_NONE, DownloadProgress().apply { bytesDownloaded = bytes.size.toLong(); percentDownloaded = 100f }))
            repeat(2) { AccountDownloads.migrate(context, provider, cache) }
            val migrated = index.getDownload(id)!!
            assertEquals(Download.STATE_COMPLETED, migrated.state)
            assertEquals("jiayin-download", migrated.request.uri.scheme)
            assertEquals(newKey, migrated.request.customCacheKey)
            assertFalse(cache.keys.contains(oldUri))
            assertTrue(cache.isCached(newKey, 0, bytes.size.toLong()))
            val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null).createDataSource()
            val output = java.io.ByteArrayOutputStream()
            try {
                source.open(DataSpec.Builder().setUri(migrated.request.uri).setKey(newKey).build())
                val buffer = ByteArray(8192)
                while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; output.write(buffer, 0, n) }
            } finally { source.close() }
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(id, AccountDownloads.owner(context, id)!!.account)
        } finally {
            cache.release(); helper.close(); context.deleteDatabase("$id.db"); directory.deleteRecursively()
            context.getSharedPreferences("jiayin-download-owners", 0).edit().remove(id).commit()
            repo.profiles.activate(previous?.profile, previous?.offline ?: false); repo.profiles.remove(id)
        }
    }
}
