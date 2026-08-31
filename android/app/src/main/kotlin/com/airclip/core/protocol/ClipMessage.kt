package com.airclip.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Wire constants shared with the Windows client (`AirClip.Core.Protocol.ProtocolConstants`).
 * Anything here is part of the cross-platform contract — changing a value breaks older peers.
 */
object ProtocolConstants {
    const val VERSION = "1.0"
    const val TEXT_MIME = "text/plain"
    const val IMAGE_MIME = "image/png"
    const val ENCODING_UTF8 = "utf-8"
    const val ENCODING_BASE64 = "base64"

    /**
     * Marks a payload whose `content` is base64 of `nonce || ciphertext || tag` instead of the clipboard
     * data itself. It occupies the same `encoding` field as the cleartext values, so the wire schema is
     * unchanged and a peer that does not know the value simply fails to read the message.
     */
    const val ENCODING_AES_GCM = "aes-256-gcm"

    /** `NsdManager` wants the two-label form; the full name on the wire is `_airclip._tcp.local.`. */
    const val SERVICE_TYPE = "_airclip._tcp."
    const val DEFAULT_PORT = 47653

    /** WebSocket path both platforms listen on. */
    const val WS_PATH = "/airclip"

    /** mDNS TXT keys. The JSON schema has no platform field, so discovery carries it instead. */
    const val TXT_DEVICE_ID = "id"
    const val TXT_DEVICE_NAME = "name"
    const val TXT_PLATFORM = "plat"
    const val TXT_VERSION = "ver"

    /**
     * The pairing fingerprint from `PairingKey.fingerprint`: eight upper-case hex characters, published
     * so a peer can spot a key mismatch before the handshake rather than after. Compare it
     * case-insensitively — it is a courtesy for diagnosis, not the thing that proves a shared secret.
     */
    const val TXT_KEY_FINGERPRINT = "fp"
}

@Serializable
enum class ClipMessageType {
    @SerialName("text")
    TEXT,

    @SerialName("image")
    IMAGE,

    @SerialName("ping")
    PING,

    @SerialName("ack")
    ACK,
}

@Serializable
data class ClipPayload(
    @SerialName("content") val content: String = "",
    @SerialName("mime_type") val mimeType: String = ProtocolConstants.TEXT_MIME,
    @SerialName("encoding") val encoding: String = ProtocolConstants.ENCODING_UTF8,
    @SerialName("width") val width: Int? = null,
    @SerialName("height") val height: Int? = null,
)

/**
 * Mirrors `AirClip.Core.Protocol.ClipMessage` field for field. [hash] is the canonical *content*
 * hash from [com.airclip.core.clipboard.ContentHasher], never a hash of the encoded bytes: peers
 * compare it against their own recently-seen hashes to break sync loops.
 */
@Serializable
data class ClipMessage(
    @SerialName("version") val version: String = ProtocolConstants.VERSION,
    @SerialName("msg_id") val messageId: String = UUID.randomUUID().toString(),
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("timestamp") val timestamp: Long = System.currentTimeMillis() / 1000,
    @SerialName("type") val type: ClipMessageType = ClipMessageType.PING,
    @SerialName("hash") val hash: String = "",
    @SerialName("payload") val payload: ClipPayload? = null,
)

data class DeviceIdentity(val id: String, val name: String)

enum class DevicePlatform {
    UNKNOWN,
    WINDOWS,
    ANDROID,
    ;

    companion object {
        fun parse(value: String?): DevicePlatform = when (value?.lowercase()) {
            "windows", "win" -> WINDOWS
            "android" -> ANDROID
            else -> UNKNOWN
        }
    }
}
