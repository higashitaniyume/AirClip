package com.airclip.core.protocol

import com.airclip.core.crypto.SessionCrypto
import java.io.ByteArrayOutputStream

enum class HandshakeFrameType(val id: Byte) {
    HELLO(1),
    PROOF(2),
    REJECT(3),
    ;

    companion object {
        fun from(id: Byte): HandshakeFrameType? = values().firstOrNull { it.id == id }
    }
}

/** Who is on the other end, and the random half of the session key they contributed. */
class HandshakeHello(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val fingerprint: String,
    val challenge: ByteArray,
)

/**
 * The handshake's own binary frames, sent before any clipboard traffic. A port of
 * `AirClip.Net.HandshakeCodec`, and like it they are deliberately *not* [ClipMessage] JSON: the
 * protocol's message schema is fixed and has no room for a challenge or a MAC, and bending `type` or
 * `payload` to carry them would make AirClip's wire format subtly different from the one it documents.
 * A binary frame on the same socket keeps the JSON schema exact.
 *
 * ```
 * offset size field
 * 0      4    magic "ACLP"
 * 4      1    version = 1
 * 5      1    frame type
 * 6      …    body (see each writer)
 * ```
 */
object HandshakeCodec {
    const val VERSION: Byte = 1

    private const val HEADER_SIZE = 6
    private const val MAX_STRING_BYTES = 256
    private val MAGIC = "ACLP".toByteArray(Charsets.US_ASCII)

    /** Four length-prefixed strings, then the fixed-size challenge. */
    fun writeHello(hello: HandshakeHello): ByteArray {
        val frame = begin(HandshakeFrameType.HELLO)
        writeString(frame, hello.deviceId)
        writeString(frame, hello.deviceName)
        writeString(frame, hello.platform)
        writeString(frame, hello.fingerprint)
        frame.write(hello.challenge)
        return frame.toByteArray()
    }

    /** One byte of length, then the MAC: it is always 32 bytes, but the length keeps it self-describing. */
    fun writeProof(mac: ByteArray): ByteArray {
        val frame = begin(HandshakeFrameType.PROOF)
        frame.write(mac.size)
        frame.write(mac)
        return frame.toByteArray()
    }

    fun writeReject(reason: String): ByteArray {
        val frame = begin(HandshakeFrameType.REJECT)
        writeString(frame, reason)
        return frame.toByteArray()
    }

    fun readType(frame: ByteArray): HandshakeFrameType? {
        if (frame.size < HEADER_SIZE) return null
        for (i in MAGIC.indices) {
            if (frame[i] != MAGIC[i]) return null
        }
        if (frame[4] != VERSION) return null
        return HandshakeFrameType.from(frame[5])
    }

    fun readHello(frame: ByteArray): HandshakeHello? {
        if (readType(frame) != HandshakeFrameType.HELLO) return null

        val cursor = Cursor(HEADER_SIZE)
        val deviceId = readString(frame, cursor) ?: return null
        val deviceName = readString(frame, cursor) ?: return null
        val platform = readString(frame, cursor) ?: return null
        val fingerprint = readString(frame, cursor) ?: return null
        if (frame.size - cursor.at != SessionCrypto.CHALLENGE_SIZE) return null

        return HandshakeHello(
            deviceId = deviceId,
            deviceName = deviceName,
            platform = platform,
            fingerprint = fingerprint,
            challenge = frame.copyOfRange(cursor.at, frame.size),
        )
    }

    fun readProof(frame: ByteArray): ByteArray? {
        if (readType(frame) != HandshakeFrameType.PROOF || frame.size < HEADER_SIZE + 1) return null
        val length = frame[HEADER_SIZE].toInt() and 0xFF
        if (frame.size - (HEADER_SIZE + 1) != length) return null
        return frame.copyOfRange(HEADER_SIZE + 1, frame.size)
    }

    fun readReject(frame: ByteArray): String? {
        if (readType(frame) != HandshakeFrameType.REJECT) return null
        return readString(frame, Cursor(HEADER_SIZE))
    }

    private fun begin(type: HandshakeFrameType): ByteArrayOutputStream {
        val frame = ByteArrayOutputStream(160)
        frame.write(MAGIC)
        frame.write(VERSION.toInt())
        frame.write(type.id.toInt())
        return frame
    }

    private fun writeString(frame: ByteArrayOutputStream, value: String) {
        var bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_BYTES) {
            // Trimmed back to a character boundary rather than to the byte limit: a device called
            // "办公室…" spends three bytes per character, and cutting one in half would put an invalid
            // UTF-8 sequence on the wire for the far end to render as a replacement glyph.
            var limit = MAX_STRING_BYTES
            while (limit > 0 && (bytes[limit].toInt() and 0xC0) == 0x80) limit--
            bytes = bytes.copyOfRange(0, limit)
        }

        frame.write((bytes.size ushr 8) and 0xFF)
        frame.write(bytes.size and 0xFF)
        frame.write(bytes)
    }

    private fun readString(frame: ByteArray, cursor: Cursor): String? {
        if (frame.size - cursor.at < 2) return null
        val length = ((frame[cursor.at].toInt() and 0xFF) shl 8) or (frame[cursor.at + 1].toInt() and 0xFF)
        cursor.at += 2
        if (length > MAX_STRING_BYTES || frame.size - cursor.at < length) return null

        val value = String(frame, cursor.at, length, Charsets.UTF_8)
        cursor.at += length
        return value
    }

    /** A read position that survives being passed to a helper; C# spells this `ref int`. */
    private class Cursor(var at: Int)
}
