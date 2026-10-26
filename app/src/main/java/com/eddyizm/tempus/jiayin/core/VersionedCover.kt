package com.eddyizm.tempus.jiayin.core

/** Revision in the local cache key, original server ID in getCoverArt requests. */
object VersionedCover {
    fun original(value: String): String = if (value.startsWith("jv:") && value.split(':', limit = 3).size == 3)
        java.net.URLDecoder.decode(value.split(':', limit = 3)[2], "UTF-8") else value
    fun encode(value: String, revision: String): String = if (value.isBlank() || value.startsWith("ir_")) value
        else "jv:${revision.take(64)}:${java.net.URLEncoder.encode(original(value), "UTF-8")}"
}
