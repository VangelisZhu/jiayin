package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.core.ServerAddressPolicy
import org.junit.Assert.*
import org.junit.Test

class ServerAddressPolicyTest {
    @Test fun standardPrivateLoopbackAndLocalHostsAreAllowed() {
        listOf("192.168.1.2", "10.0.0.2", "172.16.0.2", "172.31.255.254", "127.0.0.1", "169.254.1.2", "localhost", "nas.local").forEach {
            assertTrue(it, ServerAddressPolicy.allowsCleartext(it))
        }
    }
    @Test fun publicAddressesAndLocalLookingPublicNamesAreRejected() {
        listOf("1.1.1.1", "172.15.0.1", "172.32.0.1", "256.0.0.1", "10.music.example.com", "192.168.evil.example", "localhost.example", "example.com", "").forEach {
            assertFalse(it, ServerAddressPolicy.allowsCleartext(it))
        }
    }
    @Test fun ipv6LoopbackUlaLinkLocalAndMappedPrivateIpv4AreAllowed() {
        listOf("::1", "fd12:3456::1", "fc00::1", "fe80::2", "::ffff:192.168.1.2").forEach { assertTrue(it, ServerAddressPolicy.allowsCleartext(it)) }
    }
    @Test fun unspecifiedAndGlobalIpv6AreRejectedForCleartext() {
        listOf("::", "2001:db8::1", "::ffff:1.1.1.1").forEach { assertFalse(it, ServerAddressPolicy.allowsCleartext(it)) }
    }
}
