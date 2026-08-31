package com.airclip.core.crypto

import java.security.SecureRandom

/**
 * The pre-shared secret shared by every device in one AirClip group: twenty random bytes, shown to the
 * user as eight four-character Crockford Base32 groups so it can be read off one screen and typed on
 * another. The AES-256 master key is derived from those bytes rather than being them, which keeps the
 * length of the code the user types independent of the length the cipher wants.
 *
 * This is a port of `AirClip.Crypto.PairingKey`, and the port has to be exact: every constant below is
 * part of a cross-platform contract, and a single character out of place in a salt or an info string
 * gives the two ends different fingerprints and different keys, which surfaces to the user as a phone
 * and a PC that each insist the other's pairing code is wrong.
 *
 * Test vectors, pinned on the other side by `windows/tests/AirClip.Sync.Tests/CrossPlatformVectorTests.cs`
 * and computed from the RFCs by a third implementation, so neither client is the authority. Secret
 * `00 01 02 … 13`:
 * ```
 * code        = 000G-40R4-0M30-E209-185G-R38E-1W81-24GK
 * fingerprint = B4171D99
 * master key  = F161806BFFDE66EA16DB67A85141A151BBD20595A73A4149431FE8E1713C3086
 * ```
 * The passphrase path, `pass:airclip-口令-2026` — non-ASCII on purpose, since that is the only input that
 * exposes a PBKDF2 implementation which hashes something other than the phrase's UTF-8 bytes:
 * ```
 * secret      = 106F3EE587130825BF4643061584A038B236DF57
 * code        = 21QK-XSC7-2C42-BFT6-8C31-B150-72S3-DQTQ
 * fingerprint = 8D56C7C3
 * ```
 * And from the first vector's master key, with `clientChallenge` = 32 × `0x01` and `serverChallenge` =
 * 32 × `0x02`, which is what [SessionCrypto] builds its salt and proofs from:
 * ```
 * client-to-server = 9764DACAF082D02C2179E6B94110B48D11B6505502FA63E7AD37250B59E3F66A
 * server-to-client = 6088D127EA401F8FB6F94531B6B9BF7FE50139934E375BB55357E0645CB92310
 * proof, client / device-a = 701F1D68777A298FB5813749DBDB1887267CF21AAE810810DB93439EBAD456D4
 * proof, server / device-b = 54280ACFE572EF745FDBBDD2AE8B209B9379D5AD6D5E1E23D05DCEE0D3C9F3B8
 * ```
 */
class PairingKey private constructor(private val secret: ByteArray) {

    private val masterKey: ByteArray =
        KeyDerivation.hkdfSha256(secret, SALT, MASTER_INFO, MASTER_KEY_SIZE_BYTES)

    /**
     * Four bytes of a derived hash, shown in the UI and published in the mDNS TXT record so two devices
     * can be compared at a glance and a mismatched group is diagnosed before anyone reads a log. It is
     * derived, never the secret itself, and 32 bits of it says nothing usable about the other 160.
     */
    val fingerprint: String = Codecs.toHex(
        KeyDerivation.hkdfSha256(secret, SALT, FINGERPRINT_INFO, FINGERPRINT_SIZE_BYTES),
    )

    /** The user-facing code, in groups: `A1B2-C3D4-…`. */
    val code: String get() = Codecs.group(Codecs.toBase32(secret))

    /** Copies the secret out for at-rest protection. The caller owns clearing what it gets. */
    fun exportSecret(): ByteArray = secret.copyOf()

    /**
     * Session keys are always derived, never the master key itself: a fresh key per connection and per
     * direction means a nonce counter can restart at zero without ever repeating a key/nonce pair.
     */
    fun deriveSessionKey(salt: ByteArray, purpose: String, lengthBytes: Int = MASTER_KEY_SIZE_BYTES): ByteArray =
        KeyDerivation.hkdfSha256(masterKey, salt, purpose.toByteArray(Charsets.UTF_8), lengthBytes)

