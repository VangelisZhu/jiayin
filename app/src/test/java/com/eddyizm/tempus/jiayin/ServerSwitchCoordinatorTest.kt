package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ServerSwitchCoordinatorTest {
    private val first = ServerProfile("a", "Coast", "https://coast.example.com", "listener")
    private val second = ServerProfile("b", "Sunset", "https://sunset.example.com", "listener")
    private class Registry(profile: ServerProfile) : ProfileRegistry {
        var session: AccountSession? = AccountSession(profile, 1)
        val events = mutableListOf<String>()
        override fun active() = session
        override fun activate(profile: ServerProfile?, offline: Boolean) { events += "commit:${profile?.id}:$offline"; session = profile?.let { AccountSession(it, 2, offline) } }
        override fun logout(id: String) { events += "logout:$id" }
        override fun remove(id: String) { events += "remove:$id" }
    }
    @Test fun verifyThenStopThenCommitThenReload() = runBlocking {
        val r = Registry(first)
        ServerSwitchCoordinator(r, { r.events += "verify" }, { r.events += "stop" }, { r.events += "reload" }).switchTo(second)
        assertEquals(listOf("verify", "stop", "commit:b:false", "reload"), r.events)
    }
    @Test fun rejectedVerificationDoesNotTouchOriginalSession() = runBlocking {
        val r = Registry(first)
        val c = ServerSwitchCoordinator(r, { throw AuthenticationRequired() }, { error("must not stop") }, { error("must not reload") })
        assertTrue(runCatching { c.switchTo(second) }.exceptionOrNull() is AuthenticationRequired)
        assertEquals(first, r.active()!!.profile); assertTrue(r.events.isEmpty())
    }
    @Test fun stopFailureDoesNotCommitTarget() = runBlocking {
        val r = Registry(first)
        val c = ServerSwitchCoordinator(r, {}, { error("storage unavailable") }, { error("must not reload") })
        assertTrue(runCatching { c.switchTo(second) }.isFailure)
        assertEquals(first, r.active()!!.profile)
    }
    @Test fun offlineSwitchRequiresSavedAuthenticatedAccountAndDoesNotPing() = runBlocking {
        val r = Registry(first)
        val c = ServerSwitchCoordinator(r, { error("offline must not ping") }, { r.events += "stop" }, {})
        c.switchTo(second, true)
        assertTrue(r.active()!!.offline)
        assertTrue(runCatching { c.switchTo(first.copy(authenticated = false), true) }.isFailure)
        assertEquals(second, r.active()!!.profile)
    }
    @Test fun currentLogoutStopsAndClearsSelectionWithoutSelectingAnotherAccount() = runBlocking {
        val r = Registry(first)
        ServerSwitchCoordinator(r, {}, { r.events += "stop" }, { r.events += "reload" }).logout(first)
        assertNull(r.active())
        assertEquals(listOf("stop", "commit:null:false", "logout:a", "reload"), r.events)
    }
    @Test fun removingInactiveAccountLeavesPlaybackSessionUntouched() = runBlocking {
        val r = Registry(first)
        ServerSwitchCoordinator(r, {}, { error("must not stop") }, { error("must not reload") }).logout(second, true) { r.events += "cleanup" }
        assertEquals(first, r.active()!!.profile)
        assertEquals(listOf("cleanup", "remove:b"), r.events)
    }
    @Test fun failedCleanupRetainsRegistryForRetry() = runBlocking {
        val r = Registry(first)
        val c = ServerSwitchCoordinator(r, {}, {}, {})
        assertTrue(runCatching { c.logout(first, true) { error("disk busy") } }.isFailure)
        assertEquals(first, r.active()!!.profile); assertTrue(r.events.isEmpty())
    }
    @Test fun interruptionBeforeCommitLeavesOriginalSelection() = runBlocking {
        val r = Registry(first)
        val c = ServerSwitchCoordinator(r, {}, { throw kotlinx.coroutines.CancellationException("interrupted") }, {})
        assertTrue(runCatching { c.switchTo(second) }.isFailure)
        assertEquals(first, r.active()!!.profile)
    }
    @Test fun interruptionAfterCommitRetainsTargetSelection() = runBlocking {
        val r = Registry(first)
        val c = ServerSwitchCoordinator(r, {}, {}, { throw kotlinx.coroutines.CancellationException("interrupted") })
        assertTrue(runCatching { c.switchTo(second) }.isFailure)
        assertEquals(second, r.active()!!.profile)
    }
}
