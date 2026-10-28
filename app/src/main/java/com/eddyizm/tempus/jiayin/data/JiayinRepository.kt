package com.eddyizm.tempus.jiayin.data

import android.content.Context
import androidx.core.content.edit
import com.eddyizm.tempus.App
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.util.Preferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class JiayinRepository(val context: Context, private val fixedSession: AccountSession? = null, private val fixed: Boolean = false) {
    val profiles = ServerProfiles(context)
    fun scoped(): JiayinRepository = synchronized(sessionLock) {
        val snapshot = profiles.active()
        val cached = sessionRepository
        if (cached != null && cached.fixedSession?.profile?.id == snapshot?.profile?.id &&
            cached.fixedSession?.generation == snapshot?.generation && cached.fixedSession?.offline == snapshot?.offline) cached
        else JiayinRepository(context.applicationContext, snapshot, true).also { sessionRepository = it }
    }
    val session get() = if (fixed) fixedSession else profiles.active()
    val offline get() = session?.offline == true
    fun currentSession() = SessionGate.permits(account) && session?.let { profiles.active()?.let { now -> now.profile.id == it.profile.id && now.generation == it.generation } } == true
    fun cancelRequests() { http.dispatcher.cancelAll() }
    val settings = context.getSharedPreferences("jiayin", Context.MODE_PRIVATE)
    val vault = SecureVault(context)
    val gson = Gson()
    private val database = LocalDatabase.get(context)
    val dao = database.rows()
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val syncMutex = Mutex()
    val configured get() = if (fixed) fixedSession != null else profiles.active() != null
    val account get() = if (fixed) fixedSession?.profile?.id ?: "unconfigured" else settings.getString("account", "unconfigured")!!
    val server get() = if (fixed) fixedSession?.profile?.address.orEmpty() else settings.getString("server", "").orEmpty()
    val user get() = if (fixed) fixedSession?.profile?.username.orEmpty() else settings.getString("user", "").orEmpty()
    private val storage get() = AccountStorage(dao, account, gson)
    fun <T> get(kind: String, id: String, type: Class<T>): T? = storage.get(kind, id, type)
    fun put(kind: String, id: String, value: Any) = storage.put(kind, id, value)
    fun <T> list(kind: String, type: Class<T>): List<T> = storage.list(kind, type)
    fun transaction(block: () -> Unit) = database.runInTransaction(block)
    fun library(): Library = get("library", "snapshot", Library::class.java) ?: Library()
    fun listening() = list("listening", Listening::class.java).associateBy { it.id }
    fun saveLibrary(value: Library) { put("library", "snapshot", value); put("sync-meta", "last", value.syncedAt) }
    fun syncPolicy() = LibrarySyncPolicy(settings.getInt("$account.sync-days", 30),
        get("sync-meta", "last", java.lang.Long::class.java)?.toLong() ?: 0L,
        get("sync-meta", "failed", java.lang.Long::class.java)?.toLong() ?: 0L)

    suspend fun saveProfile(address: String, username: String, password: String, name: String = ""): ServerProfile = withContext(Dispatchers.IO) {
        val url = address.trim().trimEnd('/').toHttpUrl()
        require(url.scheme == "https" || ServerAddressPolicy.allowsCleartext(url.host)) { "公网连接请使用 HTTPS；HTTP 仅支持本地网络地址" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "地址里不要填写账号、查询参数或密码" }
        require(username.isNotBlank() && password.isNotEmpty()) { "请填写账户和密码" }
        val addressClean = url.toString().trimEnd('/')
        val salt = UUID.randomUUID().toString().replace("-", "")
        val token = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray()).joinToString("") { "%02x".format(it) }
        val scope = ServerProfiles.id(addressClean, username)
        requestAt(addressClean, username, token, salt, "ping")
        vault.putAll(mapOf("$scope.token" to token, "$scope.salt" to salt))
        CredentialBridge.invalidate()
        ServerProfile(scope, name.trim().ifBlank { profiles.find(scope)?.name ?: url.host }, addressClean, username).also { profiles.upsert(it) }
    }
    suspend fun login(address: String, username: String, password: String) {
        check(!fixed) { "请通过服务器管理切换账户" }
        profiles.activate(saveProfile(address, username, password)); bindUpstream()
    }
    suspend fun testProfile(profile: ServerProfile): String = withContext(Dispatchers.IO) {
        val auth = vault.authentication(profile.id) ?: throw AuthenticationRequired()
        val result = requestAt(profile.address, profile.username, auth.first, auth.second, "ping")
        "已连接 · ${result.str("type").ifBlank { "OpenSubsonic" }} ${result.str("serverVersion")}"
    }
    fun bindUpstream() {
        if (!configured) {
            Preferences.setServer(""); Preferences.setInUseServerAddress(""); Preferences.setServerId(""); Preferences.setUser("")
            // Clear upstream storage without writing through the active account bridge.
            Preferences.setPassword(null); Preferences.setToken(null); Preferences.setSalt(null)
            CredentialBridge.invalidate(); App.clearSubsonicClient(); return
        }
        Preferences.setServer(server); Preferences.setInUseServerAddress(server); Preferences.setServerId(account); Preferences.setUser(user)
        // Upstream Subsonic, artwork and download components share token auth. Password is not stored.
        Preferences.setPassword(null); Preferences.setToken(vault.get("$account.token")); Preferences.setSalt(vault.get("$account.salt"))
        val p = App.getInstance().preferences
        if (!settings.getBoolean("defaults-v1", false)) {
            p.edit().putString("streaming_cache_size", "2048").putString("max_bitrate_wifi", "0")
                .putString("max_bitrate_mobile", "256").putString("audio_transcode_format_wifi", "raw")
                .putString("audio_transcode_format_mobile", "mp3").putBoolean("download_wifi_only", true)
                .putBoolean("continuous_play", false).putBoolean("github_update_check", false)
                .putString("precache_tracks_count", "2").putBoolean("precache_wifi_only", false).apply()
            settings.edit { putBoolean("defaults-v1", true) }
        }
        App.refreshSubsonicClient()
    }
    fun request(method: String, params: Map<String, List<String>> = emptyMap()): JsonObject {
        check(!fixed || currentSession()) { "账户已切换，请重试" }
        check(!offline) { "当前为离线曲库，请连接服务器后重试" }
        val auth = vault.authentication(account) ?: throw AuthenticationRequired()
        val result = requestAt(server, user, auth.first, auth.second, method, params)
        check(!fixed || currentSession()) { "账户已切换，请重试" }
        return result
    }
    fun authenticatedUrl(method: String, id: String, extra: Map<String, String> = emptyMap()): String {
        check(currentSession() && !offline) { "当前账户不可联网" }
        val auth = vault.authentication(account) ?: throw AuthenticationRequired()
        return "$server/rest/$method.view".toHttpUrl().newBuilder()
            .addQueryParameter("u", user).addQueryParameter("t", auth.first)
            .addQueryParameter("s", auth.second).addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", "Jiayin").addQueryParameter("id", if (method == "getCoverArt") VersionedCover.original(id) else id).apply {
                if (method == "getCoverArt" && id.startsWith("jv:")) addQueryParameter("jiayinRevision", id.split(':', limit = 3)[1])
                extra.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build().toString()
    }

    private fun requestAt(address: String, user: String, token: String, salt: String, method: String, params: Map<String, List<String>> = emptyMap()): JsonObject {
        val form = FormBody.Builder().add("u", user).add("t", token).add("s", salt).add("v", "1.16.1").add("c", "Jiayin").add("f", "json")
        params.forEach { (key, values) -> values.forEach { form.add(key, it) } }
        return http.newCall(Request.Builder().url("$address/rest/$method.view").post(form.build()).build()).execute().use { response ->
            if (response.code >= 500) throw java.io.IOException("服务器暂时不可用（HTTP ${response.code}）")
            if (response.code == 401 || response.code == 403) throw AuthenticationRequired()
            check(response.isSuccessful) { "服务器返回 HTTP ${response.code}" }
            val root = JsonParser.parseString(JsonText.decode(response.body?.bytes() ?: error("服务器返回空内容"))).asJsonObject.getAsJsonObject("subsonic-response") ?: error("不是有效的 Subsonic 服务")
            if (root.getAsJsonObject("error")?.num("code") in listOf(40, 41, 42, 43, 44)) throw AuthenticationRequired()
            check(root.str("status") == "ok") { root.getAsJsonObject("error")?.str("message") ?: "接口请求失败" }
            root
        }
    }
    suspend fun testConnection(): String = testProfile(session?.profile ?: throw AuthenticationRequired())
    suspend fun synchronize(progress: (String) -> Unit = {}): Library = syncMutex.withLock { withContext(Dispatchers.IO) {
        flushOutbox()
        val songs = linkedMapOf<String, Track>()
        var offset = 0
        while (true) {
            val batch = request("search3", mapOf("query" to listOf(""), "songCount" to listOf("500"), "songOffset" to listOf(offset.toString()), "artistCount" to listOf("0"), "albumCount" to listOf("0")))
                .getAsJsonObject("searchResult3")?.objects("song").orEmpty()
            val before = songs.size
            batch.forEach { val track = parseTrack(it); songs[track.id] = track }
            progress("已读取 ${songs.size} 首歌曲")
            if (batch.size < 500) break
            check(songs.size > before) { "服务器分页未前进，已保留上次完整索引，请重试" }
            offset += batch.size
        }
        val playlists = request("getPlaylists").getAsJsonObject("playlists")?.objects("playlist").orEmpty().map { p ->
            val contents = request("getPlaylist", one("id", p.str("id"))).getAsJsonObject("playlist")
            MusicList(p.str("id"), p.str("name"), contents?.objects("entry").orEmpty().map { it.str("id") }, p.str("coverArt"), readOnly = playlistReadOnly(contents ?: p) || playlistReadOnly(p))
        }
        // A failed remote mutation retains the optimistic local view until retry succeeds.
        val pending = list("outbox", PendingOperation::class.java)
        val local = library()
        // Optional metadata must never prevent the song library from being synchronized.
        runCatching {
            val remoteArtists = request("getArtists").getAsJsonObject("artists")?.objects("index").orEmpty().flatMap { it.objects("artist") }
            remoteArtists.forEach { a ->
                val id = a.str("id"); if (id.isNotBlank()) {
                    val previous = get("artist", id, ArtistProfile::class.java)
                    val optimistic = pending.lastOrNull { it.params["artistId"]?.firstOrNull() == id }
                    put("artist", id, ArtistProfile(id, a.str("name"), optimistic?.let { it.method == "star" } ?: (isStarred(a)), previous?.region ?: "未分类", previous?.type ?: "未分类", previous?.sortName))
                }
            }
        }
        val enhanced = runCatching { ManagerMetadata(this@JiayinRepository).load(songs.values.toList()) }.getOrElse {
            check(currentSession()) { "账户已切换" }
            progress("曲库管理暂不可用，保留上次分类")
            val previous = local.tracks.associateBy { it.id }
            songs.values.map { t -> t.copy(sharedRegion = previous[t.id]?.sharedRegion, albumVersion = t.albumVersion?.takeIf(String::isNotBlank) ?: previous[t.id]?.albumVersion) }
        }
        // Local edits can arrive while network/metadata requests above are in flight.
        database.runInTransaction<Library>(java.util.concurrent.Callable {
            check(currentSession()) { "账户已切换，请重试" }
            val latestPending = list("outbox", PendingOperation::class.java)
            val latest = library()
            val protected = latestPending.mapNotNull { it.playlistId?.let(::resolvePlaylistId) }.toSet()
            val removed = latestPending.filter { it.method == "deletePlaylist" }.mapNotNull { it.playlistId?.let(::resolvePlaylistId) }.toSet()
            val lists = playlists.filterNot { it.id in protected || it.id in removed } + latest.playlists.filter { it.id in protected || it.id.startsWith("local:") }
            val stars = latestPending.filter { it.method in listOf("star", "unstar") && it.params.containsKey("id") }.associate { it.params.getValue("id").first() to (it.method == "star") }
            Library(enhanced.map { it.copy(starred = stars[it.id] ?: it.starred) }, lists, System.currentTimeMillis()).also { saveLibrary(it) }
        })
    } }
    @Synchronized fun favorite(track: Track) {
        database.runInTransaction {
            val lib = library(); saveLibrary(lib.copy(tracks = lib.tracks.map { if (it.id == track.id) it.copy(starred = !track.starred) else it }))
            enqueue(if (track.starred) "unstar" else "star", one("id", track.id))
        }
    }
    @Synchronized fun favoriteArtist(profile: ArtistProfile) {
        val old = get("artist", profile.id, ArtistProfile::class.java) ?: profile
        put("artist", profile.id, old.copy(starred = !old.starred))
        if (!profile.id.startsWith("name:")) enqueue(if (old.starred) "unstar" else "star", one("artistId", profile.id))
    }
    fun topSongs(name: String, ids: Set<String>): List<String> = request("getTopSongs", mapOf("artist" to listOf(name), "count" to listOf("100")))
        .getAsJsonObject("topSongs")?.objects("song").orEmpty().map { it.str("id") }.filter { it in ids }.distinct()
    @Synchronized fun setPlaylist(id: String?, name: String, ids: List<String>): MusicList {
        return database.runInTransaction<MusicList>(java.util.concurrent.Callable {
            check(currentSession()) { "账户已切换，请重试" }
            val listId = id?.let(::resolvePlaylistId) ?: "local:${UUID.randomUUID()}"
            val lib = library(); val old = lib.playlists.find { it.id == listId }
            check(old?.readOnly != true) { "这是只读歌单" }
            val value = (old ?: MusicList(listId, name)).copy(name = name, tracks = ids, pending = true, syncError = null)
            saveLibrary(lib.copy(playlists = if (old == null) lib.playlists + value else lib.playlists.map { if (it.id == listId) value else it }))
            enqueue("replacePlaylist", mapOf("name" to listOf(name), "songId" to ids), listId)
            value
        })
    }
    @Synchronized fun deletePlaylist(id: String) {
        database.runInTransaction {
            check(currentSession()) { "账户已切换，请重试" }
            val resolved = resolvePlaylistId(id)
            check(library().playlists.find { it.id == resolved }?.readOnly != true) { "这是只读歌单" }
            saveLibrary(library().let { it.copy(playlists = it.playlists.filterNot { p -> p.id == resolved }) })
            enqueue("deletePlaylist", one("id", resolved), resolved)
        }
    }
    private fun resolvePlaylistId(id: String) = get("playlist-id", id, String::class.java) ?: id
    private fun playlistReadOnly(p: JsonObject) = p.get("readonly")?.takeUnless { it.isJsonNull }?.asBoolean == true ||
        p.str("owner").let { it.isNotBlank() && it != user }

    fun addTrackToPlaylist(id: String, songId: String): PlaylistEditResult = editPlaylist(id) { p ->
        if (songId in p.tracks) Triple(PlaylistEditResult.ALREADY_PRESENT, p.tracks, emptyMap())
        else Triple(PlaylistEditResult.SAVED, p.tracks + songId, one("songId", songId))
    }
    fun removePlaylistEntry(ref: PlaylistEntryRef): PlaylistEditResult = editPlaylist(ref.playlistId, true) { p ->
        val next = PlaylistEdits.remove(p.tracks, ref)
        if (next == null) Triple(PlaylistEditResult.CHANGED, p.tracks, emptyMap())
        else Triple(PlaylistEditResult.SAVED, next, mapOf("before" to p.tracks, "after" to next, "index" to listOf(ref.index.toString())))
    }
    private fun editPlaylist(id: String, remove: Boolean = false, edit: (MusicList) -> Triple<PlaylistEditResult, List<String>, Map<String, List<String>>>): PlaylistEditResult =
        database.runInTransaction<PlaylistEditResult>(java.util.concurrent.Callable {
            check(currentSession()) { "账户已切换，请重试" }
            val lib = library(); val resolved = resolvePlaylistId(id)
            val p = lib.playlists.find { it.id == resolved } ?: return@Callable PlaylistEditResult.MISSING
            if (p.readOnly) return@Callable PlaylistEditResult.READ_ONLY
            val (result, tracks, params) = edit(p)
            if (result == PlaylistEditResult.SAVED) {
                saveLibrary(lib.copy(playlists = lib.playlists.map { if (it.id == resolved) it.copy(tracks = tracks, pending = true) else it }))
                enqueue(if (remove) "removePlaylistEntry" else "addPlaylistTrack", params, resolved)
            }
            result
        })

    private fun enqueue(method: String, params: Map<String, List<String>>, playlist: String? = null) {
        // UUID ordering is not chronological when multiple edits land in one millisecond.
        val id = UUID.randomUUID().toString()
        val last = dao.list(account, "outbox").maxOfOrNull { it.updated } ?: 0L
        dao.put(LocalRow().also { it.account = account; it.kind = "outbox"; it.id = id
            it.updated = maxOf(System.currentTimeMillis(), last + 1)
            it.payload = gson.toJson(PendingOperation(id, method, params, playlist)) })
    }
    suspend fun syncPending() = syncMutex.withLock { withContext(Dispatchers.IO) { flushOutbox() } }
    private fun flushOutbox() {
        if (offline) return
        for (op in list("outbox", PendingOperation::class.java)) {
            try {
                val resolved = op.playlistId?.let(::resolvePlaylistId)
                var newId: String? = null
                var remoteTracks: List<String>? = null
                when (op.method) {
                    "addPlaylistTrack", "removePlaylistEntry" -> remoteTracks = syncPlaylistEdit(op, requireNotNull(resolved))
                    "replacePlaylist" -> {
                        if (resolved?.startsWith("local:") == true) {
                            val attempted = get("outbox-create-attempt", op.id, Boolean::class.java) == true
                            if (attempted) {
                                val matches = request("getPlaylists").getAsJsonObject("playlists")?.objects("playlist").orEmpty().filter { it.str("name") == op.params["name"]?.firstOrNull() }
                                    .filter { p -> request("getPlaylist", one("id", p.str("id"))).getAsJsonObject("playlist")?.objects("entry").orEmpty().map { it.str("id") } == op.params["songId"].orEmpty() }
                                check(matches.size == 1) { "新建歌单结果未确认，请先核对服务器歌单再重试" }
                                newId = matches.single().str("id")
                            } else {
                                put("outbox-create-attempt", op.id, true)
                                newId = request("createPlaylist", op.params).getAsJsonObject("playlist")?.str("id")?.takeIf { it.isNotBlank() }
                                    ?: error("服务器未返回新歌单 ID")
                            }
                        } else {
                            request("createPlaylist", mapOf("playlistId" to listOf(resolved!!), "songId" to op.params.getValue("songId")))
                            request("updatePlaylist", mapOf("playlistId" to listOf(resolved), "name" to op.params.getValue("name")))
                        }
                    }
                    "deletePlaylist" -> if (resolved?.startsWith("local:") != true) request(op.method, one("id", resolved!!))
                    else -> request(op.method, op.params)
                }
                database.runInTransaction {
                    if (newId != null) put("playlist-id", requireNotNull(resolved), requireNotNull(newId))
                    dao.delete(account, "outbox", op.id)
                    dao.delete(account, "outbox-create-attempt", op.id)
                    dao.delete(account, "playlist-attempt", op.id)
                    val target = newId ?: resolved
                    val remaining = list("outbox", PendingOperation::class.java).any { it.playlistId?.let(::resolvePlaylistId) == target }
                    saveLibrary(library().let { l -> l.copy(playlists = l.playlists.map { p ->
                        if (p.id == resolved || p.id == target) p.copy(id = target ?: p.id, pending = remaining, syncError = null,
                            tracks = if (!remaining && remoteTracks != null) remoteTracks!! else p.tracks) else p
                    }) })
                }
            } catch (e: Exception) {
                op.playlistId?.let { id -> database.runInTransaction {
                    val resolved = resolvePlaylistId(id)
                    saveLibrary(library().let { l -> l.copy(playlists = l.playlists.map { if (it.id == resolved) it.copy(pending = true,
                        syncError = e.message ?: "同步失败，请重试") else it }) })
                } }
                break // Preserve dependency order. Never replay an uncertain positional removal.
            }
        }
    }

    private fun readRemotePlaylist(id: String): JsonObject = request("getPlaylist", one("id", id)).getAsJsonObject("playlist")
        ?: error("服务器歌单不存在，请重新载入")

    private fun syncPlaylistEdit(op: PendingOperation, id: String): List<String> {
        val remote = readRemotePlaylist(id)
        check(!playlistReadOnly(remote)) { "服务器不允许修改此歌单（只读或无权限）" }
        val ids = remote.objects("entry").map { it.str("id") }
        val attempt = get("playlist-attempt", op.id, PendingOperation::class.java)
        val before: List<String>
        val after: List<String>
        val params: Map<String, List<String>>
        if (op.method == "addPlaylistTrack") {
            val song = op.params.getValue("songId").single()
            if (song in ids) return ids
            if (attempt != null) check(ids == attempt.params["before"]) { "歌单内容已变化，添加结果需核对，请重新载入" }
            before = ids; after = ids + song
            params = mapOf("playlistId" to listOf(id), "songIdToAdd" to listOf(song))
        } else {
            before = op.params.getValue("before"); after = op.params.getValue("after")
            when (PlaylistEdits.removalState(ids, before, after)) {
                "done" -> return ids
                "conflict" -> error("歌单顺序已变化，已停止移除，请重新载入服务器歌单")
            }
            params = mapOf("playlistId" to listOf(id), "songIndexToRemove" to op.params.getValue("index"))
        }
        put("playlist-attempt", op.id, op.copy(params = mapOf("before" to before, "after" to after)))
        request("updatePlaylist", params)
        val verified = readRemotePlaylist(id).objects("entry").map { it.str("id") }
        check(verified == after) { "服务器歌单结果与预期不同，请重新载入后核对" }
        return verified
    }

    /** Explicitly confirmed conflict recovery; no playback/download state is changed. */
    suspend fun reloadPlaylist(id: String) = syncMutex.withLock { withContext(Dispatchers.IO) {
        val resolved = resolvePlaylistId(id)
        val remote = readRemotePlaylist(resolved)
        database.runInTransaction {
            check(currentSession()) { "账户已切换，请重试" }
            list("outbox", PendingOperation::class.java).filter { it.playlistId?.let(::resolvePlaylistId) == resolved }.forEach {
                dao.delete(account, "outbox", it.id); dao.delete(account, "playlist-attempt", it.id); dao.delete(account, "outbox-create-attempt", it.id)
            }
            saveLibrary(library().let { l -> l.copy(playlists = l.playlists.map { if (it.id == resolved) it.copy(
                name = remote.str("name").ifBlank { it.name }, tracks = remote.objects("entry").map { song -> song.str("id") },
                pending = false, syncError = null, readOnly = playlistReadOnly(remote)) else it }) })
        }
    } }
    @Synchronized fun record(id: String, elapsedMs: Long, completed: Boolean = false, skip: Boolean = false) {
        val old = get("listening", id, Listening::class.java) ?: Listening(id)
        put("listening", id, old.copy(heardMs = old.heardMs + elapsedMs.coerceAtLeast(0), completions = old.completions + if (completed) 1 else 0,
            skips = old.skips + if (skip) 1 else 0, lastHeard = if (elapsedMs > 0) System.currentTimeMillis() else old.lastHeard))
    }
    @Synchronized fun dislike(id: String) { put("listening", id, (get("listening", id, Listening::class.java) ?: Listening(id)).copy(disliked = true)) }
    companion object {
        private val sessionLock = Any()
        private var sessionRepository: JiayinRepository? = null
        @Volatile private var instance: JiayinRepository? = null
        fun get(context: Context): JiayinRepository = instance ?: synchronized(this) { instance ?: JiayinRepository(context.applicationContext).also { instance = it } }
        fun one(key: String, value: String) = mapOf(key to listOf(value))
        fun parseTrack(o: JsonObject): Track = Track(o.str("id"), o.str("title"), o.str("displayArtist").ifBlank { o.str("artist") }.ifBlank { "未知歌手" }, o.str("album"), o.str("albumId"), o.str("artistId"), o.str("coverArt"), o.num("duration"), o.str("genre"), runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(o.str("created"))?.time ?: 0 }.getOrDefault(0), isStarred(o), o.str("path"), o.num("track"), o.num("discNumber"), parseReleaseDate(o),
            artists = o.objects("artists").map { ArtistRef(it.str("id"), it.str("name")) }.takeIf { it.isNotEmpty() },
            albumArtists = o.objects("albumArtists").map { ArtistRef(it.str("id"), it.str("name")) }.takeIf { it.isNotEmpty() },
            genres = o.objects("genres").map { it.str("name") }.takeIf { it.isNotEmpty() }, recordingId = o.str("musicBrainzId").takeIf { it.isNotBlank() },
            albumVersion = o.str("albumVersion").takeIf { it.isNotBlank() }, language = o.str("language").takeIf { it.isNotBlank() }, compilation = o.get("isCompilation")?.asBoolean ?: false)
        fun isStarred(o: JsonObject): Boolean {
            val value = o.get("starred")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
            return if (value.isBoolean) value.asBoolean else value.asString.isNotBlank()
        }
        fun parseReleaseDate(o: JsonObject): Long {
            val date = o.get("originalReleaseDate")?.takeIf { it.isJsonObject }?.asJsonObject ?: o.get("releaseDate")?.takeIf { it.isJsonObject }?.asJsonObject
            val year = date?.num("year")?.takeIf { it > 0 } ?: o.num("year")
            return if (year > 0) year * 10000L + (date?.num("month") ?: 0) * 100 + (date?.num("day") ?: 0) else 0
        }
    }
}
fun JsonObject.str(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
fun JsonObject.num(name: String): Int = get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0
fun JsonObject.objects(name: String): List<JsonObject> = getAsJsonArray(name)?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.orEmpty()
