@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.eddyizm.tempus.jiayin.data.AccountDownloads
import com.eddyizm.tempus.jiayin.data.JiayinRepository
import com.eddyizm.tempus.util.DownloadUtil
import kotlinx.coroutines.*

/** Per-service preloader: an old writer keeps its original account and is cancelled before commit. */
class AccountPreloader(private val context: Context, private val repo: JiayinRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var writer: CacheWriter? = null
    private var target: List<String> = emptyList()
    fun preload(player: Player) {
        if (repo.offline || !repo.currentSession()) return
        val count = if ((context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) 1 else 2
        val upcoming = (player.currentMediaItemIndex + 1..(player.currentMediaItemIndex + count).coerceAtMost(player.mediaItemCount - 1))
            .map { player.getMediaItemAt(it) }.filter { it.localConfiguration?.uri?.scheme == "jiayin-stream" }
        val keys = upcoming.map { it.localConfiguration!!.customCacheKey.orEmpty() }
        if (keys == target) return
        writer?.cancel(); job?.cancel(); target = keys
        job = scope.launch {
            for (item in upcoming) {
                ensureActive(); if (!repo.currentSession()) break
                val source = CacheDataSource.Factory().setCache(DownloadUtil.getStreamingCacheForPreload(context))
                    .setUpstreamDataSourceFactory(AccountDownloads.playbackNetworkFactory(context, repo)).createDataSource()
                val local = item.localConfiguration!!
                val next = CacheWriter(source, DataSpec.Builder().setUri(local.uri).setKey(local.customCacheKey).build(), null, null)
                writer = next
                try { next.cache() } catch (e: Exception) { ensureActive() } finally { if (writer === next) writer = null }
            }
        }
    }
    fun cancel() { writer?.cancel(); job?.cancel(); scope.cancel(); target = emptyList() }
}
