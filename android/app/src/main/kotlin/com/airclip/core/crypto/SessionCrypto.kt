package com.airclip.core.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One connection's worth of AES-256-GCM, keyed by the group secret and both sides' handshake
 * challenges, with a separate key per direction. A port of `AirClip.Crypto.SessionCrypto`; two
 * properties are worth spelling out because they are what make the scheme safe rather than merely
 * encrypted:
 *
 *  - Nonces are a counter, not random. Ninety-six random bits per message would be fine in practice,
 *    but "fine in practice" is doing a birthday-bound calculation on the user's behalf; a counter under
 *    a key that exists only for this connection simply cannot repeat.
 *  - The counter must strictly increase on receive, which turns the nonce into replay protection for
 *    free: a captured frame replayed later decrypts correctly and is still rejected.
 *
 * On the wire a sealed payload is `nonce || ciphertext || tag`, where the nonce is four zero bytes
 * followed by the counter as a big-endian 64-bit integer. .NET's `AesGcm` takes the tag as a separate
 * span; Java's `Cipher` appends it to the ciphertext, which lands on the same bytes in the same order.
 */
class SessionCrypto private constructor(
    private val sendKey: SecretKeySpec,
    private val receiveKey: SecretKeySpec,
) {
    private val sendCounter = AtomicLong(0)

    @Volatile
    private var highestReceived = 0L

    /** Returns `nonce || ciphertext || tag`, which is what goes on the wire. */
    fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val counter = sendCounter.incrementAndGet()
        val nonce = nonceFor(counter)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        val sealed = cipher.doFinal(plaintext)

        val envelope = ByteArray(NONCE_SIZE + sealed.size)
        nonce.copyInto(envelope, 0)
        sealed.copyInto(envelope, NONCE_SIZE)
        return envelope
    }

    /**
     * `null` for every reason a frame can be wrong — truncated, replayed, tampered with, or sent under
     * a different group secret — and never says which, because the caller has no use for the
     * distinction and an attacker would.
     */
    fun open(envelope: ByteArray, associatedData: ByteArray): ByteArray? {
        if (envelope.size < OVERHEAD) return null

        val counter = ByteBuffer.wrap(envelope, NONCE_SIZE - Long.SIZE_BYTES, Long.SIZE_BYTES).long
        // Also rejects a counter that wrapped or was forged negative; the sender always starts at 1.
        if (counter <= highestReceived) return null

        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                receiveKey,
                GCMParameterSpec(TAG_BITS, envelope, 0, NONCE_SIZE),
            )
            cipher.updateAAD(associatedData)
            cipher.doFinal(envelope, NONCE_SIZE, envelope.size - NONCE_SIZE)
        } catch (e: GeneralSecurityException) {
            return null
        }

        highestReceived = counter
        return plaintext
    }

    private fun nonceFor(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        ByteBuffer.wrap(nonce).putInt(0).putLong(counter)
        return nonce
    }

    companion object {
        const val CHALLENGE_SIZE = 32
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val OVERHEAD = NONCE_SIZE + TAG_SIZE

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = TAG_SIZE * 8
        private const val CLIENT_TO_SERVER = "client-to-server"
        private const val SERVER_TO_CLIENT = "server-to-client"
        private const val AUTH_CONTEXT = "airclip-auth-v1"

        private val random = SecureRandom()

        fun createChallenge(): ByteArray = ByteArray(CHALLENGE_SIZE).also(random::nextBytes)

        /**
         * Both sides run this with the same two challenges and disagree only on [isServer], which is
         * what swaps the send and receive keys so neither direction shares a key/nonce space.
         */
        fun establish(
            key: PairingKey,
            clientChallenge: ByteArray,
            serverChallenge: ByteArray,
            isServer: Boolean,
        ): SessionCrypto {
            require(clientChallenge.size == CHALLENGE_SIZE && serverChallenge.size == CHALLENGE_SIZE) {
                "握手随机数必须是 $CHALLENGE_SIZE 字节"
            }

            val salt = clientChallenge + serverChallenge
            val clientKey = SecretKeySpec(key.deriveSessionKey(salt, CLIENT_TO_SERVER), "AES")
            val serverKey = SecretKeySpec(key.deriveSessionKey(salt, SERVER_TO_CLIENT), "AES")
            return if (isServer) SessionCrypto(serverKey, clientKey) else SessionCrypto(clientKey, serverKey)
        }

        /**
         * Proof that the far side holds the group secret, bound to this handshake and to who is claiming
         * what: without the device id and the role in the MAC, a proof could be reflected back at its
         * author.
         */
        fun computeProof(
            key: PairingKey,
            clientChallenge: ByteArray,
            serverChallenge: ByteArray,
            isServer: Boolean,
            deviceId: String,
        ): ByteArray {
            val message = AUTH_CONTEXT.toByteArray(Charsets.UTF_8) +
                (if (isServer) "server" else "client").toByteArray(Charsets.UTF_8) +
                deviceId.toByteArray(Charsets.UTF_8) +
                clientChallenge +
                serverChallenge
            return key.computeMac(message)
        }

        fun verifyProof(
            key: PairingKey,
            clientChallenge: ByteArray,
            serverChallenge: ByteArray,
            isServer: Boolean,
            deviceId: String,
            candidate: ByteArray?,
        ): Boolean {
            if (candidate == null) return false
            val expected = computeProof(key, clientChallenge, serverChallenge, isServer, deviceId)
            return MessageDigest.isEqual(expected, candidate)
        }
    }
}
