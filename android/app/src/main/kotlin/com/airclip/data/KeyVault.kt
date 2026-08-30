package com.airclip.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.airclip.core.crypto.CryptoBox
import com.airclip.core.crypto.KeyDerivation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "airclip_secrets")

/**
 * The pairing secret as the user supplied it. Which branch it is decides how it becomes an AES key,
 * so the branch has to be stored alongside the bytes — see `KeyDerivation`.
 *
 * [cryptoBox] on a [Passphrase] runs 200 000 PBKDF2 rounds; never call it on the main thread.
 */
sealed interface PairingSecret {
    fun cryptoBox(): CryptoBox

    /** 32 random bytes, from a pairing QR code or generated here. */
    class RawKey(val material: ByteArray) : PairingSecret {
        override fun cryptoBox(): CryptoBox = CryptoBox.fromKeyMaterial(material)
    }

    /** A pre-shared phrase typed on both devices. */
    class Passphrase(val text: String) : PairingSecret {
        override fun cryptoBox(): CryptoBox = CryptoBox.fromPassphrase(text)
    }
}

/**
 * Stores the pairing secret, wrapped by a non-exportable AES key in the AndroidKeyStore, in the
 * `airclip_secrets` DataStore (excluded from backup, so a restored backup cannot leak it).
 *
 * If the keystore is unavailable — a handful of ROMs fail `KeyGenerator` for GCM — the secret is
 * stored unwrapped and [usesPlaintextFallback] turns on so the UI can say so out loud. Silently
 * dropping to plaintext would be worse than telling the user.
 */
class KeyVault(context: Context) {

    private val store = context.applicationContext.secretsDataStore

    private val _usesPlaintextFallback = MutableStateFlow(false)
    val usesPlaintextFallback: StateFlow<Boolean> = _usesPlaintextFallback.asStateFlow()

    /** Emits `null` when no pairing secret is stored (or the wrapping key no longer opens it). */
    val secret: Flow<PairingSecret?> = store.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map(::decode)

    suspend fun current(): PairingSecret? = withContext(Dispatchers.IO) { secret.first() }

    suspend fun generate(): PairingSecret.RawKey = save(PairingSecret.RawKey(KeyDerivation.randomKeyMaterial()))

    suspend fun saveKeyMaterial(material: ByteArray): PairingSecret? =
        if (material.size == KeyDerivation.KEY_SIZE_BYTES) save(PairingSecret.RawKey(material)) else null

    suspend fun savePassphrase(text: String): PairingSecret? =
        if (text.isNotBlank()) save(PairingSecret.Passphrase(text.trim())) else null

    suspend fun clear() {
        store.edit { preferences ->
            preferences.remove(Keys.mode)
            preferences.remove(Keys.secret)
        }
    }

    private suspend fun <T : PairingSecret> save(value: T): T = withContext(Dispatchers.IO) {
        // Widened to the sealed type on purpose: a `when` whose subject is a type *parameter* is
        // never exhaustive, however narrow the upper bound is.
        val plain = when (val secret: PairingSecret = value) {
            is PairingSecret.RawKey -> secret.material
            is PairingSecret.Passphrase -> secret.text.toByteArray(Charsets.UTF_8)
        }
        val envelope = seal(plain)
        store.edit { preferences ->
            preferences[Keys.mode] = if (value is PairingSecret.RawKey) MODE_KEY else MODE_PASSPHRASE
            preferences[Keys.secret] = envelope
        }
        value
    }

    private fun decode(preferences: Preferences): PairingSecret? {
        val envelope = preferences[Keys.secret] ?: return null
        val plain = open(envelope) ?: return null
        return when (preferences[Keys.mode]) {
            MODE_PASSPHRASE -> PairingSecret.Passphrase(plain.toString(Charsets.UTF_8))
            else -> if (plain.size == KeyDerivation.KEY_SIZE_BYTES) PairingSecret.RawKey(plain) else null
        }
    }

    private fun seal(plain: ByteArray): String {
        val key = wrappingKey() ?: run {
            _usesPlaintextFallback.value = true
            return PLAIN_PREFIX + base64.encodeToString(plain)
        }

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val sealed = cipher.doFinal(plain)
            val iv = cipher.iv
            SEALED_PREFIX + base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + sealed)
        }.getOrElse {
            _usesPlaintextFallback.value = true
            PLAIN_PREFIX + base64.encodeToString(plain)
        }
    }

    /** `null` for a secret this install can no longer open: the user has to pair again. */
    private fun open(envelope: String): ByteArray? = when {
        envelope.startsWith(PLAIN_PREFIX) -> {
            _usesPlaintextFallback.value = true
            runCatching { base64Decoder.decode(envelope.removePrefix(PLAIN_PREFIX)) }.getOrNull()
        }

        envelope.startsWith(SEALED_PREFIX) -> runCatching {
            val raw = base64Decoder.decode(envelope.removePrefix(SEALED_PREFIX))
            val ivSize = raw[0].toInt()
            val key = wrappingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 1, ivSize))
            cipher.doFinal(raw, 1 + ivSize, raw.size - 1 - ivSize)
        }.getOrNull()

        else -> null
    }

    private fun wrappingKey(): SecretKey? {
        val keyStore = runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        }.getOrNull() ?: return null

        runCatching { (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey }
            .getOrNull()
            ?.let { return it }

        return runCatching {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // No user-authentication requirement: the sync service runs unattended.
                    .build(),
            )
            generator.generateKey()
        }.getOrElse { if (it is GeneralSecurityException) null else throw it }
    }

    private object Keys {
        val mode = stringPreferencesKey("secret_mode")
        val secret = stringPreferencesKey("secret_envelope")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "airclip.vault.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val MODE_KEY = "key"
        const val MODE_PASSPHRASE = "passphrase"
        const val SEALED_PREFIX = "v1:"
        const val PLAIN_PREFIX = "p1:"

        val base64: Base64.Encoder = Base64.getEncoder()
        val base64Decoder: Base64.Decoder = Base64.getDecoder()
    }
}
