package com.airclip.core.protocol

import com.airclip.core.clipboard.ClipContent
import com.airclip.core.clipboard.ClipImage
import com.airclip.core.clipboard.ClipKind
import java.util.Base64

/** Outcome of turning a received [ClipMessage] back into clipboard content. */
sealed interface DecodedMessage {
    data class Content(val content: ClipContent) : DecodedMessage

    /** Well-formed but carries no clipboard content (`ping` / `ack`). */
    data class Control(val message: ClipMessage) : DecodedMessage

    data class Rejected(val reason: String) : DecodedMessage
}

/** Port of `AirClip.Core.Protocol.ClipMessageFactory`, including its hash-verification rules. */
object ClipMessageFactory {
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun create(
        content: ClipContent,
        device: DeviceIdentity,
        timestampSeconds: Long = System.currentTimeMillis() / 1000,
    ): ClipMessage {
        val payload = when (content.kind) {
            ClipKind.TEXT -> ClipPayload(
                content = content.text!!,
                mimeType = ProtocolConstants.TEXT_MIME,
                encoding = ProtocolConstants.ENCODING_UTF8,
            )

            ClipKind.IMAGE -> ClipPayload(
                content = encoder.encodeToString(content.image!!.png),
                mimeType = ProtocolConstants.IMAGE_MIME,
                encoding = ProtocolConstants.ENCODING_BASE64,
                width = content.image.width,
                height = content.image.height,
            )
        }

        return ClipMessage(
            deviceId = device.id,
            deviceName = device.name,
            timestamp = timestampSeconds,
            type = if (content.kind == ClipKind.TEXT) ClipMessageType.TEXT else ClipMessageType.IMAGE,
            hash = content.hash,
            payload = payload,
        )
    }

    /**
     * A heartbeat. It carries a token nobody reads, which sounds like waste but is not: a payload is
     * what [com.airclip.core.crypto.MessageProtector] seals, so sealing the heartbeat puts its header
     * under GCM's associated data and its nonce under the replay counter. The far end rejects a
     * payload-less frame outright rather than trusting it as an unencrypted ping.
     */
    fun ping(device: DeviceIdentity): ClipMessage = ClipMessage(
        deviceId = device.id,
        deviceName = device.name,
        type = ClipMessageType.PING,
        payload = ClipPayload(content = "ping"),
    )

    /**
     * The acknowledged `msg_id` travels in the `hash` field. That is the only field the fixed 1.0 schema
     * leaves free for correlation, and it is covered by the associated data, so it cannot be rewritten
     * in flight to make a ping look answered when it was not.
     */
    fun ack(device: DeviceIdentity, acknowledgedMessageId: String): ClipMessage = ClipMessage(
        deviceId = device.id,
        deviceName = device.name,
        type = ClipMessageType.ACK,
        hash = acknowledgedMessageId,
        payload = ClipPayload(content = "ack"),
    )

    fun decode(message: ClipMessage): DecodedMessage {
        if (message.type == ClipMessageType.PING || message.type == ClipMessageType.ACK) {
            return DecodedMessage.Control(message)
        }

        val payload = message.payload ?: return DecodedMessage.Rejected("payload is missing")

        return when (message.type) {
            ClipMessageType.TEXT -> {
                val text = ClipContent.fromText(payload.content)
                if (message.hash.isNotEmpty() && !text.hash.equals(message.hash, ignoreCase = true)) {
                    DecodedMessage.Rejected("text hash mismatch")
                } else {
                    DecodedMessage.Content(text)
                }
            }

            ClipMessageType.IMAGE -> decodeImage(message, payload)

            else -> DecodedMessage.Rejected("${message.type} carries no clipboard content")
        }
    }

    private fun decodeImage(message: ClipMessage, payload: ClipPayload): DecodedMessage {
        // The sender's pixel hash is the only thing that can identify an image after a platform
        // re-encode, so unlike text it is mandatory rather than merely verified.
        if (message.hash.isEmpty()) {
            return DecodedMessage.Rejected("image hash is required")
        }

        val width = payload.width ?: 0
        val height = payload.height ?: 0
        if (width <= 0 || height <= 0) {
            return DecodedMessage.Rejected("image dimensions are missing")
        }

        val png = runCatching { decoder.decode(payload.content) }.getOrNull()
            ?: return DecodedMessage.Rejected("image payload is not valid base64")

        return DecodedMessage.Content(ClipContent.fromImage(ClipImage(width, height, png, message.hash)))
    }
}
