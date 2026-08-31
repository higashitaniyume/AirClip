package com.airclip.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The three key-derivation primitives AirClip needs, and nothing about what they are used for — the
 * protocol's salts, info strings and lengths all live in [PairingKey], so there is exactly one place
 * to compare against the Windows client.
 *
 * HKDF and PBKDF2 are both written out by hand: `javax.crypto.KDF` does not exist on Android, and the
 * JCA's PBKDF2 leaves the password's character encoding up to the provider. Both are built on the same
 * `HmacSHA256`, which every Android release has.
 */
internal object KeyDerivation {

    /** RFC 5869 HKDF-SHA256, equivalent to .NET's `HKDF.DeriveKey(HashAlgorithmName.SHA256, …)`. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(salt, HMAC_SHA256))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, HMAC_SHA256))
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

    /**
     * PBKDF2-HMAC-SHA256, equivalent to .NET's `Rfc2898DeriveBytes.Pbkdf2` over the phrase's UTF-8
     * bytes. Written out rather than handed to `SecretKeyFactory` on purpose: the JCA API takes a
     * `char[]` and leaves the character encoding to the provider, and BouncyCastle ships both a UTF-8
     * and an 8-bit-per-character variant of PBKDF2. Picking up the wrong one derives a different secret
     * from the same phrase — silently, and for any phrase with a non-ASCII character in it, which for a
     * 中文 口令 is every one of them. Twelve lines of RFC 2898 buys certainty about which bytes are hashed.
     */
    fun pbkdf2Sha256(passphrase: String, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), HMAC_SHA256))

        val out = ByteArray(length)
        var offset = 0
        var block = 1
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))

            var u = mac.doFinal()
            val folded = u.copyOf()
            for (round in 2..iterations) {
                u = mac.doFinal(u)
                for (i in folded.indices) folded[i] = (folded[i].toInt() xor u[i].toInt()).toByte()
            }

            val take = minOf(folded.size, length - offset)
            folded.copyInto(out, offset, 0, take)
            offset += take
            block++
        }
        return out
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(message)
    }

    private const val HMAC_SHA256 = "HmacSHA256"
}
