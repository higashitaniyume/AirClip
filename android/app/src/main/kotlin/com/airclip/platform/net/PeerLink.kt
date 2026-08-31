package com.airclip.platform.net

import com.airclip.core.clipboard.ClipContent
import com.airclip.core.crypto.MessageProtector
import com.airclip.core.crypto.PairingKey
import com.airclip.core.crypto.SessionCrypto
import com.airclip.core.crypto.UnprotectResult
import com.airclip.core.protocol.AirClipJson
import com.airclip.core.protocol.ClipMessage
import com.airclip.core.protocol.ClipMessageFactory
import com.airclip.core.protocol.ClipMessageType
import com.airclip.core.protocol.DecodedMessage
import com.airclip.core.protocol.DeviceIdentity
import com.airclip.core.protocol.HandshakeCodec
import com.airclip.core.protocol.HandshakeHello
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Thrown when a connection is refused before it carries any clipboard data. */
class PeerHandshakeException(message: String) : Exception(message)

/**
 * One authenticated WebSocket connection to one peer, in whichever direction it was established.
 *
 * Framing rule, and it is the whole protocol in two lines: the handshake is binary frames
 * ([HandshakeCodec]), and everything after it is a text frame holding `ClipMessage` JSON whose payload
 * is sealed by the session key. There is no plaintext mode — the far side is only known to be a peer at
 * all because it proved it holds the group secret, so a frame that is not sealed is a frame from
 * somebody else.
 *
 * The counterpart is `AirClip.Net.PeerSession`; the two must stay in step frame for frame.
 */
