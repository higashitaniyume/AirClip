package com.airclip.core.crypto

import java.util.Base64

/**
 * The text encodings that are part of the cross-platform contract, each with a named .NET counterpart
 * so a change on one side has an obvious place to land on the other: [toHex] is
 * `Convert.ToHexString` (upper case, which is why the table below is upper case), [base64] is
 * `Convert.ToBase64String`, and [toBase32]/[fromBase32] are the pairing code in
 * `AirClip.Crypto.PairingKey`.
 */
internal object Codecs {
    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * Crockford Base32. I, L, O and U are absent, so a code read off a phone screen has no character
     * that can be confused with 1 or 0 — and no way to spell anything unfortunate.
     */
    const val BASE32_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    const val GROUP_SIZE = 4

    val base64: Base64.Encoder = Base64.getEncoder()
    val base64Decoder: Base64.Decoder = Base64.getDecoder()

    fun toHex(value: ByteArray): String {
        val out = CharArray(value.size * 2)
        for (i in value.indices) {
            val b = value[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }

    fun toBase32(data: ByteArray): String {
        val builder = StringBuilder((data.size * 8 / 5) + 1)
        var buffer = 0
        var bits = 0
        for (value in data) {
            buffer = (buffer shl 8) or (value.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                builder.append(BASE32_ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) {
            builder.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        return builder.toString()
    }

    /**
     * `null` for anything that is not exactly [size] bytes worth of Base32 digits once separators are
     * dropped. Twenty bytes are exactly thirty-two digits, so a short code is a typo rather than
     * something to pad, and a long one is a different kind of string altogether. O, I and L fold onto
     * 0 and 1, which is the whole reason for choosing this alphabet.
     */
    fun fromBase32(text: String, size: Int): ByteArray? {
        val digitCount = size * 8 / 5
        val digits = CharArray(digitCount)
        var count = 0
        for (raw in text) {
            if (raw == '-' || raw == ' ' || raw == '_' || raw == '\t') continue
            if (count == digitCount) return null
            val upper = raw.uppercaseChar()
            digits[count++] = when (upper) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> upper
            }
        }
        if (count != digitCount) return null

        val bytes = ByteArray(size)
        var buffer = 0
        var bits = 0
        var written = 0
        for (digit in digits) {
            val value = BASE32_ALPHABET.indexOf(digit)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes[written++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return bytes
    }

    /** The user-facing form of a code: `A1B2-C3D4-…`. */
    fun group(raw: String): String {
        val builder = StringBuilder(raw.length + (raw.length / GROUP_SIZE))
        for (i in raw.indices) {
            if (i > 0 && i % GROUP_SIZE == 0) builder.append('-')
            builder.append(raw[i])
        }
        return builder.toString()
    }
}
