package com.eddyizm.tempus.jiayin

import com.eddyizm.tempus.jiayin.data.*
import org.junit.Assert.*
import org.junit.Test

class ServerEditorPolicyTest {
    @Test fun canonicalAddressPreservesServicePathAndIpv6() {
        assertEquals("https://music.example.com/subsonic", ServerEditorPolicy.address(" HTTPS://Music.Example.com:443/subsonic/ "))
        assertEquals("http://[::1]:4533", ServerEditorPolicy.address("http://[::1]:4533/"))
    }
    @Test fun invalidAndCredentialBearingAddressesAreRejected() {
        listOf("", "music.example.com", "https://user:secret@music.example.com", "https://music.example.com?token=x", "https://music.example.com#x", "http://music.example.com").forEach {
            assertTrue(it, runCatching { ServerEditorPolicy.address(it) }.exceptionOrNull() is ServerFieldError)
        }
    }
    @Test fun feedbackDoesNotEchoServerErrorsOrCredentials() {
        val secret = "private-auth-token"
        listOf(IllegalStateException(secret), java.io.IOException(secret), java.net.UnknownHostException(secret), javax.net.ssl.SSLHandshakeException(secret), AuthenticationRequired(secret)).forEach {
            assertFalse(ServerEditorPolicy.error(it).contains(secret))
            assertTrue(ServerEditorPolicy.error(it).isNotBlank())
        }
        assertTrue(ServerEditorPolicy.error(AuthenticationRequired()).contains("密码"))
        assertTrue(ServerEditorPolicy.error(javax.net.ssl.SSLHandshakeException("")).contains("证书"))
    }
}
