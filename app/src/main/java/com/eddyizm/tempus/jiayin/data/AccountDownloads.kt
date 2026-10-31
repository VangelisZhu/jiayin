@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.*
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.jiayin.core.Track
import com.eddyizm.tempus.service.DownloaderService
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.Preferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.*
import java.io.IOException

@androidx.annotation.Keep data class OwnedDownload(val requestId: String, val account: String, val track: String, val cacheKey: String)
data class AccountFootprint(val count: Int = 0, val bytes: Long = 0, val pending: Int = 0, val syncedAt: Long = 0)

/** Download URIs on disk contain no credentials. Authorization is resolved only when bytes are opened. */
object AccountDownloads {
    @JvmStatic fun enabled() = BuildConfig.APPLICATION_ID.startsWith("org.homemusic.player")
    fun requestId(account: String, song: String) = "jy:$account:$song:original"
    fun logicalUri(account: String, song: String): Uri = Uri.Builder().scheme("jiayin-download").authority(account).appendPath(song).build()
    private fun registry(context: Context) = context.getSharedPreferences("jiayin-download-owners", Context.MODE_PRIVATE)
    private fun saveOwner(context: Context, value: OwnedDownload) { check(registry(context).edit().putString(value.requestId, com.google.gson.Gson().toJson(value)).commit()) }
    fun owner(context: Context, requestId: String) = registry(context).getString(requestId, null)?.let { com.google.gson.Gson().fromJson(it, OwnedDownload::class.java) }
    private fun owners(context: Context, account: String) = registry(context).all.values.filterIsInstance<String>().map { com.google.gson.Gson().fromJson(it, OwnedDownload::class.java) }.filter { it.account == account }
    fun trackId(context: Context, account: String, requestId: String): String? = owner(context, requestId)?.takeIf { it.account == account }?.track
    fun requestFor(context: Context, account: String, song: String): OwnedDownload? = owners(context, account).firstOrNull { it.track == song }
    fun downloaded(context: Context, account: String, song: String): Boolean = requestFor(context, account, song)?.let { DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(it.requestId)?.state == Download.STATE_COMPLETED } == true