class PeerLink(
    private val session: WebSocketSession,
    private val identity: DeviceIdentity,
    private val keyProvider: () -> PairingKey?,
    val host: String,
    val port: Int,
    val dialedByUs: Boolean,
) {
    private val remoteIdentity = CompletableDeferred<DeviceIdentity>()
    private val pendingPings = ConcurrentHashMap<String, Long>()
    private val authFailures = AtomicInteger(0)
    private val sendGate = Mutex()
    private val seen = HashSet<String>()
    private val seenOrder = ArrayDeque<String>()

    @Volatile
    private var crypto: SessionCrypto? = null

    /** Completes once the handshake established who the peer is. */
    val remote: Deferred<DeviceIdentity> get() = remoteIdentity

    /** The fingerprint the peer presented in its hello, for the UI to show next to ours. */
    @Volatile
    var remoteFingerprint: String? = null
        private set

    @Volatile
    var roundTripMillis: Long? = null
        private set

    /** True once the handshake produced session keys, which is the only way a link ever carries data. */
    val isEncrypted: Boolean get() = crypto != null

    /** How many frames failed to authenticate; the transport drops the link once this climbs. */
    val failedAuthCount: Int get() = authFailures.get()

    suspend fun send(message: ClipMessage): Boolean {
        val active = crypto ?: return false
        return runCatching {
            sendGate.withLock {
                // Sealing belongs inside the gate, not before it. The receiver only accepts a strictly
                // increasing nonce counter, so sealing two messages concurrently and then sending them
                // in the other order would make the loser indistinguishable from a replay.
                session.send(Frame.Text(AirClipJson.encode(MessageProtector.protect(message, active))))
            }
        }.isSuccess
    }

    suspend fun sendPing(): Boolean {
        val ping = ClipMessageFactory.ping(identity)
        pendingPings[ping.messageId] = System.nanoTime()
        return send(ping)
    }

    /**
     * Runs the handshake and then reads until the connection dies. [onContent] receives clipboard
     * payloads; identity, heartbeat and replay bookkeeping is handled here.
     */
    suspend fun run(onContent: suspend (ClipContent, DeviceIdentity) -> Unit) {
        val key = keyProvider()
        if (key == null) {
            // Refuse out loud. Simply throwing closes the socket, and a close during the handshake reaches
            // the far side as "对端在握手完成前关闭了连接" — true, useless, and indistinguishable from a
            // crash. The reject frame names the one thing the user has to do. Only worth sending when they
            // dialled us: we have said nothing yet, so it is the only chance to explain.
            if (!dialedByUs) reject("该设备尚未配对")
            throw PeerHandshakeException("本机尚未配对，无法建立加密连接")
        }

        if (dialedByUs) shakeAsClient(key) else shakeAsServer(key)

        sendPing()
        for (frame in session.incoming) {
            val text = when (frame) {
                is Frame.Text -> frame.readText()
                // The handshake is over; anything binary now is a peer speaking a protocol we do not.
                else -> continue
            }
            handle(text, onContent)
        }
    }

    suspend fun close(reason: String) {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) }
        if (!remoteIdentity.isCompleted) {
            remoteIdentity.cancel()
        }
    }

    /** We dialled, so we speak first, and we prove ourselves before being trusted. */
    private suspend fun shakeAsClient(key: PairingKey) {
        val clientChallenge = SessionCrypto.createChallenge()
        sendFrame(HandshakeCodec.writeHello(hello(key, clientChallenge)))

        val frame = readHandshakeFrame()
        HandshakeCodec.readReject(frame)?.let { throw PeerHandshakeException("对端拒绝连接：$it") }
        val theirs = HandshakeCodec.readHello(frame)
            ?: throw PeerHandshakeException("对端没有回应合法的 AirClip 握手帧")
        requireAgreement(theirs.fingerprint, key.fingerprint)

        val established = SessionCrypto.establish(key, clientChallenge, theirs.challenge, isServer = false)
        sendFrame(
            HandshakeCodec.writeProof(
                SessionCrypto.computeProof(
                    key, clientChallenge, theirs.challenge, isServer = false, identity.id,
                ),
            ),
        )

        val proofFrame = readHandshakeFrame()
        HandshakeCodec.readReject(proofFrame)?.let { throw PeerHandshakeException("对端拒绝连接：$it") }
        val verified = SessionCrypto.verifyProof(
            key, clientChallenge, theirs.challenge, isServer = true, theirs.deviceId,
            HandshakeCodec.readProof(proofFrame),
        )
        if (!verified) throw PeerHandshakeException("对端的身份证明校验失败")

        adopt(established, theirs)
    }

    /**
     * They dialled us, so we answer. The order is not arbitrary: we check their fingerprint before
     * revealing our own challenge, and we verify their proof before sending ours, so an unpaired caller
     * learns nothing it could take to another device.
     */
    private suspend fun shakeAsServer(key: PairingKey) {
        val frame = readHandshakeFrame()
        val theirs = HandshakeCodec.readHello(frame)
        if (theirs == null) {
            reject("不是合法的 AirClip 握手帧")
            throw PeerHandshakeException("对端没有发送合法的 AirClip 握手帧")
        }

        if (!fingerprintsAgree(theirs.fingerprint, key.fingerprint)) {
            reject("配对码不一致")
            throw PeerHandshakeException(mismatch(theirs.fingerprint, key.fingerprint))
        }

        val serverChallenge = SessionCrypto.createChallenge()
        sendFrame(HandshakeCodec.writeHello(hello(key, serverChallenge)))
        val established = SessionCrypto.establish(key, theirs.challenge, serverChallenge, isServer = true)

        val proofFrame = readHandshakeFrame()
        val verified = SessionCrypto.verifyProof(
            key, theirs.challenge, serverChallenge, isServer = false, theirs.deviceId,
            HandshakeCodec.readProof(proofFrame),
        )
        if (!verified) {
            reject("身份证明校验失败")
            throw PeerHandshakeException("对端的身份证明校验失败")
        }

        sendFrame(
            HandshakeCodec.writeProof(
                SessionCrypto.computeProof(
                    key, theirs.challenge, serverChallenge, isServer = true, identity.id,
                ),
            ),
        )

        adopt(established, theirs)
    }

    private fun hello(key: PairingKey, challenge: ByteArray) = HandshakeHello(
        deviceId = identity.id,
        deviceName = identity.name,
        platform = PLATFORM,
        fingerprint = key.fingerprint,
        challenge = challenge,
    )

    private fun adopt(established: SessionCrypto, theirs: HandshakeHello) {
        crypto = established
        remoteFingerprint = theirs.fingerprint.takeIf { it.isNotBlank() }?.uppercase()
        remoteIdentity.complete(DeviceIdentity(theirs.deviceId, theirs.deviceName))
    }

    private fun requireAgreement(theirs: String, ours: String) {
        if (!fingerprintsAgree(theirs, ours)) throw PeerHandshakeException(mismatch(theirs, ours))
    }

    /**
     * A blank fingerprint passes: it means a peer that does not publish one, and the proof exchange is
     * what actually decides whether the two sides hold the same secret. A fingerprint that is present
     * and different is worth failing on immediately, naming both values — "配对码不一致" is something the
     * user can act on, whereas the same mismatch discovered as a decryption failure three frames later
     * looks like a bug.
     */
    private fun fingerprintsAgree(theirs: String, ours: String): Boolean =
        theirs.isBlank() || theirs.equals(ours, ignoreCase = true)

    private fun mismatch(theirs: String, ours: String): String =
        "配对码不一致：本机 ${describe(ours)}，对端 ${describe(theirs)}"

    private fun describe(fingerprint: String): String = fingerprint.ifBlank { "未提供" }.uppercase()

    /** Best effort: the socket may already be gone, and the caller is about to throw regardless. */
    private suspend fun reject(reason: String) {
        runCatching { sendFrame(HandshakeCodec.writeReject(reason)) }
    }

    private suspend fun sendFrame(bytes: ByteArray) {
        sendGate.withLock { session.send(Frame.Binary(true, bytes)) }
    }

    private suspend fun readHandshakeFrame(): ByteArray {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Binary -> return frame.readBytes()
                // Clipboard JSON before the handshake finished: either a peer speaking the old
                // plaintext protocol or something that is not AirClip at all. Neither can be trusted.
                is Frame.Text -> throw PeerHandshakeException("对端跳过握手直接发送了报文，可能仍在运行旧版本")
                else -> continue
            }
        }
        throw PeerHandshakeException("对端在握手完成前关闭了连接")
    }

    /**
     * One post-handshake text frame. A frame that will not open is counted rather than fatal — a single
     * corrupted frame should not drop a working link — but the count is what [failedAuthCount] exposes,
     * so a peer that keeps sending garbage is disconnected by the transport instead of being humoured
     * forever.
     */
    private suspend fun handle(text: String, onContent: suspend (ClipContent, DeviceIdentity) -> Unit) {
        val active = crypto ?: return
        val sealed = AirClipJson.decodeOrNull(text) ?: return
        if (sealed.payload == null) {
            // No payload means nothing was sealed, so there is nothing to authenticate it with.
            authFailures.incrementAndGet()
            return
        }

        val message = when (val opened = MessageProtector.unprotect(sealed, active)) {
            is UnprotectResult.Opened -> opened.message.also { authFailures.set(0) }
            is UnprotectResult.Rejected -> {
                authFailures.incrementAndGet()
                return
            }
        }

        if (!remember(message.messageId)) return

        when (message.type) {
            ClipMessageType.PING -> send(ClipMessageFactory.ack(identity, message.messageId))

            // The acked id travels in `hash`, which is under the associated data, so a stale or forged
            // ack cannot invent a round-trip time for a ping we never sent.
            ClipMessageType.ACK -> pendingPings.remove(message.hash)?.let { sentAt ->
                roundTripMillis = (System.nanoTime() - sentAt) / 1_000_000
            }

            else -> when (val decoded = ClipMessageFactory.decode(message)) {
                is DecodedMessage.Content ->
                    onContent(decoded.content, DeviceIdentity(message.deviceId, message.deviceName))

                is DecodedMessage.Control -> Unit
                is DecodedMessage.Rejected -> Unit
            }
        }
    }

    /**
     * False if this `msg_id` has already been handled. The session counter already stops replays; this
     * exists for the honest duplicate — the same clip arriving over two links to the same peer.
     */
    private fun remember(messageId: String): Boolean {
        if (messageId.isEmpty()) return true
        synchronized(seen) {
            if (!seen.add(messageId)) return false
            seenOrder.addLast(messageId)
            if (seenOrder.size > MAX_SEEN) seen.remove(seenOrder.removeFirst())
            return true
        }
    }

    private companion object {
        /** Goes in the hello frame's platform field; the Windows side sends `windows`. */
        const val PLATFORM = "android"
        const val MAX_SEEN = 256
    }
}
