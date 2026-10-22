package com.eddyizm.tempus.jiayin.data

import com.eddyizm.tempus.jiayin.core.*
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

fun interface LyricSearch {
    suspend fun search(query: String): List<LyricMatch>
    suspend fun candidates(track: Track): List<LyricMatch> = search(LyricRepairPolicy.query(track))
}

class LrclibSearch(http: OkHttpClient) : LyricSearch {
    private val client = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
    override suspend fun search(query: String): List<LyricMatch> {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        return fetch(url)
    }
    override suspend fun candidates(track: Track): List<LyricMatch> {
        val title = LyricRepairPolicy.title(track)
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", track.artist).build()
        val exact = try { fetch(url) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
        if (LyricRepairPolicy.choose(track, exact) != null) return exact
        // The service's multi-field search can fail for international metadata.
        // A title-only fallback still passes the same strict artist/version/duration checks.
        return (exact + search(title)).distinctBy { it.id }
    }
    private fun fetch(url: HttpUrl): List<LyricMatch> =
        client.newCall(Request.Builder().url(url).header("User-Agent", "Jiayin/1.0 (personal music library client)").build()).execute().use { r ->
            check(r.isSuccessful) { "歌词服务暂不可用（HTTP ${r.code}）" }
            JsonParser.parseString(r.body!!.string()).asJsonArray.map { it.asJsonObject }.map {
                LyricMatch(it.get("id").asLong, it.str("trackName"), it.str("artistName"), it.str("albumName"), it.num("duration"), it.str("syncedLyrics").ifBlank { null }, it.str("plainLyrics").ifBlank { null })
            }
        }
}
