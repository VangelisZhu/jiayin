@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.eddyizm.tempus.jiayin.playback

import androidx.media3.common.*
import androidx.media3.exoplayer.source.ForwardingTimeline
import com.google.common.util.concurrent.ListenableFuture

data class SessionPosition(val durations: List<Long>, val serverSeek: List<Boolean>, val offset: Long,
    val pending: Long? = null, val discovery: Boolean = false)

/** One state and command adapter for both platform/legacy and Media3 controllers. */
class SessionPlayer(delegate: Player, private val position: () -> SessionPosition,
    private val action: (String, Int, Long) -> ListenableFuture<*>) : ForwardingSimpleBasePlayer(delegate) {
    private var source: Timeline? = null
    private var timeline: Timeline = Timeline.EMPTY
    private var durations = emptyList<Long>()
    private var serverSeek = emptyList<Boolean>()
    fun refresh() = invalidateState()
    override fun getState(): State {
        val base = super.getState()
        val s = position()
        if (source != base.timeline || durations != s.durations || serverSeek != s.serverSeek) {
            source = base.timeline; durations = s.durations; serverSeek = s.serverSeek
            val times = durations; val remote = serverSeek
            timeline = object : ForwardingTimeline(base.timeline) {
                override fun getWindow(index: Int, window: Timeline.Window, projection: Long): Timeline.Window {
                    super.getWindow(index, window, projection)
                    val full = times.getOrNull(index) ?: 0
                    if (full > 0 && (remote.getOrNull(index) == true || window.durationUs == C.TIME_UNSET)) window.durationUs = full * 1000
                    window.isSeekable = window.isSeekable || (remote.getOrNull(index) == true && full > 0)
                    return window
                }
            }
        }
        val index = base.currentMediaItemIndex
        val seekable = !timeline.isEmpty && index in 0 until timeline.windowCount &&
            timeline.getWindow(index, Timeline.Window()).isSeekable
        val commands = base.availableCommands.buildUpon()
        if (seekable) commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        else commands.remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).remove(Player.COMMAND_SEEK_BACK).remove(Player.COMMAND_SEEK_FORWARD)
        if (s.discovery) commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val b = base.buildUpon().setAvailableCommands(commands.build())
            .setPlaylist(timeline, base.currentTracks, base.currentMetadata)
            .setContentPositionMs(PositionSupplier { s.pending ?: (base.contentPositionMsSupplier.get() + s.offset).coerceAtLeast(0) })
            .setContentBufferedPositionMs(PositionSupplier { (base.contentBufferedPositionMsSupplier.get() + s.offset).coerceAtLeast(0) })
        if (base.hasPositionDiscontinuity) b.setPositionDiscontinuity(base.positionDiscontinuityReason, base.discontinuityPositionMs + s.offset)
        if (s.pending != null && base.playbackState != Player.STATE_IDLE) b.setPlaybackState(Player.STATE_BUFFERING)
        return b.build()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = action(if (playWhenReady) "play" else "pause", -1, 0)
    override fun handleStop() = action("stop", -1, 0)
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> action("next", mediaItemIndex, 0)
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> action("previous", mediaItemIndex, 0)
        else -> action("seek", mediaItemIndex, if (positionMs == C.TIME_UNSET) 0 else positionMs)
    }
}
