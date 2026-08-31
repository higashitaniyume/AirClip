package com.airclip.core.crypto

import com.airclip.core.protocol.ClipMessage
import com.airclip.core.protocol.ProtocolConstants

/** Outcome of opening a sealed [ClipMessage]. */
sealed interface UnprotectResult {
    data class Opened(val message: ClipMessage) : UnprotectResult

    data class Rejected(val reason: String) : UnprotectResult
}

/**
 * Turns a [ClipMessage] into its encrypted form and back without changing the wire schema: the
 * ciphertext travels in the payload's `content` field with `encoding` set to `aes-256-gcm`, so the JSON
 * a peer parses has exactly the shape the protocol specifies. A port of
 * `AirClip.Crypto.MessageProtector`.
 *
 * Two things stay in the clear because the schema puts them outside the payload: the content hash and
 * the mime type. The hash is what makes loop prevention work across devices, and the price is that an
 * eavesdropper who already suspects a specific clipboard value can confirm the guess.
 *
 * Every header field that is in the clear is fed to GCM as associated data, so none of them can be
 * altered in flight — a rewritten hash, device id or timestamp makes decryption fail outright.
 *
 * Frozen frame, pinned on the other side by
 * `windows/tests/AirClip.Sync.Tests/CrossPlatformVectorTests.cs`. Sealed with the `client-to-server` key
 * of the vectors in [PairingKey] — secret `00 01 02 … 13`, challenges 32 × `0x01` and 32 × `0x02` — over
 * the text `你好，剪贴板`, with the associated data below and the session's first counter:
 * ```
 * aad      = 1.0|msg-0001|device-a|1735689600000|text|HASH0001
 * envelope = AAAAAAAAAAAAAAABSO2UNJPydbqb6PgPnTfEDmXGX0jh6NdwQe93vBdodmI5EA==
 * ```
 */
object MessageProtector {

    fun protect(message: ClipMessage, crypto: SessionCrypto): ClipMessage {
        // Ping and ack still carry a payload on this protocol; a payload-less frame is rejected at the
        // far end rather than treated as an unencrypted heartbeat, so there is nothing to special-case.
        val payload = message.payload ?: return message

        val plaintext = if (payload.encoding.equals(ProtocolConstants.ENCODING_BASE64, ignoreCase = true)) {
            // Images are sealed as raw PNG bytes rather than as their base64 text: base64 inside the
            // ciphertext would inflate every screenshot by a third for no benefit whatsoever.
            runCatching { Codecs.base64Decoder.decode(payload.content) }.getOrNull()
                ?: return message
        } else {
            payload.content.toByteArray(Charsets.UTF_8)
        }

        val envelope = crypto.seal(plaintext, associatedData(message))
        return message.copy(
            payload = payload.copy(
                content = Codecs.base64.encodeToString(envelope),
                encoding = ProtocolConstants.ENCODING_AES_GCM,
            ),
        )
    }

    /**
     * Reverses [protect]. A payload that is *not* marked as encrypted is rejected rather than passed
     * through: accepting cleartext would let anyone on the LAN inject a clipboard entry simply by
     * omitting the encryption, which is exactly what the session key is there to stop.
     */
    fun unprotect(message: ClipMessage, crypto: SessionCrypto): UnprotectResult {
        val payload = message.payload ?: return UnprotectResult.Opened(message)

        if (!payload.encoding.equals(ProtocolConstants.ENCODING_AES_GCM, ignoreCase = true)) {
            return UnprotectResult.Rejected("载荷未加密，已拒绝")
        }

        val envelope = runCatching { Codecs.base64Decoder.decode(payload.content) }.getOrNull()
            ?: return UnprotectResult.Rejected("密文不是合法的 base64")

        // Deliberately one message for every failure: wrong key, tampered header, replay, truncation.
        val plaintext = crypto.open(envelope, associatedData(message))
            ?: return UnprotectResult.Rejected("解密失败（密钥不符、报文被改动或重放）")

        val isText = payload.mimeType.equals(ProtocolConstants.TEXT_MIME, ignoreCase = true)
        return UnprotectResult.Opened(
            message.copy(
                payload = payload.copy(
                    content = if (isText) {
                        plaintext.toString(Charsets.UTF_8)
                    } else {
                        Codecs.base64.encodeToString(plaintext)
                    },
                    encoding = if (isText) ProtocolConstants.ENCODING_UTF8 else ProtocolConstants.ENCODING_BASE64,
                ),
            ),
        )
    }

    /**
     * The cleartext header, in a fixed order, as GCM associated data. Field order and the separator are
     * part of the protocol: both sides must build this byte-for-byte identically or nothing decrypts.
     */
    private fun associatedData(message: ClipMessage): ByteArray = listOf(
        message.version,
        message.messageId,
        message.deviceId,
        message.timestamp.toString(),
        message.type.name.lowercase(),
        message.hash,
    ).joinToString("|").toByteArray(Charsets.UTF_8)
}
