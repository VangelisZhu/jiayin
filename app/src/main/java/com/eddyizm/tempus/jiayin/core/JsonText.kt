package com.eddyizm.tempus.jiayin.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** JSON is UTF-8 unless an explicit Unicode BOM identifies a legacy UTF-16 response. */
object JsonText {
    fun decode(bytes: ByteArray): String {
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
        val (charset, skip) = when {
            starts(0xef, 0xbb, 0xbf) -> Charsets.UTF_8 to 3
            starts(0xff, 0xfe) -> Charsets.UTF_16LE to 2
            starts(0xfe, 0xff) -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        return try { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { error("服务器响应文本编码异常，请检查服务端 UTF-8 设置") }
    }
}
