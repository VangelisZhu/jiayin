package com.eddyizm.tempus.jiayin

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.*
import com.eddyizm.tempus.jiayin.playback.*
import com.eddyizm.tempus.service.DownloaderService
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class DownloadInfo(val id: String, val progress: Float, val state: Int, val bytes: Long)
data class HomeState(
    val configured: Boolean = false, val loading: Boolean = true, val status: String = "正在读取曲库",
    val library: Library = Library(), val records: Map<String, Listening> = emptyMap(),
    val recommendations: List<Recommendation> = emptyList(), val downloads: List<DownloadInfo> = emptyList(),
    val pending: Int = 0, val connection: String = "尚未测试连接", val message: String? = null, val lyrics: Lyrics? = null,
    val lyricsBusy: Boolean = false, val lyricMatches: List<LyricMatch> = emptyList(),
)
@androidx.annotation.OptIn(UnstableApi::class)
class JiayinViewModel(app: Application) : AndroidViewModel(app) {
    val repo = JiayinRepository.get(app)
    private val lyricsRepo = LyricsRepository(repo)
    private val mutable = MutableStateFlow(HomeState(configured = repo.configured))
    val state = mutable.asStateFlow()
    val playerRequests = MutableStateFlow(0L)
    fun requestPlayer() { playerRequests.value++ }
    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val pendingCommands = ArrayDeque<Bundle>()
    private var lyricJob: Job? = null
    private var downloadJob: Job? = null
    private var batch = 0L
    var selectedCollection: MusicList? = null
    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.bindUpstream() }
            refreshLocal(); if (repo.configured) { connect(); sync() } else mutable.update { it.copy(loading = false) }
        }
        viewModelScope.launch { PlaybackHub.state.map { it.error }.distinctUntilChanged().collect { error -> error?.let { message(it) } } }
        viewModelScope.launch { PlaybackHub.state.map { it.current?.id }.distinctUntilChanged().collect { id ->
            lyricJob?.cancel()
            mutable.update { it.copy(lyrics = null, lyricMatches = emptyList()) }
            if (id != null) loadLyrics(false)
        } }
    }
    private fun connect() {
        if (controllerFuture != null) return
        val future = runCatching { MediaController.Builder(getApplication(), SessionToken(getApplication(), ComponentName(getApplication(), JiayinMediaService::class.java))).buildAsync() }
            .getOrElse { message("播放服务暂不可用，请重新打开应用"); return }
        controllerFuture = future
        future.addListener({ runCatching {
            controller = future.get()
            while (pendingCommands.isNotEmpty()) send(pendingCommands.removeFirst())
            command("foreground")
        }.onFailure { message("播放服务连接失败：${it.cause?.message ?: it.message}"); controllerFuture = null } }, ContextCompat.getMainExecutor(getApplication()))
    }
    suspend fun refreshLocal() {
        val local = withContext(Dispatchers.IO) { Triple(repo.library(), repo.listening(), repo.dao.list(repo.account, "outbox").size) }
        val recommendations = withContext(Dispatchers.Default) { RecommendationEngine.recommend(local.first.tracks, local.second, System.currentTimeMillis() / 86_400_000 + batch) }
        mutable.update { it.copy(configured = repo.configured, loading = false, library = local.first, records = local.second, recommendations = recommendations, pending = local.third) }
    }
    fun login(address: String, user: String, password: String) = task {
        mutable.update { it.copy(loading = true, status = "正在连接 NAS") }
        try { repo.login(address, user, password); refreshLocal(); connect(); sync() }
        finally { mutable.update { it.copy(loading = false) } }
    }
    fun sync() = task {
        mutable.update { it.copy(loading = true, status = "正在同步曲库") }
        try { repo.synchronize { progress -> mutable.update { it.copy(status = progress) } }; refreshLocal(); mutable.update { it.copy(connection = "已连接 NAS") }; message("曲库已同步") }
        catch (e: Exception) { mutable.update { it.copy(connection = "暂时离线 · 使用手机上的曲库与下载") }; throw e }
        finally { mutable.update { it.copy(loading = false) } }
    }
    fun test() = task { try { val result = repo.testConnection(); mutable.update { it.copy(connection = result) }; message(result) } catch (e: Exception) { mutable.update { it.copy(connection = "连接失败 · 可继续使用下载与本地曲库") }; throw e } }
    fun command(action: String, build: Bundle.() -> Unit = {}) {
        val args = Bundle().apply { putString("action", action); build() }
        if (controller == null) { if (pendingCommands.size < 64) pendingCommands.addLast(args); connect(); return }
        send(args)
    }
    private fun send(args: Bundle) {
        val active = controller ?: return
        val command = SessionCommand(PlaybackHub.COMMAND, Bundle.EMPTY)
        if (!active.isSessionCommandAvailable(command)) { message("播放服务尚未准备好，请稍后重试"); return }
        val result = active.sendCustomCommand(command, args)
        result.addListener({ runCatching {
            if (result.get().resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS) message("播放操作未完成，请重试")
        }.onFailure { message("播放服务连接已断开，请重新点播放"); controller = null; controllerFuture = null } }, ContextCompat.getMainExecutor(getApplication()))
    }
    fun foreground() { if (repo.configured) { connect(); if (controller != null) command("foreground") } }
    fun start(tracks: List<Track>, shuffle: Boolean = false, source: String = "播放队列", index: Int = 0) = task {
        if (tracks.isEmpty()) { message("这里还没有歌曲"); return@task }
        val recent = state.value.records.values.sortedByDescending { it.lastHeard }.take(100).map { it.id }.toSet()
        val queue = withContext(Dispatchers.Default) { QueueEngine.create(tracks.map { it.id }, source, shuffle, tracks.associateBy { it.id }, recent).let { if (shuffle) it else it.copy(index = index.coerceIn(0, tracks.lastIndex)) } }
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) { repo.put("command", id, queue) }
        command("start") { putString("id", id) }
    }
    fun favorite(track: Track) = task { withContext(Dispatchers.IO) { repo.favorite(track) }; refreshLocal(); repo.syncPending(); refreshLocal() }
    fun dislike(track: Track) = task { withContext(Dispatchers.IO) { repo.dislike(track.id) }; refreshLocal(); message("以后不再推荐这首歌") }
    fun recommendations(from: Track? = null) = task {
        batch++
        val result = withContext(Dispatchers.Default) { RecommendationEngine.recommend(state.value.library.tracks, state.value.records, System.currentTimeMillis() / 86_400_000 + batch, from = from) }
        mutable.update { it.copy(recommendations = result) }
    }
    fun rediscover() = task {
        val tracks = state.value.library.tracks.filter { state.value.records[it.id]?.disliked != true }.sortedBy { state.value.records[it.id]?.lastHeard ?: 0 }.take(30)
        mutable.update { it.copy(recommendations = tracks.map { t -> Recommendation(t.id, if ((it.records[t.id]?.lastHeard ?: 0) == 0L) "还没有听过" else "有一阵子没听了") }) }
    }
    fun savePlaylist(id: String?, name: String, tracks: List<String>) = task {
        require(name.isNotBlank()) { "歌单名称不能为空" }
        withContext(Dispatchers.IO) { repo.setPlaylist(id, name.trim(), tracks) }; refreshLocal()
        repo.syncPending(); refreshLocal()
        message(if (state.value.pending > 0) "已保存，等待同步" else "歌单已保存")
    }
    fun deletePlaylist(id: String) = task { withContext(Dispatchers.IO) { repo.deletePlaylist(id) }; refreshLocal(); repo.syncPending(); refreshLocal() }
    fun loadLyrics(refresh: Boolean) {
        val track = PlaybackHub.state.value.current ?: return
        lyricJob?.cancel()
        lyricJob = task {
            mutable.update { it.copy(lyricsBusy = true) }
            try {
                val result = lyricsRepo.load(track, refresh)
                if (PlaybackHub.state.value.current?.id == track.id) mutable.update { it.copy(lyrics = result) }
            } finally { mutable.update { it.copy(lyricsBusy = false) } }
        }
    }
    fun searchLyrics(query: String) = task {
        val track = PlaybackHub.state.value.current ?: return@task
        mutable.update { it.copy(lyricsBusy = true) }
        try { val result = lyricsRepo.search(track, query); if (PlaybackHub.state.value.current?.id == track.id) mutable.update { it.copy(lyricMatches = result) } }
        finally { mutable.update { it.copy(lyricsBusy = false) } }
    }
    fun selectLyrics(match: LyricMatch) = task {
        val track = PlaybackHub.state.value.current ?: return@task
        val value = lyricsRepo.select(track, match)
        if (PlaybackHub.state.value.current?.id == track.id) mutable.update { it.copy(lyrics = value, lyricMatches = emptyList()) }
    }
    fun translate() = task {
        val original = state.value.lyrics ?: return@task
        mutable.update { it.copy(lyricsBusy = true) }
        try { val result = lyricsRepo.translate(original); if (PlaybackHub.state.value.current?.id == original.songId) mutable.update { it.copy(lyrics = result) } }
        finally { mutable.update { it.copy(lyricsBusy = false) } }
    }
    fun editLyrics(value: Lyrics) = task { lyricsRepo.save(value); mutable.update { it.copy(lyrics = value) } }
    fun download(tracks: List<Track>, list: MusicList? = null) = task {
        // Existing Tempus downloader keeps Room + Media3 download index authoritative.
        require(tracks.isNotEmpty()) { "这里还没有可以下载的歌曲" }
        val manager = DownloadUtil.getDownloadManager(getApplication())
        manager.requirements = Requirements(if (Preferences.isDownloadWifiOnly()) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
        DownloadService.sendResumeDownloads(getApplication(), DownloaderService::class.java, true)
        var queued = 0
        var refused = 0
        tracks.chunked(30).forEach { batch ->
            val children = batch.map { child(it) }
            DownloadUtil.downloadMissing(getApplication(), children, list?.id, list?.name) { count ->
                if (count < 0) { refused++; message("下载未能启动，请在前台重试") } else queued += count
            }
            delay(20)
        }
        if (refused == 0) message(if (queued > 0) "已加入 $queued 首${if (Preferences.isDownloadWifiOnly()) "，仅 Wi-Fi 下载" else "，允许移动网络下载"}" else "已下载或已在队列中")
        watchDownloads(true)
        // Lyrics are optional enrichment. Their requests must not hold up enqueueing or progress.
        viewModelScope.launch(Dispatchers.IO) { tracks.forEach { if (isActive) runCatching { lyricsRepo.load(it) } } }
    }
    fun watchDownloads(enabled: Boolean) {
        downloadJob?.cancel()
        if (!enabled) return
        downloadJob = task {
            while (isActive) {
                val list = withContext(Dispatchers.IO) {
                    val values = mutableListOf<DownloadInfo>()
                    DownloadUtil.getDownloadManager(getApplication()).downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) { val d = cursor.download; values.add(DownloadInfo(d.request.id, d.percentDownloaded.coerceAtLeast(0f), d.state, d.bytesDownloaded)) }
                    }
                    values
                }
                mutable.update { it.copy(downloads = list) }; delay(1000)
            }
        }
    }
    fun pauseDownload(id: String, paused: Boolean) {
        DownloadService.sendSetStopReason(getApplication(), DownloaderService::class.java, id, if (paused) 1 else Download.STOP_REASON_NONE, false)
    }
    fun removeDownload(id: String) = task {
        val track = state.value.library.tracks.find { it.id == id } ?: return@task
        val row = withContext(Dispatchers.IO) { com.eddyizm.tempus.repository.DownloadRepository().getDownload(id) }
        if (row != null) DownloadUtil.getDownloadTracker(getApplication()).remove(com.eddyizm.tempus.util.MappingUtil.mapMediaItem(child(track)), row)
    }
    fun clearHistory() = task { withContext(Dispatchers.IO) { repo.dao.clear(repo.account, "listening") }; refreshLocal(); message("收听记录与推荐偏好已清空") }
    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun consumedMessage() { mutable.update { it.copy(message = null) } }
    private fun task(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message(e.message ?: "操作失败，请重试") }
    }
    private fun child(t: Track) = Child(t.id, title = t.title, artist = t.artist, album = t.album, coverArtId = t.cover.ifBlank { null }, duration = t.duration, albumId = t.albumId, artistId = t.artistId)
    override fun onCleared() { controllerFuture?.let { MediaController.releaseFuture(it) } }
}
