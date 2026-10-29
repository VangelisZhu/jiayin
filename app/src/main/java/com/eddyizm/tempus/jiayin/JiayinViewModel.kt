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

data class DownloadInfo(val id: String, val progress: Float, val state: Int, val bytes: Long, val trackId: String = id)
data class ServerUiState(val profiles: List<ServerProfile> = emptyList(), val busy: Boolean = false, val blocking: Boolean = false, val status: String = "", val error: String? = null, val offlineTarget: ServerProfile? = null, val footprints: Map<String, AccountFootprint> = emptyMap())
data class SearchUiState(val active: Boolean = false, val focusKeyboard: Boolean = true, val query: String = "", val scope: SearchScope = SearchScope(), val category: SearchKind = SearchKind.ALL, val hits: List<SearchDocument> = emptyList(), val history: List<SearchHistory> = emptyList(), val busy: Boolean = false)
data class HomeState(
    val configured: Boolean = false, val loading: Boolean = true, val preparationError: String? = null, val status: String = "正在读取曲库",
    val library: Library = Library(), val records: Map<String, Listening> = emptyMap(),
    val artists: Map<String, ArtistProfile> = emptyMap(), val topSongs: Map<String, List<String>> = emptyMap(),
    val index: LibraryIndex = LibraryIndex.EMPTY, val regionRules: List<PathRegionRule> = emptyList(),
    val recommendations: List<Recommendation> = emptyList(), val downloads: List<DownloadInfo> = emptyList(),
    val pending: Int = 0, val uncertain: Int = 0, val connection: String = "尚未测试连接", val message: String? = null, val lyrics: Lyrics? = null,
    val lyricsBusy: Boolean = false, val lyricMatches: List<LyricMatch> = emptyList(),
    val syncing: Boolean = false,
)
@androidx.annotation.OptIn(UnstableApi::class)
class JiayinViewModel(app: Application) : AndroidViewModel(app) {
    var repo = JiayinRepository.get(app).scoped()
        private set
    private var lyricsRepo = LyricsRepository(repo)
    private var sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val sessionMutable = MutableStateFlow(0L)
    val sessionRevision = sessionMutable.asStateFlow()
    private val serversMutable = MutableStateFlow(ServerUiState())
    val servers = serversMutable.asStateFlow()
    private var switchJob: Job? = null
    private var switching = false
    private var frozen = false
    private val switcher = ServerSwitchCoordinator(repo.profiles,
        verify = { JiayinRepository.get(app).testProfile(it) },
        quiesce = { quiesceAccount() }, reload = { reloadAccount() })
    private val mutable = MutableStateFlow(HomeState(configured = repo.configured))
    val state = mutable.asStateFlow()
    private val searchMutable = MutableStateFlow(SearchUiState())
    val search = searchMutable.asStateFlow()
    private var searchJob: Job? = null
    private var refreshNumber = 0L
    val folderRequest = MutableStateFlow(BrowseLocation())
    fun requestFolder(path: String) = restoreBrowser("文件夹", path)
    fun restoreBrowser(category: String, path: String) { folderRequest.value = BrowseLocation(category, path, folderRequest.value.request + 1) }
    val playerRequests = MutableStateFlow(0L)
    fun requestPlayer() { playerRequests.value++ }
    private var controller: MediaController? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val pendingCommands = ArrayDeque<Bundle>()
    private var lyricJob: Job? = null
    private var downloadJob: Job? = null
    private var batch = 0L
    private var downloadEpoch = 0L
    private val artistRequests = mutableSetOf<String>()
    private var discoveryJob: Job? = null
    private var syncJob: Job? = null
    private var syncManualRequested = false
    private var lastCorrectionCheck = 0L
    private var lastOutboxCheck = 0L
    var selectedCollection: MusicList? = null
    var searchOrigin: SearchReturnLocation? = null
    init {
        prepareLocalData()
        viewModelScope.launch { PlaybackHub.libraryRevision.collect { if (it > 0 && !frozen) refreshLocal() } }
        viewModelScope.launch { PlaybackHub.state.map { it.error }.distinctUntilChanged().collect { error -> error?.let { message(it) } } }
        viewModelScope.launch { PlaybackHub.state.map { it.current?.id to repo.session?.generation }.distinctUntilChanged().collect { (id, _) ->
            lyricJob?.cancel()
            mutable.update { it.copy(lyrics = null, lyricMatches = emptyList()) }
            if (id != null && !frozen) loadLyrics(false)
        } }
    }
    fun prepareLocalData(clearStreamingCache: Boolean = false) = task {
        mutable.update { it.copy(loading = true, preparationError = null) }
        try {
            withContext(Dispatchers.IO) {
                repo.bindUpstream()
                if (clearStreamingCache) {
                    val cache = DownloadUtil.getStreamingCacheForPreload(getApplication())
                    cache.keys.toList().forEach { cache.removeResource(it) }
                }
                DownloadUtil.getDownloadManager(getApplication())
            }
            refreshLocal()
            refreshServers()
            if (repo.configured) foreground() else mutable.update { it.copy(loading = false) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(loading = false, preparationError = "本地下载准备未完成：${e.message ?: "请检查手机剩余空间"}") } }
    }
    private fun connect() {
        if (controllerFuture != null || frozen || !repo.configured) return
        val future = runCatching { MediaController.Builder(getApplication(), SessionToken(getApplication(), ComponentName(getApplication(), JiayinMediaService::class.java))).buildAsync() }
            .getOrElse { message("播放服务暂不可用，请重新打开应用"); return }
        controllerFuture = future
        val expected = repo.session
        future.addListener({ if (expected != repo.session || frozen || future !== controllerFuture) { MediaController.releaseFuture(future); return@addListener }; runCatching {
            controller = future.get()
            while (pendingCommands.isNotEmpty()) send(pendingCommands.removeFirst())
            command("foreground") { putLong("enteredAt", lastForegroundAt) }
        }.onFailure { message("播放服务连接失败：${it.cause?.message ?: it.message}"); controllerFuture = null } }, ContextCompat.getMainExecutor(getApplication()))
    }
    suspend fun refreshLocal() {
        val expected = ++refreshNumber
        val local = withContext(Dispatchers.IO) { Triple(repo.library(), repo.listening(), repo.dao.list(repo.account, "outbox").size) }
        val uncertain = withContext(Dispatchers.IO) { repo.dao.list(repo.account, "outbox-create-attempt").count { repo.dao.get(repo.account, "outbox", it.id) != null } }
        val artists = withContext(Dispatchers.IO) { repo.list("artist", ArtistProfile::class.java).associateBy { it.id } }
        val rules = withContext(Dispatchers.IO) { repo.list("folder-region", PathRegionRule::class.java) }
        val ownership = withContext(Dispatchers.IO) { OwnershipRepository(repo).effective() }
        val index = withContext(Dispatchers.Default) { LibraryIndex.build(local.first, artists, rules, AndroidNameOrdering(), ownership) }
        val recommendations = withContext(Dispatchers.Default) { RecommendationEngine.recommend(local.first.tracks, local.second, System.currentTimeMillis() / 86_400_000 + batch, ownership = ownership) }
        if (expected != refreshNumber) return
        mutable.update { it.copy(configured = repo.configured, loading = false, library = local.first, records = local.second, artists = artists, recommendations = recommendations, pending = local.third, uncertain = uncertain, index = index, regionRules = rules) }
        if (search.value.active) updateSearch(search.value.query)
    }
    fun openSearch(scope: SearchScope = SearchScope()) {
        searchJob?.cancel()
        searchMutable.value = SearchUiState(active = true, scope = scope)
        task { val history = withContext(Dispatchers.IO) { repo.list("search-history", SearchHistory::class.java).sortedByDescending { it.time }.take(20) }; searchMutable.update { it.copy(history = history) } }
    }
    fun hideSearch() { searchJob?.cancel(); searchMutable.update { it.copy(active = false, busy = false) } }
    fun resumeSearch() { searchMutable.update { it.copy(active = true, focusKeyboard = false) }; updateSearch(search.value.query) }
    fun updateSearch(query: String, scope: SearchScope = search.value.scope, category: SearchKind = search.value.category) {
        searchJob?.cancel()
        searchMutable.update { it.copy(query = query, scope = scope, category = category, hits = emptyList(), busy = query.isNotBlank()) }
        searchJob = task {
            delay(180)
            val index = state.value.index
            val hits = withContext(Dispatchers.Default) { index.search(query, scope, category) { ensureActive() } }
            ensureActive()
            searchMutable.update { it.copy(hits = hits, busy = false) }
        }
    }
    fun rememberSearch(query: String = search.value.query) = task {
        val value = query.trim(); if (value.isBlank()) return@task
        val history = withContext(Dispatchers.IO) {
            repo.put("search-history", digest(normalizeSearch(value)), SearchHistory(value, System.currentTimeMillis()))
            val all = repo.list("search-history", SearchHistory::class.java).sortedByDescending { it.time }
            all.drop(20).forEach { repo.dao.delete(repo.account, "search-history", digest(normalizeSearch(it.query))) }; all.take(20)
        }
        searchMutable.update { it.copy(history = history) }
    }
    fun deleteSearchHistory(query: String? = null) = task {
        withContext(Dispatchers.IO) { if (query == null) repo.dao.clear(repo.account, "search-history") else repo.dao.delete(repo.account, "search-history", digest(normalizeSearch(query))) }
        searchMutable.update { it.copy(history = if (query == null) emptyList() else it.history.filterNot { h -> h.query == query }) }
    }
    fun setFolderRegion(path: String, region: String?) = task {
        withContext(Dispatchers.IO) { if (region == null) repo.dao.delete(repo.account, "folder-region", digest(path)) else repo.put("folder-region", digest(path), PathRegionRule(path, region)) }
        refreshLocal(); message(if (region == null) "已清除目录地区设置" else "目录地区已保存在本机，子目录继承")
    }
    fun updateArtistPreferences(profile: ArtistProfile, region: String, type: String, name: String) = task {
        withContext(Dispatchers.IO) { val latest = repo.get("artist", profile.id, ArtistProfile::class.java) ?: profile; repo.put("artist", profile.id, latest.copy(region = region, type = type, sortName = name.trim().takeIf { it.isNotEmpty() })) }; refreshLocal()
    }
    fun setArtistSortName(profile: ArtistProfile, name: String) = task {
        withContext(Dispatchers.IO) { val latest = repo.get("artist", profile.id, ArtistProfile::class.java) ?: profile; repo.put("artist", profile.id, latest.copy(sortName = name.trim().takeIf { it.isNotEmpty() })) }; refreshLocal()
    }
    fun login(address: String, user: String, password: String) = saveServer(address, user, password, "", true)
    fun saveServer(address: String, user: String, password: String, name: String, activate: Boolean) = serverTask("正在验证服务器") {
        val profile = JiayinRepository.get(getApplication()).saveProfile(address, user, password, name)
        if (activate || profile.id == repo.account) switcher.switchTo(profile) else { refreshServers(); message("服务器已保存") }
    }
    fun switchServer(profile: ServerProfile, offline: Boolean = false) = serverTask("正在切换到 ${profile.name}") {
        try { switcher.switchTo(profile, offline); serversMutable.update { it.copy(offlineTarget = null) } }
        catch (e: AuthenticationRequired) { throw e }
        catch (e: java.io.IOException) {
            if (!offline) serversMutable.update { it.copy(offlineTarget = profile) }
            throw IllegalStateException("无法连接 ${profile.name}，当前账户未改变；可重试或进入离线曲库", e)
        }
    }
    fun dismissOfflineOffer() { serversMutable.update { it.copy(offlineTarget = null) } }
    fun testServer(profile: ServerProfile) = serverTask("正在测试 ${profile.name}") { message(JiayinRepository.get(getApplication()).testProfile(profile)) }
    fun renameServer(profile: ServerProfile, name: String) = serverTask("正在保存名称") { repo.profiles.rename(profile.id, name); refreshServers() }
    fun logoutServer(profile: ServerProfile, remove: Boolean = false) = serverTask(if (remove) "正在移除本地数据" else "正在退出登录") {
        if (profile.id != repo.account) AccountDownloads.pause(getApplication(), profile.id, null, true)
        switcher.logout(profile, remove) {
            AccountDownloads.remove(getApplication(), profile.id)
            withContext(Dispatchers.IO) {
                repo.dao.removeAccount(profile.id)
                val prefs = repo.settings
                prefs.edit().apply { prefs.all.keys.filter { it.startsWith(profile.id + ".") }.forEach { remove(it) } }.commit()
                com.eddyizm.tempus.jiayin.ui.Covers.removeAccount(getApplication(), profile.id)
                val cache = DownloadUtil.getStreamingCacheForPreload(getApplication())
                cache.keys.filter { it.startsWith(profile.id + "|") }.forEach { cache.removeResource(it) }
            }
        }
        refreshServers()
    }
    suspend fun refreshServers() {
        val values = withContext(Dispatchers.IO) { repo.profiles.all() }
        val sizes = values.associate { it.id to AccountDownloads.footprint(getApplication(), it.id) }
        serversMutable.update { it.copy(profiles = values, footprints = sizes) }
    }
    private fun serverTask(status: String, block: suspend () -> Unit): Job {
        if (switchJob?.isActive == true) return switchJob!!
        switchJob = viewModelScope.launch {
            switching = true
            serversMutable.update { it.copy(busy = true, status = status, error = null, offlineTarget = null) }
            try { block() }
            catch (e: TimeoutCancellationException) {
                if (frozen) reloadAccount()
                serversMutable.update { it.copy(error = "等待后台停止超时，当前账户已保留，请重试") }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (frozen) reloadAccount() // failed before commit: reopen original account, paused
                serversMutable.update { it.copy(error = e.message ?: "操作未完成，请重试") }
            } finally { switching = false; serversMutable.update { it.copy(busy = false, blocking = false) } }
        }
        return switchJob!!
    }
    private suspend fun quiesceAccount() {
        frozen = true; refreshNumber++; downloadEpoch++
        serversMutable.update { it.copy(blocking = true) }
        SessionGate.block(repo.account)
        com.eddyizm.tempus.jiayin.ui.Covers.cancelAccount(repo.account)
        repo.cancelRequests(); sessionJob.cancelAndJoin()
        searchJob?.cancelAndJoin(); lyricJob?.cancelAndJoin(); downloadJob?.cancelAndJoin(); discoveryJob?.cancelAndJoin()
        pendingCommands.clear()
        controller = null; controllerFuture?.let { MediaController.releaseFuture(it) }; controllerFuture = null
        JiayinMediaService.stopForSwitch(getApplication())
        AccountDownloads.pauseAndAwait(getApplication(), repo.account)
        com.eddyizm.tempus.jiayin.ui.Covers.cancelAccount(repo.account)
    }
    private suspend fun reloadAccount() {
        repo = JiayinRepository.get(getApplication()).scoped(); lyricsRepo = LyricsRepository(repo)
        sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job]); refreshNumber++
        artistRequests.clear(); selectedCollection = null; searchOrigin = null; batch = 0
        lastOutboxCheck = 0; lastCorrectionCheck = 0
        folderRequest.value = BrowseLocation(); playerRequests.value = 0
        searchMutable.value = SearchUiState(); mutable.value = HomeState(configured = repo.configured)
        withContext(Dispatchers.IO) { repo.bindUpstream() }
        PlaybackHub.reset(); SessionGate.clear(); frozen = false
        sessionMutable.value++
        refreshLocal(); refreshServers()
        if (repo.configured) { foreground(); if (repo.offline) mutable.update { it.copy(connection = "离线曲库 · 仅使用本地数据与下载") } }
    }
    private fun autoSync() { if (!frozen && repo.configured && !repo.offline) sync(automatic = true) }
    fun sync(automatic: Boolean = false): Job {
        syncJob?.takeIf { it.isActive }?.let { if (!automatic) syncManualRequested = true; return it }
        syncManualRequested = !automatic
        val fixedRepo = repo
        return task {
            if (!fixedRepo.currentSession() || fixedRepo.offline) return@task
            if (automatic) {
                val due = withContext(Dispatchers.IO) { fixedRepo.syncPolicy().due(System.currentTimeMillis()) }
                if (!due && !syncManualRequested) return@task
                val manager = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
                if (manager.activeNetwork == null && !syncManualRequested) return@task
            }
            mutable.update { it.copy(loading = true, syncing = true, status = "正在更新曲库") }
            try {
                fixedRepo.synchronize { progress -> if (fixedRepo.currentSession()) mutable.update { it.copy(status = progress) } }
                withContext(Dispatchers.IO) { fixedRepo.dao.delete(fixedRepo.account, "sync-meta", "failed") }
                if (!fixedRepo.vault.get("${fixedRepo.account}.manager-member").isNullOrBlank()) {
                    try { ManagerCorrections(fixedRepo).refresh(); OwnershipRepository(fixedRepo).refresh() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Optional metadata does not invalidate a complete library. */ }
                }
                refreshLocal(); mutable.update { it.copy(connection = "已连接 NAS", status = "曲库已更新") }
                if (syncManualRequested) message("曲库已更新")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                withContext(Dispatchers.IO) { fixedRepo.put("sync-meta", "failed", System.currentTimeMillis()) }
                mutable.update { it.copy(connection = "更新失败 · 保留上次曲库", status = "更新失败：${e.message ?: "请检查连接"}") }
                if (syncManualRequested) throw e
            } finally { if (fixedRepo.currentSession()) mutable.update { it.copy(loading = false, syncing = false) } }
        }.also { syncJob = it }
    }
    fun refreshCorrections() = task {
        val scoped = repo.scoped()
        com.eddyizm.tempus.jiayin.data.ManagerCorrections(scoped).refresh()
        OwnershipRepository(scoped).refresh()
        refreshLocal()
        if (PlaybackHub.state.value.current != null) loadLyrics(false)
    }
    fun test() = task { try { val result = repo.testConnection(); mutable.update { it.copy(connection = result) }; message(result) } catch (e: Exception) { mutable.update { it.copy(connection = "连接失败 · 可继续使用下载与本地曲库") }; throw e } }
    fun command(action: String, build: Bundle.() -> Unit = {}) {
        if (frozen || !repo.configured) return
        val args = Bundle().apply { putString("action", action); putString("account", repo.account); putLong("generation", repo.session?.generation ?: -1); build() }
        if (controller == null) { if (pendingCommands.size < 64) pendingCommands.addLast(args); connect(); return }
        send(args)
    }
    private fun send(args: Bundle) {
        val active = controller ?: return
        val command = SessionCommand(PlaybackHub.COMMAND, Bundle.EMPTY)
        if (!active.isSessionCommandAvailable(command)) { message("播放服务尚未准备好，请稍后重试"); return }
        val result = active.sendCustomCommand(command, args)
        val expected = repo.session
        result.addListener({ if (repo.session != expected || frozen) return@addListener; runCatching {
            if (result.get().resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS) message("播放操作未完成，请重试")
        }.onFailure { message("播放服务连接已断开，请重新点播放"); controller = null; controllerFuture = null } }, ContextCompat.getMainExecutor(getApplication()))
    }
    private var lastForegroundAt = 0L
    fun foreground(userReturn: Boolean = false) {
        if (userReturn) lastForegroundAt = android.os.SystemClock.elapsedRealtime()
        if (!frozen && repo.configured) {
            connect(); if (controller != null) command("foreground") { putLong("enteredAt", lastForegroundAt) }
            autoSync()
            if (!repo.offline && System.currentTimeMillis() - lastOutboxCheck > 30_000) {
                lastOutboxCheck = System.currentTimeMillis()
                task {
                    val hasPending = withContext(Dispatchers.IO) { repo.dao.list(repo.account, "outbox").isNotEmpty() }
                    if (hasPending) { repo.syncPending(); refreshLocal() }
                }
            }
            if (!repo.offline && System.currentTimeMillis() - lastCorrectionCheck > 30_000 && !repo.vault.get("${repo.account}.manager-member").isNullOrBlank()) {
                lastCorrectionCheck = System.currentTimeMillis()
                task {
                    try { ManagerCorrections(repo).refresh() }
                    finally { OwnershipRepository(repo).refresh(); refreshLocal() }
                }
            }
        }
    }
    fun start(tracks: List<Track>, shuffle: Boolean = false, source: String = "播放队列", index: Int = 0, discovery: DiscoveryState? = null) = task {
        if (tracks.isEmpty()) { message("这里还没有歌曲"); return@task }
        val recent = state.value.records.values.sortedByDescending { it.lastHeard }.take(100).map { it.id }.toSet()
        val queue = withContext(Dispatchers.Default) { QueueEngine.create(tracks.map { it.id }, source, shuffle, tracks.associateBy { it.id }, recent).let { if (shuffle) it else it.copy(index = index.coerceIn(0, tracks.lastIndex)) } }
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) { repo.put("command", id, queue.copy(discovery = discovery)) }
        command("start") { putString("id", id) }
    }
    fun favorite(track: Track) = task { withContext(Dispatchers.IO) { repo.favorite(repo.library().tracks.find { it.id == track.id } ?: track) }; refreshLocal(); command("refresh-favorites"); repo.syncPending(); refreshLocal() }
    fun discover() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = task {
            val snapshot = withContext(Dispatchers.IO) { Triple(repo.library(), repo.listening(), repo.get("discovery", "recent", Array<String>::class.java)?.toList().orEmpty()) }
            val eligible = if (repo.offline) snapshot.first.copy(tracks = snapshot.first.tracks.filter { AccountDownloads.downloaded(getApplication(), repo.account, it.id) }) else snapshot.first
            val result = withContext(Dispatchers.Default) { DiscoveryEngine.extend(eligible, snapshot.second, DiscoveryState(recent = snapshot.third), emptyList(), emptySet(), System.nanoTime(), 30, state.value.index.ownership) }
            val picks = result.picks
            if (picks.isEmpty()) { message("没有更多未收藏、未加入歌单的歌曲了"); return@task }
            val byId = snapshot.first.tracks.associateBy { it.id }
            withContext(Dispatchers.IO) { repo.put("discovery", "recent", (snapshot.third + picks.map { it.id }).takeLast(150)) }
            mutable.update { it.copy(recommendations = picks) }
            start(picks.mapNotNull { byId[it.id] }, source = "随便听听 · ∞", discovery = result.state.copy(relaxedNotified = result.window < 10)).join()
            message(if (result.window < 10) "无限听歌已开启；候选较少，歌手间隔已放宽" else "无限听歌已开启 · 相邻 10 首歌手不重复")
        }
    }
    fun artistProfile(t: Track) = state.value.artists[artistKey(t)] ?: ArtistProfile(artistKey(t), t.artist)
    fun favoriteArtist(profile: ArtistProfile) = task { withContext(Dispatchers.IO) { repo.favoriteArtist(profile) }; refreshLocal(); repo.syncPending(); refreshLocal() }
    fun classifyArtist(profile: ArtistProfile, region: String, type: String) = task { withContext(Dispatchers.IO) { val latest = repo.get("artist", profile.id, ArtistProfile::class.java) ?: profile; repo.put("artist", profile.id, latest.copy(region = region, type = type)) }; refreshLocal() }
    fun loadArtist(list: MusicList) {
        if (!artistRequests.add(list.id)) return
        task {
            val ids = list.tracks.toSet()
            val top = withContext(Dispatchers.IO) { runCatching { repo.topSongs(list.name, ids) }.getOrDefault(emptyList()) }
            mutable.update { it.copy(topSongs = it.topSongs + (list.id to top)) }
        }
    }
    fun dislike(track: Track) = task { withContext(Dispatchers.IO) { repo.dislike(track.id) }; refreshLocal(); message("以后不再推荐这首歌") }
    fun recommendations(from: Track? = null) = task {
        batch++
        val result = withContext(Dispatchers.Default) { RecommendationEngine.recommend(state.value.library.tracks, state.value.records, System.currentTimeMillis() / 86_400_000 + batch, from = from, ownership = state.value.index.ownership) }
        mutable.update { it.copy(recommendations = result) }
    }
    fun rediscover() = task {
        val tracks = state.value.library.tracks.filter { state.value.records[it.id]?.disliked != true }.sortedBy { state.value.records[it.id]?.lastHeard ?: 0 }.take(30)
        mutable.update { it.copy(recommendations = tracks.map { t -> Recommendation(t.id, if ((it.records[t.id]?.lastHeard ?: 0) == 0L) "还没有听过" else "有一阵子没听了") }) }
    }
    fun addTrackToPlaylist(id: String, songId: String, done: (Result<PlaylistEditResult>) -> Unit) = playlistEdit(done) { it.addTrackToPlaylist(id, songId) }
    fun removePlaylistEntry(ref: PlaylistEntryRef, done: (Result<PlaylistEditResult>) -> Unit) = playlistEdit(done) { it.removePlaylistEntry(ref) }
    private fun playlistEdit(done: (Result<PlaylistEditResult>) -> Unit, edit: (JiayinRepository) -> PlaylistEditResult) = task {
        val target = repo
        var delivered = false
        try {
            val result = withContext(Dispatchers.IO) { edit(target) }
            if (!target.currentSession()) return@task
            refreshLocal(); delivered = true; done(Result.success(result))
            if (result == PlaylistEditResult.SAVED) {
                message("已保存，等待同步")
                target.syncPending()
                if (target.currentSession()) {
                    refreshLocal()
                    message(state.value.library.playlists.firstOrNull { it.syncError != null }?.syncError
                        ?: if (state.value.pending > 0) "已保存，等待同步" else "歌单已同步")
                }
            } else if (result == PlaylistEditResult.ALREADY_PRESENT) message("这首歌已在歌单中")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (target.currentSession()) { if (!delivered) done(Result.failure(e)) else message(e.message ?: "已保存，等待同步") } }
    }
    fun reloadPlaylist(id: String, done: (String?) -> Unit) = task {
        val target = repo
        try { target.reloadPlaylist(id); if (target.currentSession()) { refreshLocal(); done(null) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (target.currentSession()) done(e.message ?: "重新载入失败") }
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
        require(tracks.isNotEmpty()) { "这里还没有可以下载的歌曲" }
        val manager = DownloadUtil.getDownloadManager(getApplication())
        manager.requirements = Requirements(if (Preferences.isDownloadWifiOnly()) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
        val epoch = downloadEpoch
        var queued = 0
        for (batch in tracks.chunked(30)) {
            ensureActive(); if (epoch != downloadEpoch) break
            queued += AccountDownloads.enqueue(getApplication(), repo, batch); delay(20)
        }
        message(if (queued > 0) "已加入 $queued 首${if (Preferences.isDownloadWifiOnly()) "，仅 Wi-Fi 下载" else ""}" else "这些歌曲已下载")
        watchDownloads(true)
    }
    fun watchDownloads(enabled: Boolean) {
        downloadJob?.cancel()
        if (!enabled) return
        downloadJob = task {
            while (isActive) {
                val list = withContext(Dispatchers.IO) {
                    val values = mutableListOf<DownloadInfo>()
                    DownloadUtil.getDownloadManager(getApplication()).downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) { val d = cursor.download; val track = AccountDownloads.trackId(getApplication(), repo.account, d.request.id); if (track != null) values.add(DownloadInfo(d.request.id, d.percentDownloaded.coerceAtLeast(0f), d.state, d.bytesDownloaded, track)) }
                    }
                    values
                }
                mutable.update { it.copy(downloads = list) }; delay(1000)
            }
        }
    }
    fun pauseDownload(id: String, paused: Boolean) {
        if (frozen) return
        AccountDownloads.pause(getApplication(), repo.account, id, paused)
    }
    fun pauseAllDownloads(paused: Boolean) {
        if (frozen) return
        downloadEpoch++; AccountDownloads.pause(getApplication(), repo.account, null, paused)
        message(if (paused) "已暂停当前账户的下载" else "已继续当前账户的下载")
    }
    fun removeDownload(id: String) = removeDownloads(setOf(id))
    fun removeDownloads(ids: Set<String>) = task {
        downloadEpoch++; AccountDownloads.remove(getApplication(), repo.account, ids)
        mutable.update { it.copy(downloads = it.downloads.filterNot { d -> d.id in ids }) }
        message("已移除 ${ids.size} 首手机上的下载")
    }
    fun retryPending() = task { repo.syncPending(); refreshLocal(); message(state.value.library.playlists.firstOrNull { it.syncError != null }?.syncError ?: if (state.value.pending > 0) "部分修改仍待同步" else "歌单已同步") }
    fun retryUncertainPlaylists() = task {
        withContext(Dispatchers.IO) { repo.dao.clear(repo.account, "outbox-create-attempt") }
        repo.syncPending(); refreshLocal()
        message(if (state.value.pending > 0) "部分修改仍待同步，请检查连接" else "待同步修改已处理")
    }
    fun clearHistory() = task { withContext(Dispatchers.IO) { repo.dao.clear(repo.account, "listening") }; refreshLocal(); message("收听记录与推荐偏好已清空") }
    fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun consumedMessage() { mutable.update { it.copy(message = null) } }
    private fun task(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch(sessionJob) {
        if (frozen) return@launch
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message(e.message ?: "操作失败，请重试") }
    }
    private fun child(t: Track) = Child(t.id, title = t.title, artist = t.artist, album = t.album, coverArtId = t.cover.ifBlank { null }, duration = t.duration, albumId = t.albumId, artistId = t.artistId)
    override fun onCleared() { if (frozen) SessionGate.clear(); repo.cancelRequests(); sessionJob.cancel(); controllerFuture?.let { MediaController.releaseFuture(it) } }
}
