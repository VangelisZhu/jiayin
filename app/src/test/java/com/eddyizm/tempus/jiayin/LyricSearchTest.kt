package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.LrclibSearch
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LyricSearchTest {
    private val track = Track("s", "海辺の手紙 (Letters)", "テスト楽団", "夕暮れ", duration = 200)
    private val payload = """[{"id":1,"trackName":"海辺の手紙","artistName":"テスト楽団","albumName":"夕暮れ","duration":200,"syncedLyrics":"[00:01.00]海へ続く道"}]"""
    @Test fun serviceFailureFallsBackToTitleQueryAndStillChecksMetadata() = runBlocking {
        val queries = mutableListOf<HttpUrl>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            queries.add(chain.request().url)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (queries.size == 1) 503 else 200).message("fixture")
                .body((if (queries.size == 1) "unavailable" else payload).toResponseBody()).build()
        }.build()
        val matches = LrclibSearch(http).candidates(track)
        assertEquals(2, queries.size)
        assertEquals("海辺の手紙", queries[0].queryParameter("track_name"))
        assertEquals(track.artist, queries[0].queryParameter("artist_name"))
        assertEquals("海辺の手紙", queries[1].queryParameter("q"))
        assertEquals(1L, LyricRepairPolicy.choose(track, matches)?.id)
        assertNull(LyricRepairPolicy.choose(track.copy(artist = "別の歌手"), matches))
    }
    @Test fun uniqueExactResultDoesNotNeedFallback() = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(payload.toResponseBody()).build()
        }.build()
        assertEquals(1L, LyricRepairPolicy.choose(track, LrclibSearch(http).candidates(track))?.id)
        assertEquals(1, requests)
    }
}
