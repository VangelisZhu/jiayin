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
    val dao = LocalDatabase.get(context).rows()
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
    fun library(): Library = get("library", "snapshot", Library::class.java) ?: Library()
    fun listening() = list("listening", Listening::class.java).associateBy { it.id }
    fun saveLibrary(value: Library) { put("library", "snapshot", value); put("sync-meta", "last", value.syncedAt) }

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
            .addQueryParameter("c", "Jiayin").addQueryParameter("id", id).apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
    }

    private fun requestAt(address: String, user: String, token: String, salt: String, method: String, params: Map<String, List<String>> = emptyMap()): JsonObject {
        val form = FormBody.Builder().add("u", user).add("t", token).add("s", salt).add("v", "1.16.1").add("c", "Jiayin").add("f", "json")
        params.forEach { (key, values) -> values.forEach { form.add(key, it) } }
        return http.newCall(Request.Builder().url("$address/rest/$method.view").post(form.build()).build()).execute().use { response ->
            if (response.code >= 500) throw java.io.IOException("服务器暂时不可用（HTTP ${response.code}）")
            if (response.code == 401 || response.code == 403) throw AuthenticationRequired()
            check(response.isSuccessful) { "服务器返回 HTTP ${response.code}" }
            val root = JsonParser.parseString(response.body?.string() ?: error("服务器返回空内容")).asJsonObject.getAsJsonObject("subsonic-response") ?: error("不是有效的 Subsonic 服务")
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
            MusicList(p.str("id"), p.str("name"), contents?.objects("entry").orEmpty().map { it.str("id") }, p.str("coverArt"))
        }
        // A failed remote mutation retains the optimistic local view until retry succeeds.
        val pending = list("outbox", PendingOperation::class.java)
        val local = library()
        val pendingIds = pending.mapNotNull { it.playlistId }.toSet()
        val mergedLists = playlists.filterNot { it.id in pendingIds } + local.playlists.filter { it.id in pendingIds || it.id.startsWith("local:") }
        val favorites = pending.filter { (it.method == "star" || it.method == "unstar") && it.params.containsKey("id") }.associate { it.params.getValue("id").first() to (it.method == "star") }
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
        Library(enhanced.map { it.copy(starred = favorites[it.id] ?: it.starred) }, mergedLists, System.currentTimeMillis()).also { saveLibrary(it) }
    } }
    @Synchronized fun favorite(track: Track) {
        val lib = library(); saveLibrary(lib.copy(tracks = lib.tracks.map { if (it.id == track.id) it.copy(starred = !track.starred) else it }))
        enqueue(if (track.starred) "unstar" else "star", one("id", track.id))
    }
    @Synchronized fun favoriteArtist(profile: ArtistProfile) {
        val old = get("artist", profile.id, ArtistProfile::class.java) ?: profile
        put("artist", profile.id, old.copy(starred = !old.starred))
        if (!profile.id.startsWith("name:")) enqueue(if (old.starred) "unstar" else "star", one("artistId", profile.id))
    }
    fun topSongs(name: String, ids: Set<String>): List<String> = request("getTopSongs", mapOf("artist" to listOf(name), "count" to listOf("100")))
        .getAsJsonObject("topSongs")?.objects("song").orEmpty().map { it.str("id") }.filter { it in ids }.distinct()
    @Synchronized fun setPlaylist(id: String?, name: String, ids: List<String>): MusicList {
        val listId = id ?: "local:${UUID.randomUUID()}"
        val lib = library(); val value = MusicList(listId, name, ids, pending = true)
        saveLibrary(lib.copy(playlists = lib.playlists.filterNot { it.id == listId } + value))
        enqueue("replacePlaylist", mapOf("name" to listOf(name), "songId" to ids), listId)
        return value
    }
    @Synchronized fun deletePlaylist(id: String) {
        saveLibrary(library().let { it.copy(playlists = it.playlists.filterNot { p -> p.id == id }) })
        enqueue("deletePlaylist", one("id", id), id)
    }
    private fun enqueue(method: String, params: Map<String, List<String>>, playlist: String? = null) {
        val id = UUID.randomUUID().toString(); put("outbox", id, PendingOperation(id, method, params, playlist))
    }
    suspend fun syncPending() = syncMutex.withLock { withContext(Dispatchers.IO) { flushOutbox() } }
    private fun flushOutbox() {
        for (op in list("outbox", PendingOperation::class.java)) {
            try {
                val resolved = op.playlistId?.let { get("playlist-id", it, String::class.java) ?: it }
                when (op.method) {
                    "replacePlaylist" -> {
                        if (resolved?.startsWith("local:") == true) {
                            val attempted = get("outbox-create-attempt", op.id, Boolean::class.java) == true
                            if (attempted) {
                                val matches = request("getPlaylists").getAsJsonObject("playlists")?.objects("playlist").orEmpty().filter { it.str("name") == op.params["name"]?.firstOrNull() }
                                    .filter { p -> request("getPlaylist", one("id", p.str("id"))).getAsJsonObject("playlist")?.objects("entry").orEmpty().map { it.str("id") } == op.params["songId"].orEmpty() }
                                if (matches.size != 1) break // Require explicit user resolution; no duplicate playlist.
                                val remote = matches.single().str("id")
                                put("playlist-id", resolved, remote)
                                saveLibrary(library().let { l -> l.copy(playlists = l.playlists.map { if (it.id == resolved) it.copy(id = remote, pending = false) else it }) })
                                dao.delete(account, "outbox", op.id); dao.delete(account, "outbox-create-attempt", op.id); continue
                            }
                            put("outbox-create-attempt", op.id, true)
                            val result = request("createPlaylist", op.params)
                            val remote = result.getAsJsonObject("playlist")?.str("id")?.takeIf { it.isNotBlank() } ?: error("服务器未返回新歌单 ID")
                            put("playlist-id", resolved, remote)
                            saveLibrary(library().let { l -> l.copy(playlists = l.playlists.map { if (it.id == resolved) it.copy(id = remote, pending = false) else it }) })
                        } else {
                            // createPlaylist with playlistId replaces contents idempotently (Subsonic API).
                            request("createPlaylist", mapOf("playlistId" to listOf(resolved!!), "songId" to op.params.getValue("songId")))
                            request("updatePlaylist", mapOf("playlistId" to listOf(resolved), "name" to op.params.getValue("name")))
                            saveLibrary(library().let { l -> l.copy(playlists = l.playlists.map { if (it.id == resolved) it.copy(pending = false) else it }) })
                        }
                    }
                    "deletePlaylist" -> if (resolved?.startsWith("local:") != true) request(op.method, one("id", resolved!!))
                    else -> request(op.method, op.params)
                }
                dao.delete(account, "outbox", op.id)
                dao.delete(account, "outbox-create-attempt", op.id)
            } catch (_: Exception) { break } // preserve ordered outbox; UI explicitly shows pending status
        }
    }
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
