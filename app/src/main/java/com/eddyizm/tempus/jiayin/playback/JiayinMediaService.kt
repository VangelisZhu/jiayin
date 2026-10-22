package com.eddyizm.tempus.jiayin.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.eddyizm.tempus.jiayin.JiayinActivity
import com.eddyizm.tempus.jiayin.core.*
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.service.QueuePreloader
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.MusicUtil
import com.eddyizm.tempus.util.ReplayGainUtil
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

@UnstableApi
class JiayinMediaService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storage = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val commands = Mutex()
    private lateinit var repo: JiayinRepository
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var focus: AudioFocusCoordinator
    private var state = QueueState()
    private var tracks: Map<String, Track> = emptyMap()
    private var cause = PauseCause.RESTORED
    private var error: String? = null
    private var installing = false
    private var generation = 0L
    private var retryCount = 0
    private var retryJob: Job? = null
    private var lastKey: String? = null
    private var heardMs = 0L
    private var heardThisPlay = 0L
    private var lastTick = 0L
    private var offsetMs = 0L
    private var offsetKey: String? = null
    private var sleepUntil = 0L
    private var sleepJob: Job? = null
    private var goingBack = false
    private var transitionReason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
    private var completionKey: String? = null
    private val commandNumber = AtomicLong()
    private val noisy = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { pause(PauseCause.HEADSET) } }
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scope.launch { refreshUpcoming(); if (cause == PauseCause.NETWORK && retryCount < 3) recover(generation) } }
    }
    override fun onCreate() {
        super.onCreate()
        repo = JiayinRepository.get(this); repo.bindUpstream()
        focus = AudioFocusCoordinator(this) { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause(PauseCause.FOCUS, abandon = false)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.volume = .2f
                AudioManager.AUDIOFOCUS_GAIN -> { player.volume = 1f; if (ResumePolicy(repo.settings.getBoolean("auto-resume", false)).mayResume(cause)) play() }
            }
        }
        player = ExoPlayer.Builder(this, DownloadUtil.buildRenderersFactory(this, false))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DownloadUtil.getCacheDataSourceFactory(this))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))) // retries are bounded here, once
            .setAudioAttributes(AudioAttributes.DEFAULT, false).setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK).build()
        val controls = object : ForwardingPlayer(player) {
            override fun getCurrentPosition() = absolutePosition()
            override fun getContentPosition() = absolutePosition()
            override fun getBufferedPosition() = player.bufferedPosition + if (offsetKey == state.current?.key) offsetMs else 0
            override fun getContentBufferedPosition() = bufferedPosition
            override fun getDuration(): Long = state.current?.trackId?.let { tracks[it]?.duration?.takeIf { d -> d > 0 }?.times(1000L) } ?: player.duration
            override fun getContentDuration() = duration
            override fun isCurrentMediaItemSeekable() = duration > 0
            override fun getAvailableCommands(): Player.Commands = player.availableCommands.buildUpon().add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build()
            override fun getCurrentTimeline(): Timeline {
                return object : ForwardingTimeline(player.currentTimeline) {
                    override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window {
                        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                        val duration = state.entries.getOrNull(windowIndex)?.trackId?.let { tracks[it]?.duration } ?: 0
                        if (duration > 0) { window.durationUs = duration * 1_000_000L; window.isSeekable = true }
                        return window
                    }
                }
            }
            override fun play() = this@JiayinMediaService.play()
            override fun pause() = this@JiayinMediaService.pause(PauseCause.USER)
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }
            override fun seekToNextMediaItem() = next()
            override fun seekToNext() = next()
            override fun seekToPreviousMediaItem() = previous()
            override fun seekToPrevious() = previous()
            override fun seekTo(positionMs: Long) = seekAbsolute(positionMs)
        }
        val intent = Intent(this, JiayinActivity::class.java).setAction("jiayin.player")
        session = MediaLibrarySession.Builder(this, controls, Callback())
            .setSessionActivity(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { transitionReason = reason }
            override fun onEvents(p: Player, events: Player.Events) {
                if (installing) return
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    val index = p.currentMediaItemIndex.coerceAtLeast(0)
                    val key = state.entries.getOrNull(index)?.key
                    if (key != lastKey) {
                        flushListening(completed = transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                        val history = if (!goingBack && lastKey != null) (state.history + lastKey!!).takeLast(2000) else state.history
                        state = state.copy(index = index, positionMs = 0, history = history)
                        lastKey = key; completionKey = null; heardMs = 0; heardThisPlay = 0; retryCount = 0; retryJob?.cancel()
                        if (key != offsetKey) { offsetMs = 0; offsetKey = null }
                        generation++
                        p.currentMediaItem?.let { ReplayGainUtil.applyGain(p, it) }
                        refreshUpcoming()
                    }
                    goingBack = false
                    if (transitionReason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                        flushListening(completed = true); completionKey = null; heardThisPlay = 0
                    }
                }
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    if (!p.isPlaying) flushListening()
                    lastTick = SystemClock.elapsedRealtime(); persist()
                }
                if (p.playbackState == Player.STATE_ENDED && state.repeat == Player.REPEAT_MODE_OFF) {
                    flushListening(completed = true)
                    if (repo.settings.getBoolean("recommend-autoplay", false)) continueRecommendations()
                }
                publish()
            }
            override fun onPlayerError(exception: PlaybackException) {
                flushListening(); cause = PauseCause.NETWORK
                val transient = exception.errorCode in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                error = if (transient && retryCount < 3) "连接中断，正在恢复 ${retryCount + 1}/3" else "播放失败，点播放重试（${exception.errorCodeName}）"
                if (transient && retryCount < 3) {
                    val expected = generation
                    retryJob?.cancel(); retryJob = scope.launch { delay((retryCount + 1) * 1000L); recover(expected) }
                } else player.playWhenReady = false
                persist(); publish()
            }
        })
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
        scope.launch {
            // Restore and button commands share the same lock. An old paused queue must never
            // supersede a song the user has just chosen while startup IO is still running.
            commands.withLock {
                val restored = withContext(Dispatchers.IO) { tracks = repo.library().tracks.associateBy { it.id }; repo.get("queue", "active", QueueState::class.java) }
                if (state.entries.isEmpty() && restored != null) install(restored, false)
                publish()
            }
            while (isActive) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                if (player.isPlaying && lastTick > 0) { val elapsed = (now - lastTick).coerceIn(0, 1500); heardMs += elapsed; heardThisPlay += elapsed }
                lastTick = now
                if (player.mediaItemCount > 0) state = state.copy(positionMs = absolutePosition())
                publish()
                if (player.isPlaying && now / 2000 != (now - 500) / 2000) { persist(); flushListening() }
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session
    private fun absolutePosition() = player.currentPosition.coerceAtLeast(0) + if (state.current?.key == offsetKey) offsetMs else 0
    private fun publish() { PlaybackHub.mutable.value = PlaybackState(state.copy(positionMs = if (player.mediaItemCount > 0) absolutePosition() else state.positionMs), state.current?.trackId?.let { tracks[it] }, player.isPlaying, player.playbackState == Player.STATE_BUFFERING, cause, error, true, sleepUntil) }
    private fun persist() { val snapshot = state.copy(positionMs = if (player.mediaItemCount > 0) absolutePosition() else state.positionMs); storage.launch { repo.put("queue", "active", snapshot) } }
    private fun flushListening(completed: Boolean = false, skip: Boolean = false) {
        val id = state.current?.trackId ?: return
        val elapsed = heardMs; heardMs = 0
        val duration = (tracks[id]?.duration ?: 0) * 1000L
        val countCompletion = completed && duration > 0 && heardThisPlay >= duration * .8 && completionKey != state.current?.key
        if (countCompletion) completionKey = state.current?.key
        if (elapsed > 0 || countCompletion || skip) storage.launch { repo.record(id, elapsed, countCompletion, skip) }
    }
    private fun child(track: Track) = Child(track.id, title = track.title, album = track.album, artist = track.artist, coverArtId = track.cover.ifBlank { null }, duration = track.duration, genre = track.genre, albumId = track.albumId, artistId = track.artistId)
    private suspend fun install(next: QueueState, start: Boolean) {
        val request = commandNumber.incrementAndGet()
        val map = withContext(Dispatchers.IO) { repo.library().tracks.associateBy { it.id } }
        val valid = next.entries.filter { it.trackId in map }
        val currentKey = next.current?.key
        val targetIndex = valid.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        val items = withContext(Dispatchers.IO) { valid.map { MappingUtil.mapMediaItem(child(map.getValue(it.trackId))) }.toMutableList() }
        if (request != commandNumber.get()) return
        flushListening(); installing = true
        tracks = map; state = next.copy(entries = valid, index = targetIndex)
        offsetMs = 0; offsetKey = null; generation++; retryCount = 0; retryJob?.cancel(); error = null
        if (lastKey != state.current?.key) { heardThisPlay = 0; completionKey = null }
        lastKey = state.current?.key
        val target = items.getOrNull(targetIndex)
        val targetUri = target?.localConfiguration?.uri
        if (state.positionMs > 0 && targetUri?.scheme?.startsWith("http") == true && targetUri.getQueryParameter("format")?.let { it != "raw" } == true && !DownloadUtil.getDownloadTracker(this).isDownloaded(state.current?.trackId.orEmpty())) {
            offsetMs = state.positionMs / 1000 * 1000; offsetKey = state.current?.key
            items[targetIndex] = target.buildUpon().setUri(MusicUtil.getStreamUri(state.current!!.trackId, (offsetMs / 1000).toInt())).build()
        }
        player.playWhenReady = false
        if (items.isEmpty()) player.clearMediaItems() else player.setMediaItems(items, targetIndex, state.positionMs - offsetMs)
        player.repeatMode = state.repeat; player.shuffleModeEnabled = false
        player.prepare(); installing = false
        if (start) play() else focus.abandon()
        persist(); publish(); QueuePreloader.preload(this, player)
    }
    private fun play() {
        if (player.mediaItemCount == 0) return
        retryJob?.cancel(); error = null; retryCount = 0
        // Android 15+ requires a top app or a foreground service to request focus.
        // Reuse Media3's notification rather than creating a second notification.
        runCatching { onUpdateNotification(session, true) }.onFailure {
            cause = PauseCause.USER; error = "请打开家音后点播放"; publish(); return
        }
        if (!focus.request()) { cause = PauseCause.FOCUS; error = "其他应用正在使用声音，稍后再点播放"; publish(); return }
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) {
            val expected = generation; scope.launch { recover(expected, explicit = true) }; return
        }
        if (player.playbackState == Player.STATE_ENDED) seekAbsolute(0)
        cause = PauseCause.NONE; player.volume = 1f; player.play(); publish()
    }
    private fun pause(why: PauseCause, abandon: Boolean = true) {
        // Late focus callbacks must never overwrite a deliberate pause/headset loss.
        if (why == PauseCause.FOCUS && !player.playWhenReady && cause != PauseCause.NONE) return
        cause = why; retryJob?.cancel(); generation++; flushListening(); player.pause()
        if (abandon) focus.abandon()
        persist(); publish()
    }
    private fun next() {
        if (state.entries.isEmpty()) return
        flushListening(skip = player.isPlaying)
        if (state.index < state.entries.lastIndex) player.seekTo(state.index + 1, 0)
        else if (state.repeat == Player.REPEAT_MODE_ALL) player.seekTo(0, 0)
        else { pause(PauseCause.USER); return }
        play()
    }
    private fun previous() {
        val key = state.history.lastOrNull()
        val index = state.entries.indexOfFirst { it.key == key }
        if (index >= 0) { state = state.copy(history = state.history.dropLast(1)); goingBack = true; player.seekTo(index, 0) } else seekAbsolute(0)
        play()
    }
    private fun seekAbsolute(position: Long) {
        generation++; retryJob?.cancel()
        val target = position.coerceIn(0, ((state.current?.trackId?.let { tracks[it]?.duration } ?: Int.MAX_VALUE).toLong() * 1000 - 1).coerceAtLeast(0))
        val currentOffset = if (offsetKey == state.current?.key) offsetMs else 0
        val uri = player.currentMediaItem?.localConfiguration?.uri
        val transcoded = uri?.scheme?.startsWith("http") == true && uri.getQueryParameter("format")?.let { it != "raw" } == true
        if (transcoded && !DownloadUtil.getDownloadTracker(this).isDownloaded(state.current?.trackId.orEmpty())) {
            val wasPlaying = player.playWhenReady; scope.launch { restartAt(target, wasPlaying) }
        } else player.seekTo((target - currentOffset).coerceAtLeast(0))
        state = state.copy(positionMs = target); persist(); publish()
    }
    private suspend fun recover(expected: Long, explicit: Boolean = false) {
        if (generation != expected || (!explicit && cause != PauseCause.NETWORK) || state.current == null) return
        if (!explicit && retryCount >= 3) return
        retryCount++
        restartAt(absolutePosition(), true)
    }
    private suspend fun restartAt(position: Long, resume: Boolean) {
        val key = state.current?.key ?: return
        val index = player.currentMediaItemIndex
        player.currentMediaItem ?: return
        val track = tracks[state.current?.trackId] ?: return
        val expected = generation
        val item = withContext(Dispatchers.IO) { MappingUtil.mapMediaItem(child(track)) }
        if (generation != expected || state.current?.key != key) return
        val uri = item.localConfiguration?.uri ?: return
        val transcoded = uri.scheme?.startsWith("http") == true && uri.getQueryParameter("format")?.let { it != "raw" } == true && !DownloadUtil.getDownloadTracker(this).isDownloaded(track.id)
        val start = if (transcoded) (position / 1000) * 1000 else 0
        val adjusted = if (transcoded) item.buildUpon().setUri(MusicUtil.getStreamUri(track.id, (start / 1000).toInt())).build() else item
        offsetMs = start; offsetKey = key
        installing = true
        player.replaceMediaItem(index, adjusted); player.seekTo(index, position - start); player.prepare()
        installing = false
        if (resume && focus.request()) { cause = PauseCause.NONE; player.play() }
        error = null; publish()
    }
    private fun refreshUpcoming() {
        val expected = generation
        val indices = (player.currentMediaItemIndex + 1..(player.currentMediaItemIndex + 2).coerceAtMost(player.mediaItemCount - 1)).toList()
        val upcoming = indices.mapNotNull { i -> state.entries.getOrNull(i)?.let { e -> tracks[e.trackId]?.let { Triple(i, e.key, it) } } }
        scope.launch {
            val items = withContext(Dispatchers.IO) { upcoming.map { (i, key, t) -> Triple(i, key, MappingUtil.mapMediaItem(child(t))) } }
            if (expected != generation) return@launch
            installing = true
            for ((i, key, item) in items) if (state.entries.getOrNull(i)?.key == key) player.replaceMediaItem(i, item)
            installing = false
            QueuePreloader.preload(this@JiayinMediaService, player)
        }
    }
    /** Apply queue edits in-place: preserve the current source/buffer and audible position. */
    private suspend fun editQueue(next: QueueState) {
        if (next.current?.key != state.current?.key || next.entries.isEmpty()) { install(next, player.playWhenReady); return }
        val expected = generation
        val previous = state
        val existing = previous.entries.map { it.key }.toMutableList()
        val missing = next.entries.filter { it.key !in existing }
        val added = withContext(Dispatchers.IO) { missing.associate { it.key to MappingUtil.mapMediaItem(child(tracks.getValue(it.trackId))) } }
        if (expected != generation) { error = "播放进度已变化，请重试队列操作"; publish(); return }
        installing = true
        val cut = state.index + 1
        if (next.index == state.index && next.entries.take(cut) == previous.entries.take(cut)) {
            val currentItems = previous.entries.mapIndexed { i, e -> e.key to player.getMediaItemAt(i) }.toMap()
            val tail = next.entries.drop(cut).map { currentItems[it.key] ?: added.getValue(it.key) }
            player.replaceMediaItems(cut, player.mediaItemCount, tail)
            state = next.copy(positionMs = absolutePosition()); generation++; installing = false
            persist(); publish(); refreshUpcoming(); return
        }
        val wanted = next.entries.map { it.key }.toSet()
        for (i in existing.indices.reversed()) if (existing[i] !in wanted) { player.removeMediaItem(i); existing.removeAt(i) }
        next.entries.forEachIndexed { i, entry ->
            val at = existing.indexOf(entry.key)
            if (at < 0) { player.addMediaItem(i, added.getValue(entry.key)); existing.add(i, entry.key) }
            else if (at != i) { player.moveMediaItem(at, i); existing.add(i, existing.removeAt(at)) }
        }
        state = next.copy(positionMs = absolutePosition()); generation++; installing = false
        persist(); publish(); refreshUpcoming()
    }
    private fun continueRecommendations() {
        if (scope.coroutineContext[Job]?.isActive != true) return
        scope.launch {
            val rec = withContext(Dispatchers.IO) { RecommendationEngine.recommend(tracks.values.toList(), repo.listening(), System.currentTimeMillis(), from = tracks[state.current?.trackId]).map { it.id }.filterNot { id -> state.entries.any { it.trackId == id } } }
            if (rec.isNotEmpty() && player.playbackState == Player.STATE_ENDED) install(QueueEngine.insert(state, rec, false).copy(index = state.entries.size, positionMs = 0), true)
        }
    }
    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            if (controller.uid == android.os.Process.myUid()) commands.add(SessionCommand(PlaybackHub.COMMAND, Bundle.EMPTY))
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build()).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (command.customAction != PlaybackHub.COMMAND || controller.uid != android.os.Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            val result = SettableFuture.create<SessionResult>()
            scope.launch {
                try {
                    commands.withLock { execute(args) }
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                } catch (e: CancellationException) { result.cancel(false); throw e }
                catch (e: Exception) {
                    error = e.message ?: "操作失败"; publish()
                    result.set(SessionResult(SessionError.ERROR_UNKNOWN))
                }
            }
            return result
        }
    }
    private suspend fun execute(args: Bundle) {
        when (args.getString("action")) {
            "start" -> {
                val staged = withContext(Dispatchers.IO) { val id = args.getString("id")!!; repo.get("command", id, QueueState::class.java).also { repo.dao.delete(repo.account, "command", id) } } ?: error("播放队列读取失败，请重新点歌")
                install(staged, true)
            }
            "toggle" -> if (player.isPlaying || player.playWhenReady) pause(PauseCause.USER) else play()
            "play" -> play()
            "pause" -> pause(PauseCause.USER)
            "next" -> next()
            "previous" -> previous()
            "seek" -> seekAbsolute(args.getLong("position"))
            "shuffle" -> editQueue(QueueEngine.toggle(state.copy(positionMs = absolutePosition()), tracks))
            "reshuffle" -> {
                val original = state.copy(shuffled = false, positionMs = absolutePosition())
                editQueue(QueueEngine.toggle(original, tracks))
            }
            "repeat" -> { state = state.copy(repeat = (state.repeat + 1) % 3); player.repeatMode = state.repeat; persist() }
            "insert" -> {
                if (tracks[args.getString("track")] == null) tracks = withContext(Dispatchers.IO) { repo.library().tracks.associateBy { it.id } }
                editQueue(QueueEngine.insert(state.copy(positionMs = absolutePosition()), listOf(args.getString("track")!!), args.getBoolean("next")))
            }
            "jump" -> { val i = args.getInt("index"); if (i in state.entries.indices) { player.seekTo(i, 0); play() } }
            "move" -> editQueue(QueueEngine.move(state.copy(positionMs = absolutePosition()), args.getInt("from"), args.getInt("to")))
            "remove" -> editQueue(QueueEngine.remove(state.copy(positionMs = absolutePosition()), args.getInt("index")))
            "reorder" -> {
                val keys = args.getStringArrayList("keys").orEmpty(); val byKey = state.entries.associateBy { it.key }
                if (keys.size == byKey.size && keys.toSet() == byKey.keys) {
                    val entries = keys.map { byKey.getValue(it) }; editQueue(state.copy(entries = entries, index = entries.indexOfFirst { it.key == state.current?.key }))
                }
            }
            "foreground" -> if (ResumePolicy(repo.settings.getBoolean("auto-resume", false)).mayResume(cause)) play()
            "sleep" -> {
                sleepJob?.cancel(); val minutes = args.getInt("minutes")
                sleepUntil = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0
                if (minutes > 0) sleepJob = scope.launch { delay(minutes * 60_000L); sleepUntil = 0; pause(PauseCause.SLEEP) }
            }
        }
        publish()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { persist(); if (!player.playWhenReady) stopSelf() }
    override fun onDestroy() {
        persist(); flushListening(); QueuePreloader.cancel(); focus.abandon()
        unregisterReceiver(noisy); getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network)
        session.release(); player.release()
        // Let final ordered IO snapshots complete; cancel UI/retry jobs separately.
        scope.cancel()
        storage.launch { storage.cancel() }
        PlaybackHub.mutable.value = PlaybackHub.mutable.value.copy(playing = false, ready = false)
        super.onDestroy()
    }
}
