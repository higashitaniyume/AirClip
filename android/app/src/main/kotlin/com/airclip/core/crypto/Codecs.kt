package com.airclip.core.crypto

import java.util.Base64

internal object Codecs {
    private val HEX = "0123456789abcdef".toCharArray()

    val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    val base64UrlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    fun toHex(value: ByteArray): String {
        val out = CharArray(value.size * 2)
        for (i in value.indices) {
            val b = value[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    /** Accepts both base64url and standard base64, with or without padding. */
    fun decodeKeyMaterial(text: String): ByteArray? {
        val trimmed = text.trim().trimEnd('=')
        if (trimmed.isEmpty()) return null
        val normalised = trimmed.replace('+', '-').replace('/', '_')
        return runCatching { base64UrlDecoder.decode(normalised) }.getOrNull()
    }
}
