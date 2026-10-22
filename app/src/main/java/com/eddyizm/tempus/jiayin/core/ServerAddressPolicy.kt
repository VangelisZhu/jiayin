package com.eddyizm.tempus.jiayin.core

import java.net.InetAddress

/** Cleartext is restricted to local names and literal local addresses, without DNS lookup. */
object ServerAddressPolicy {
    fun allowsCleartext(host: String): Boolean {
        val value = host.lowercase().trimEnd('.')
        if (value == "localhost" || value.endsWith(".local")) return true
        if (':' in value) {
            val bytes = runCatching { InetAddress.getByName(value).address }.getOrNull() ?: return false
            if (bytes.size == 4) return localV4(bytes.map { it.toInt() and 255 })
            val first = bytes[0].toInt() and 255; val second = bytes[1].toInt() and 255
            return bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte() ||
                (first and 254) == 252 || first == 254 && (second and 192) == 128
        }
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.any { char -> char !in '0'..'9' } }) return false
        return localV4(parts.map { it.toIntOrNull() ?: return false })
    }
    private fun localV4(parts: List<Int>): Boolean = parts.all { it in 0..255 } &&
        (parts[0] == 10 || parts[0] == 127 || parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31 || parts[0] == 169 && parts[1] == 254)
}
