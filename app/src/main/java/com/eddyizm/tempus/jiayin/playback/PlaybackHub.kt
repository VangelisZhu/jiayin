package com.eddyizm.tempus.jiayin.playback

import com.eddyizm.tempus.jiayin.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val queue: QueueState = QueueState(), val current: Track? = null,
    val playing: Boolean = false, val buffering: Boolean = false,
    val cause: PauseCause = PauseCause.RESTORED, val error: String? = null,
    val ready: Boolean = false, val sleepUntil: Long = 0,
)
object PlaybackHub {
    internal val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()
    const val COMMAND = "jiayin.action"
}
