package com.airclip.core.clipboard

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Canonical SHA-256 content hashes. This file is a literal port of
 * `AirClip.Core.Clipboard.ContentHasher`: both platforms must produce identical hashes for identical
 * content, otherwise [com.airclip.core.sync.LoopGuard] cannot recognise an echo of data it just
 * sent, and the two devices ping-pong forever.
 */
object ContentHasher {
    /** Domain separator + version tag; bump on both platforms when the canonical image form changes. */
    private const val IMAGE_DOMAIN = "airclip/img1"

    private val HEX = "0123456789abcdef".toCharArray()

    fun hashText(text: String): String = toHex(digest().digest(text.toByteArray(Charsets.UTF_8)))

    fun hashBytes(data: ByteArray): String = toHex(digest().digest(data))

    /**
     * Hashes raw pixels rather than encoded bytes: PNG encoders differ per platform, pixels do not.
     * Canonical form is top-down rows of straight-alpha BGRA quads — see
     * [com.airclip.platform.clipboard.ImageCodec.toCanonicalBgra] for the Android producer.
     */
    fun hashImagePixels(width: Int, height: Int, bgra32TopDown: ByteArray): String {
        val digest = digest()
        digest.update(IMAGE_DOMAIN.toByteArray(Charsets.US_ASCII))

        val dimensions = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        dimensions.putInt(width)
        dimensions.putInt(height)
        digest.update(dimensions.array())

        digest.update(bgra32TopDown)
        return toHex(digest.digest())
    }

    private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun toHex(value: ByteArray): String {
        val out = CharArray(value.size * 2)
        for (i in value.indices) {
            val b = value[i].toInt() and 0xFF
            out[i * 2] = HEX[b ushr 4]
            out[i * 2 + 1] = HEX[b and 0x0F]
        }
        return String(out)
    }
}
