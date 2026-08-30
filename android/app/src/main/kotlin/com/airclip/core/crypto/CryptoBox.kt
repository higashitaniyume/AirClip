package com.airclip.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM framing for every payload that crosses the network. Frame layout — part of the
 * cross-platform contract, so the Windows side must lay bytes out identically:
 *
 * ```
 * offset size field
 * 0      4    magic  "ACG1"
 * 4      1    version = 0x01
 * 5      1    flags   = 0x00 (reserved)
 * 6      12   nonce (CSPRNG, fresh per frame)
 * 18     n    ciphertext
 * 18+n   16   GCM tag
 * ```
 *
 * The 6-byte header is the AAD, so a downgrade attempt that rewrites `version` or `flags` fails
 * authentication. .NET's `AesGcm` takes the tag as a separate span: it is the trailing 16 bytes.
 *
 * 96-bit random nonces are safe here because a clipboard session never approaches 2^32 frames; a
 * counter would need persisting across restarts to stay safe, which random nonces avoid entirely.
 */
class CryptoBox private constructor(private val key: SecretKeySpec) {

    val fingerprint: String = KeyDerivation.fingerprint(key)

    fun seal(plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))

        val header = header()
        cipher.updateAAD(header)
        val sealed = cipher.doFinal(plaintext)

        val frame = ByteArray(HEADER_SIZE + NONCE_SIZE + sealed.size)
        header.copyInto(frame, 0)
        nonce.copyInto(frame, HEADER_SIZE)
        sealed.copyInto(frame, HEADER_SIZE + NONCE_SIZE)
        return frame
    }

    /** Returns `null` for anything that fails to authenticate: wrong key, tampering, truncation. */
    fun open(frame: ByteArray): ByteArray? {
        if (!isSealed(frame)) return null
        if (frame.size < HEADER_SIZE + NONCE_SIZE + TAG_BITS / 8) return null
        if (frame[VERSION_OFFSET] != VERSION) return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val nonce = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + NONCE_SIZE)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(frame, 0, HEADER_SIZE)
            cipher.doFinal(frame, HEADER_SIZE + NONCE_SIZE, frame.size - HEADER_SIZE - NONCE_SIZE)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private fun header(): ByteArray = byteArrayOf(MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], VERSION, FLAGS)

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val NONCE_SIZE = 12
        private const val HEADER_SIZE = 6
        private const val VERSION_OFFSET = 4
        private const val VERSION: Byte = 0x01
        private const val FLAGS: Byte = 0x00
        private val MAGIC = "ACG1".toByteArray(Charsets.US_ASCII)

        private val random = SecureRandom()

        fun fromKeyMaterial(material: ByteArray): CryptoBox =
            CryptoBox(KeyDerivation.deriveFromKeyMaterial(material))

        fun fromPassphrase(passphrase: String): CryptoBox =
            CryptoBox(KeyDerivation.deriveFromPassphrase(passphrase))

        /** Cheap magic check, so a plaintext JSON frame is never handed to the cipher. */
        fun isSealed(frame: ByteArray): Boolean =
            frame.size > HEADER_SIZE &&
                frame[0] == MAGIC[0] && frame[1] == MAGIC[1] &&
                frame[2] == MAGIC[2] && frame[3] == MAGIC[3]
    }
}