    /** Keyed proof that the far side holds the same secret, used by the handshake. */
    fun computeMac(message: ByteArray): ByteArray = KeyDerivation.hmacSha256(masterKey, message)

    fun createInvite(deviceName: String, serviceName: String, port: Int): PairingInvite =
        PairingInvite(this, deviceName, serviceName, port)

    /** Describes the key by fingerprint; the secret never reaches a log through here. */
    override fun toString(): String = "配对码 $fingerprint"

    companion object {
        /** Entropy the user actually handles: 160 bits, far past brute-force, still typeable. */
        const val SECRET_SIZE_BYTES = 20

        const val MASTER_KEY_SIZE_BYTES = 32

        /**
         * Marks the rest of the input as a shared phrase rather than a pairing code. It is an explicit
         * prefix and not a fallback for "text that failed to parse": a scheme where any unrecognised
         * string silently becomes a group key turns one typo into a device that pairs with nothing and
         * reports no error. The Windows client accepts the same prefix.
         */
        const val PASSPHRASE_PREFIX = "pass:"

        const val PASSPHRASE_ITERATIONS = 200_000

        /** Short enough to be memorable, long enough that stretching it is not a formality. */
        const val MIN_PASSPHRASE_LENGTH = 8

        private const val FINGERPRINT_SIZE_BYTES = 4

        private val SALT = "airclip-pairing-v1".toByteArray(Charsets.UTF_8)
        private val MASTER_INFO = "airclip-master-key-v1".toByteArray(Charsets.UTF_8)
        private val FINGERPRINT_INFO = "airclip-fingerprint-v1".toByteArray(Charsets.UTF_8)

        private val random = SecureRandom()

        fun create(): PairingKey = PairingKey(ByteArray(SECRET_SIZE_BYTES).also(random::nextBytes))

        /** `null` unless [secret] is exactly [SECRET_SIZE_BYTES] long. */
        fun fromSecret(secret: ByteArray): PairingKey? =
            if (secret.size == SECRET_SIZE_BYTES) PairingKey(secret.copyOf()) else null

        /**
         * Stretches a phrase typed on both devices into the same twenty bytes a random code would have
         * supplied, so everything downstream is unaware of which pairing style was used. PBKDF2 rather
         * than HKDF because a human phrase has nothing like 160 bits of entropy, and stretching is the
         * only thing standing between it and a dictionary.
         *
         * Runs 200 000 rounds: never call it on the main thread.
         */
        fun fromPassphrase(passphrase: String): PairingKey? {
            val phrase = passphrase.trim()
            if (phrase.length < MIN_PASSPHRASE_LENGTH) return null
            return PairingKey(
                KeyDerivation.pbkdf2Sha256(phrase, SALT, PASSPHRASE_ITERATIONS, SECRET_SIZE_BYTES),
            )
        }

        /**
         * Accepts what a user is likely to hand over: the grouped code, the same code without
         * separators or in lower case, a whole `airclip://pair?…` invite from a QR scan, or a shared
         * phrase behind the explicit [PASSPHRASE_PREFIX] marker. `null` for everything else —
         * deliberately, so a mistyped code is reported as a mistyped code.
         */
        fun parse(text: String?): PairingKey? {
            val candidate = text?.trim()
            if (candidate.isNullOrEmpty()) return null

            if (candidate.startsWith(PASSPHRASE_PREFIX, ignoreCase = true)) {
                return fromPassphrase(candidate.substring(PASSPHRASE_PREFIX.length))
            }

            // Covers both airclip://pair?… and the airclip:pair?… form some QR scanners produce.
            if (candidate.startsWith(PairingInvite.SCHEME + ":", ignoreCase = true)) {
                return PairingInvite.parse(candidate)?.key
            }

            return Codecs.fromBase32(candidate, SECRET_SIZE_BYTES)?.let(::PairingKey)
        }
    }
}
