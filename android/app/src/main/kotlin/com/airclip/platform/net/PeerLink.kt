package com.airclip.platform.net

import com.airclip.core.clipboard.ClipContent
import com.airclip.core.crypto.CryptoBox
import com.airclip.core.protocol.AirClipJson
import com.airclip.core.protocol.ClipMessage
import com.airclip.core.protocol.ClipMessageFactory
import com.airclip.core.protocol.ClipMessageType
import com.airclip.core.protocol.DecodedMessage
import com.airclip.core.protocol.DeviceIdentity
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One WebSocket connection to one peer, in whichever direction it was established.
 *
 * Framing rule: an encrypted payload is a binary frame holding a [CryptoBox] frame; plaintext JSON
 * travels as a text frame and is only accepted when the user turned encryption off. A peer that
 * sends plaintext while we require encryption is ignored rather than trusted.
 */
class PeerLink(
    private val session: WebSocketSession,
    private val identity: DeviceIdentity,
    private val cryptoProvider: () -> CryptoBox?,
    private val requireEncryption: () -> Boolean,
    val host: String,
    val port: Int,
    val dialedByUs: Boolean,
) {
    private val remoteIdentity = CompletableDeferred<DeviceIdentity>()
    private val pendingPings = ConcurrentHashMap<String, Long>()
    private val authFailures = AtomicInteger(0)

    /** Completes once the peer's `ping`/`ack` told us who it is. */
    val remote: Deferred<DeviceIdentity> get() = remoteIdentity

    @Volatile
    var roundTripMillis: Long? = null
        private set

    @Volatile
    var isEncrypted: Boolean = false
        private set

    suspend fun send(message: ClipMessage): Boolean {
        val json = AirClipJson.encode(message)
        val crypto = cryptoProvider()

        return runCatching {
            if (crypto != null) {
                session.send(Frame.Binary(true, crypto.seal(json.toByteArray(Charsets.UTF_8))))
                isEncrypted = true
            } else {
                if (requireEncryption()) return false
                session.send(Frame.Text(json))
            }
        }.isSuccess
    }

    suspend fun sendPing(): Boolean {
        val ping = ClipMessageFactory.ping(identity)
        pendingPings[ping.messageId] = System.nanoTime()
        return send(ping)
    }

    /**
     * Reads until the connection dies. [onContent] receives clipboard payloads; identity and
     * round-trip bookkeeping is handled here.
     */
    suspend fun run(onContent: suspend (ClipContent, DeviceIdentity) -> Unit) {
        sendPing()

        for (frame in session.incoming) {
            val json = when (frame) {
                is Frame.Binary -> decrypt(frame.readBytes()) ?: continue
                is Frame.Text -> if (requireEncryption()) continue else frame.readText()
                else -> continue
            }

            val message = AirClipJson.decodeOrNull(json) ?: continue
            noteIdentity(message)

            when (val decoded = ClipMessageFactory.decode(message)) {
                is DecodedMessage.Content -> onContent(decoded.content, currentRemote(message))
                is DecodedMessage.Control -> handleControl(message)
                is DecodedMessage.Rejected -> Unit // Malformed payload: drop the frame, keep the link.
            }
        }
    }

    suspend fun close(reason: String) {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) }
        if (!remoteIdentity.isCompleted) {
            remoteIdentity.cancel()
        }
    }

    private fun decrypt(bytes: ByteArray): String? {
        val crypto = cryptoProvider() ?: return null
        val opened = crypto.open(bytes)
        if (opened == null) {
            // Wrong key or tampering. A handful of these means the peers are not really paired.
            authFailures.incrementAndGet()
            return null
        }
        isEncrypted = true
        authFailures.set(0)
        return opened.toString(Charsets.UTF_8)
    }

    /** How many frames failed to authenticate; the transport drops the link once this climbs. */
    val failedAuthCount: Int get() = authFailures.get()

    private suspend fun handleControl(message: ClipMessage) {
        when (message.type) {
            ClipMessageType.PING -> send(ClipMessageFactory.ack(identity, message.messageId))
            ClipMessageType.ACK -> {
                val acknowledged = message.payload?.content.orEmpty()
                pendingPings.remove(acknowledged)?.let { sentAt ->
                    roundTripMillis = (System.nanoTime() - sentAt) / 1_000_000
                }
            }

            else -> Unit
        }
    }

    private fun noteIdentity(message: ClipMessage) {
        if (message.deviceId.isEmpty() || remoteIdentity.isCompleted) return
        remoteIdentity.complete(DeviceIdentity(message.deviceId, message.deviceName.ifEmpty { message.deviceId }))
    }

    private fun currentRemote(message: ClipMessage): DeviceIdentity =
        DeviceIdentity(message.deviceId, message.deviceName.ifEmpty { message.deviceId })
}
