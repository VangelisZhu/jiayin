package com.eddyizm.tempus.jiayin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eddyizm.tempus.jiayin.core.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class LibraryIndexAndroidTest {
    @Test fun icuPinyinNaturalNumbersForeignNamesAndManualAlias() {
        val order = AndroidNameOrdering()
        listOf("陈屿" to "C", "江南" to "J", "林间乐队" to "L", "周禾" to "Z", "黃山" to "H", "amber" to "A", "123" to "#", "!Amber" to "#", "★星" to "#", "さくら" to "#", "테스트" to "#").forEach { (name, expected) -> assertEquals(name, expected, order.section(name)) }
        assertEquals("#", order.section("山下", "日本"))
        assertEquals("S", order.section("山下", "日本", "Sakura"))
        assertTrue(order.compare("Album 2", "Album 10") < 0)
        assertTrue(order.compare("Album 1", "Album 2") < 0)
        assertTrue(order.compare("陈屿", "江南") < 0)
    }
    @Test fun legacyArtistJsonKeepsRegionAndUsesNullableAlias() {
        val profile = Gson().fromJson("""{"id":"legacy","name":"示例","starred":true,"region":"日本","type":"乐队/组合"}""", ArtistProfile::class.java)
        assertEquals("日本", profile.region); assertNull(profile.sortName)
        val i = LibraryIndex.build(Library(listOf(Track("s", "Song", "示例", artistId = "legacy", path = "music/华语/a.wav"))), mapOf(profile.id to profile), emptyList(), AndroidNameOrdering())
        assertEquals("日本", i.regions["s"]); assertEquals("#", i.artists.single().section)
    }
    @Test(timeout = 45000) fun tenThousandSongsTwoThousandArtistsSearchBenchmark() {
        val tracks = (0 until 10000).map { n -> Track("bench-$n", "曲目 ${n.toString().padStart(5, '0')}", "Artist ${n % 2000}", "Album ${n % 500}", "album-${n % 500}", "artist-${n % 2000}", genre = "类型 ${n % 8}", path = "music/${if (n % 2 == 0) "华语" else "日本"}/Artist ${n % 2000}/Album ${n % 500}/$n.wav") }
        lateinit var index: LibraryIndex
        val buildMs = measureNanoTime { index = LibraryIndex.build(Library(tracks), emptyMap(), emptyList(), AndroidNameOrdering()) } / 1_000_000.0
        repeat(10) { index.search("曲目", SearchScope()) }
        val timings = (0 until 100).map { n -> measureNanoTime { index.search(if (n % 2 == 0) "曲目 0${n % 10}" else "Artist ${n % 20}", SearchScope()) } / 1_000_000.0 }.sorted()
        val p95 = timings[94]
        assertEquals(2000, index.artists.size); assertEquals(10000, index.tracks.size)
        assertTrue("Local search P95 was $p95 ms", p95 < 200)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "preview7-benchmark.json").writeText(Gson().toJson(mapOf("generated_songs" to tracks.size, "generated_artists" to index.artists.size, "index_build_ms" to buildMs, "search_p95_ms" to p95)))
    }
}
