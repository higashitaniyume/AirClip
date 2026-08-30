package com.airclip.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * How a pairing secret becomes the AES-256 session key. This is a cross-platform contract; the
 * Windows client must reproduce it exactly (`System.Security.Cryptography.HKDF.DeriveKey` and
 * `Rfc2898DeriveBytes.Pbkdf2` are the .NET equivalents of the two branches below).
 *
 *  - QR / raw key  : HKDF-SHA256(ikm = 32 random bytes, salt = SALT, info = INFO, L = 32)
 *  - passphrase    : PBKDF2-HMAC-SHA256(password, salt = SALT, iterations = 200_000, L = 32)
 *
 * The branches differ on purpose: a random 256-bit key needs no stretching, a human passphrase does.
 */
object KeyDerivation {
    const val KEY_SIZE_BYTES = 32
    const val PASSPHRASE_ITERATIONS = 200_000

    private val SALT = "airclip/psk-v1".toByteArray(Charsets.US_ASCII)
    private val INFO = "airclip/aes-256-gcm/v1".toByteArray(Charsets.US_ASCII)
    private val FINGERPRINT_DOMAIN = "airclip/fp1".toByteArray(Charsets.US_ASCII)

    private val random = SecureRandom()

    fun randomKeyMaterial(): ByteArray = ByteArray(KEY_SIZE_BYTES).also(random::nextBytes)

    fun deriveFromKeyMaterial(material: ByteArray): SecretKeySpec {
        require(material.isNotEmpty()) { "key material must not be empty" }
        return SecretKeySpec(hkdfSha256(material, SALT, INFO, KEY_SIZE_BYTES), "AES")
    }

    fun deriveFromPassphrase(passphrase: String): SecretKeySpec {
        require(passphrase.isNotBlank()) { "passphrase must not be blank" }
        val spec = PBEKeySpec(passphrase.toCharArray(), SALT, PASSPHRASE_ITERATIONS, KEY_SIZE_BYTES * 8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Short, human-comparable tag for a derived key: SHA-256("airclip/fp1" || key), first 8 hex
     * chars. Domain-separated and truncated so publishing it in an mDNS TXT record leaks as little
     * as possible about the key itself.
     */
    fun fingerprint(key: SecretKeySpec): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FINGERPRINT_DOMAIN)
        digest.update(key.encoded)
        return Codecs.toHex(digest.digest()).take(8)
    }

    /** RFC 5869 HKDF-SHA256. Written out because `javax.crypto.KDF` is not on Android. */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, take)
            offset += take
            counter++
        }
        return out
    }
}