    /** Called BEFORE DownloadManager can schedule persisted work. Idempotent across interrupted upgrades. */
    @JvmStatic fun migrate(context: Context, database: DatabaseProvider, cache: androidx.media3.datasource.cache.Cache) = runBlocking(Dispatchers.IO) {
        if (!enabled()) return@runBlocking
        val profiles = ServerProfiles(context)
        val active = profiles.active()?.profile
        val index = DefaultDownloadIndex(database)
        val values = mutableListOf<Download>()
        index.getDownloads().use { c -> while (c.moveToNext()) values.add(c.download) }
        for (d in values) {
            var owned = owner(context, d.request.id)
            if (owned == null && active != null && !d.request.id.startsWith("jy:")) {
                val uri = d.request.uri
                val matches = runCatching {
                    val base = active.address.toHttpUrl()
                    val source = uri.toString().toHttpUrl()
                    source.scheme == base.scheme && source.host == base.host && source.port == base.port &&
                        source.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/rest/") && source.queryParameter("u") == active.username
                }.getOrDefault(false)
                if (matches) {
                    owned = OwnedDownload(d.request.id, active.id, d.request.id, d.request.customCacheKey ?: uri.toString())
                    saveOwner(context, owned)
                }
            }
            // Unknown legacy requests are quarantined and cannot reach the network.
            val safeUri = if (owned != null) logicalUri(owned.account, owned.track) else Uri.parse("jiayin-quarantine://${Uri.encode(d.request.id)}")
            val oldKey = owned?.cacheKey ?: d.request.customCacheKey ?: d.request.uri.toString()
            val key = if (owned != null) "jy-download|${owned.account}|${owned.track}|original" else "jy-quarantine|${com.eddyizm.tempus.jiayin.core.digest(oldKey)}"
            if (oldKey != key) {
                // Re-key complete and partial bytes locally; never redownload an existing file.
                for (span in cache.getCachedSpans(oldKey).toList()) {
                    val source = span.file ?: continue
                    var position = span.position
                    val end = span.position + span.length
                    while (position < end) {
                        val cached = cache.getCachedLength(key, position, end - position)
                        if (cached > 0) { position += cached; continue }
                        val hole = cache.startReadWrite(key, position, end - position)
                        if (hole.isCached) { position = minOf(end, hole.position + hole.length); continue }
                        try {
                            val length = if (hole.length < 0) end - position else minOf(hole.length, end - position)
                            val destination = cache.startFile(key, position, length)
                            java.io.RandomAccessFile(source, "r").use { input ->
                                input.seek(position - span.position)
                                destination.outputStream().use { output ->
                                    val buffer = ByteArray(65536); var remaining = length
                                    while (remaining > 0) {
                                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        check(n > 0) { "旧下载文件不完整，请重试本地准备" }
                                        output.write(buffer, 0, n); remaining -= n
                                    }
                                }
                            }
                            cache.commitFile(destination, length); position += length
                        } finally { cache.releaseHoleSpan(hole) }
                    }
                    check(cache.isCached(key, span.position, span.length)) { "本地下载准备尚未完成" }
                }
                val length = d.contentLength.takeIf { it >= 0 } ?: androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata(oldKey))
                if (length >= 0) cache.applyContentMetadataMutations(key, androidx.media3.datasource.cache.ContentMetadataMutations().also {
                    androidx.media3.datasource.cache.ContentMetadataMutations.setContentLength(it, length)
                })
                cache.removeResource(oldKey)
                if (owned != null) { owned = owned.copy(cacheKey = key); saveOwner(context, owned) }
            }
            val request = DownloadRequest.Builder(d.request.id, safeUri).setMimeType(d.request.mimeType).setCustomCacheKey(key).setData(d.request.data).build()
            val terminal = d.state == Download.STATE_COMPLETED || d.state == Download.STATE_FAILED
            index.putDownload(Download(request, if (terminal) d.state else Download.STATE_STOPPED, d.startTimeMs, d.updateTimeMs, d.contentLength,
                if (terminal) 0 else 1, if (d.state == Download.STATE_FAILED) d.failureReason else Download.FAILURE_REASON_NONE,
                DownloadProgress().apply { bytesDownloaded = d.bytesDownloaded; percentDownloaded = d.percentDownloaded }))
            // Remove authentication URLs from the legacy Room metadata as well.
            val row = com.eddyizm.tempus.database.AppDatabase.getInstance().downloadDao().getOne(d.request.id)
            if (row != null) { row.downloadUri = safeUri.toString(); com.eddyizm.tempus.database.AppDatabase.getInstance().downloadDao().insert(row) }
        }
    }

    @JvmStatic fun networkFactory(context: Context, upstream: DataSource.Factory): DataSource.Factory = ResolvingDataSource.Factory(upstream) { spec ->
        if (!spec.uri.scheme.orEmpty().startsWith("jiayin-")) {
            // No legacy authentication URL may be scheduled in the public app.
            if (enabled()) throw IOException("下载请求需要迁移，请打开家音")
            spec
        } else {
            val profileId = spec.uri.host ?: throw IOException("下载归属缺失")
            val repo = JiayinRepository.get(context).scoped()
            if (spec.uri.scheme != "jiayin-download" || repo.account != profileId || !repo.configured || repo.offline) throw IOException("该账户的下载已暂停")
            val owned = requestFor(context, profileId, spec.uri.lastPathSegment.orEmpty()) ?: throw IOException("下载归属缺失")
            spec.buildUpon().setUri(repo.authenticatedUrl("download", owned.track)).build()
        }
    }

    fun enqueue(context: Context, repo: JiayinRepository, tracks: List<Track>): Int {
        check(repo.currentSession() && !repo.offline) { "请先连接服务器" }
        val manager = DownloadUtil.getDownloadManager(context)
        var added = 0
        for (track in tracks) {
            val old = requestFor(context, repo.account, track.id)
            if (old != null) {
                val d = manager.downloadIndex.getDownload(old.requestId)
                if (d?.state == Download.STATE_COMPLETED) continue
            }
            val id = old?.requestId ?: requestId(repo.account, track.id)
            val owned = old ?: OwnedDownload(id, repo.account, track.id, "jy-download|${repo.account}|${track.id}|original")
            saveOwner(context, owned)
            val request = DownloadRequest.Builder(id, logicalUri(repo.account, track.id)).setCustomCacheKey(owned.cacheKey)
                .setMimeType("audio/*").setData(track.title.toByteArray()).build()
            val metadata = com.eddyizm.tempus.model.Download(id).apply {
                title = track.title; artist = track.artist; album = track.album; coverArtId = track.cover
                albumId = track.albumId; artistId = track.artistId; duration = track.duration
                downloadUri = logicalUri(repo.account, track.id).toString(); downloadState = 0
            }
            com.eddyizm.tempus.repository.DownloadRepository().insert(metadata)
            DownloadService.sendAddDownload(context, DownloaderService::class.java, request, 0, true)
            added++
        }
        // All inactive accounts retain a stop reason; this cannot wake their work.
        DownloadService.sendResumeDownloads(context, DownloaderService::class.java, true)
        return added
    }

    suspend fun pauseAndAwait(context: Context, account: String, pauseScheduler: Boolean = true) {
        val manager = withContext(Dispatchers.Main.immediate) { DownloadUtil.getDownloadManager(context).also { m ->
            owners(context, account).forEach { m.setStopReason(it.requestId, 1) }
            if (pauseScheduler) m.pauseDownloads()
        } }
        withTimeout(15_000) {
            while (true) {
                val active = withContext(Dispatchers.Main.immediate) { manager.currentDownloads.any { d -> owner(context, d.request.id)?.account == account && d.state == Download.STATE_DOWNLOADING } }
                if (!active) break
                delay(30)
            }
        }
    }
    fun pause(context: Context, account: String, id: String?, paused: Boolean) {
        val manager = DownloadUtil.getDownloadManager(context)
        val selected = owners(context, account).filter { id == null || it.requestId == id }
        selected.forEach { manager.setStopReason(it.requestId, if (paused) 1 else 0) }
        if (paused && id == null && ServerProfiles(context).active()?.profile?.id == account) manager.pauseDownloads()
        if (!paused) manager.resumeDownloads()
    }
    suspend fun footprint(context: Context, account: String): AccountFootprint = withContext(Dispatchers.IO) {
        val index = DownloadUtil.getDownloadManager(context).downloadIndex
        val values = owners(context, account).mapNotNull { index.getDownload(it.requestId) }
        AccountFootprint(values.count { it.state == Download.STATE_COMPLETED }, values.sumOf { it.bytesDownloaded }, LocalDatabase.get(context).rows().list(account, "outbox").size, AccountStorage(LocalDatabase.get(context).rows(), account).get("sync-meta", "last", Long::class.java) ?: 0)
    }
    suspend fun remove(context: Context, account: String, ids: Set<String>? = null) {
        val selected = withContext(Dispatchers.IO) { owners(context, account).filter { ids == null || it.requestId in ids } }
        val manager = withContext(Dispatchers.Main.immediate) { DownloadUtil.getDownloadManager(context).also { m -> selected.forEach { m.removeDownload(it.requestId) } } }
        withTimeout(30_000) { while (withContext(Dispatchers.IO) { selected.any { manager.downloadIndex.getDownload(it.requestId) != null } }) delay(50) }
        withContext(Dispatchers.IO) {
            selected.forEach {
                com.eddyizm.tempus.database.AppDatabase.getInstance().downloadDao().delete(it.requestId)
                registry(context).edit().remove(it.requestId).commit()
            }
        }
    }

    fun mediaItem(context: Context, repo: JiayinRepository, track: Track, offsetSeconds: Int = 0): MediaItem {
        val owned = requestFor(context, repo.account, track.id)
        val done = owned?.let { DownloadUtil.getDownloadManager(context).downloadIndex.getDownload(it.requestId) }?.takeIf { it.state == Download.STATE_COMPLETED }
        val extras = android.os.Bundle().apply { putString("account", repo.account) }
        val uri: Uri
        val key: String
        if (done != null) { uri = done.request.uri; key = owned!!.cacheKey }
        else {
            val mobile = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered
            val prefs = com.eddyizm.tempus.App.getInstance().preferences
            val format = prefs.getString(if (mobile) "audio_transcode_format_mobile" else "audio_transcode_format_wifi", if (mobile) "mp3" else "raw").orEmpty()
            val rate = prefs.getString(if (mobile) "max_bitrate_mobile" else "max_bitrate_wifi", if (mobile) "256" else "0").orEmpty()
            // Persist no stream credentials in queues or cache metadata.
            uri = Uri.Builder().scheme("jiayin-stream").authority(repo.account).appendPath(track.id)
                .appendQueryParameter("format", format).appendQueryParameter("maxBitRate", rate).appendQueryParameter("timeOffset", offsetSeconds.toString()).build()
            key = "${repo.account}|${track.id}|$rate|$format|${if (offsetSeconds == 0) "" else offsetSeconds.toString()}"
        }
        return MediaItem.Builder().setMediaId(track.id).setUri(uri).setCustomCacheKey(key)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).setExtras(extras)
                .setArtworkUri(if (track.cover.isBlank()) null else Uri.Builder().scheme("content").authority(com.eddyizm.tempus.provider.AlbumArtContentProvider.AUTHORITY).appendPath("account").appendPath(repo.account).appendPath(track.cover).build())
                .setIsPlayable(true).setIsBrowsable(false).build()).build()
    }

    fun playbackNetworkFactory(context: Context, repo: JiayinRepository): DataSource.Factory = ResolvingDataSource.Factory(DownloadUtil.getHttpDataSourceFactory()) { spec ->
            check(repo.currentSession() && !repo.offline) { "当前账户不可联网" }
            if (spec.uri.host != repo.account) throw IOException("账户已切换")
            val method = if (spec.uri.scheme == "jiayin-download") "download" else "stream"
            spec.buildUpon().setUri(repo.authenticatedUrl(method, spec.uri.lastPathSegment.orEmpty(), spec.uri.queryParameterNames.associateWith { spec.uri.getQueryParameter(it).orEmpty() })).build()
        }
    fun playbackFactory(context: Context, repo: JiayinRepository): DataSource.Factory {
        val stream = CacheDataSource.Factory().setCache(DownloadUtil.getStreamingCacheForPreload(context)).setUpstreamDataSourceFactory(playbackNetworkFactory(context, repo))
        return CacheDataSource.Factory().setCache(DownloadUtil.getDownloadCache(context)).setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(stream)
    }
}
