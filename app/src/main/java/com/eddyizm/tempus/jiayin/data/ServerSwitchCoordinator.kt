package com.eddyizm.tempus.jiayin.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Verification precedes quiescence; the active registry commit is the switch's single commit point. */
class ServerSwitchCoordinator(
    private val profiles: ProfileRegistry,
    private val verify: suspend (ServerProfile) -> Unit,
    private val quiesce: suspend () -> Unit,
    private val reload: suspend () -> Unit,
) {
    private val mutex = Mutex()
    suspend fun switchTo(profile: ServerProfile, offline: Boolean = false) = mutex.withLock {
        require(profile.authenticated) { "请先重新登录" }
        if (!offline) verify(profile)
        quiesce()
        profiles.activate(profile, offline)
        reload()
    }
    suspend fun logout(profile: ServerProfile, remove: Boolean = false, cleanup: suspend () -> Unit = {}) = mutex.withLock {
        val active = profiles.active()?.profile?.id == profile.id
        if (active) quiesce()
        // Cleanup must finish before removing the registry, so a failed cleanup remains retryable.
        if (remove) cleanup()
        if (active) profiles.activate(null)
        if (remove) profiles.remove(profile.id) else profiles.logout(profile.id)
        if (active) reload()
    }
}
