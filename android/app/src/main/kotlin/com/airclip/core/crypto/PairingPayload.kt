package com.airclip.core.crypto

import com.airclip.core.protocol.DevicePlatform
import com.airclip.core.protocol.ProtocolConstants
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a pairing QR code contains. Rendered as `airclip://pair?...` so the same string works as a
 * QR payload, a deep link, and something a user can paste. Parsed with `java.net` rather than
 * `android.net.Uri` to keep this class testable off-device.
 *
 * Example:
 * `airclip://pair?v=1&id=win-desktop-01&name=My%20Windows%20PC&plat=windows&host=192.168.1.20&port=47653&key=<43 base64url chars>`
 */
data class PairingPayload(
    val deviceId: String,
    val deviceName: String,
    val platform: DevicePlatform,
    val host: String?,
    val port: Int,
    val keyMaterial: ByteArray,
) {
    val fingerprint: String get() = CryptoBox.fromKeyMaterial(keyMaterial).fingerprint

    fun toUri(): String = buildString {
        append("airclip://pair?v=1")
        append("&id=").append(encode(deviceId))
        append("&name=").append(encode(deviceName))
        append("&plat=").append(platform.name.lowercase())
        if (!host.isNullOrBlank()) append("&host=").append(encode(host))
        append("&port=").append(port)
        append("&key=").append(Codecs.base64Url.encodeToString(keyMaterial))
    }

    override fun equals(other: Any?): Boolean = other is PairingPayload &&
        other.deviceId == deviceId && other.deviceName == deviceName && other.platform == platform &&
        other.host == host && other.port == port && other.keyMaterial.contentEquals(keyMaterial)

    override fun hashCode(): Int = deviceId.hashCode() * 31 + keyMaterial.contentHashCode()

    companion object {
        const val SCHEME = "airclip"
        const val HOST = "pair"

        /** `null` for anything that is not a v1 pairing URI carrying a 32-byte key. */
        fun parse(text: String): PairingPayload? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
            // Both airclip://pair?... and airclip:pair?... are accepted; scanners differ.
            val target = uri.authority ?: uri.path?.trim('/')
            if (!HOST.equals(target, ignoreCase = true)) return null

            val query = parseQuery(uri.rawQuery ?: return null)
            val key = query["key"]?.let(Codecs::decodeKeyMaterial) ?: return null
            if (key.size != KeyDerivation.KEY_SIZE_BYTES) return null

            val port = query["port"]?.toIntOrNull() ?: ProtocolConstants.DEFAULT_PORT
            return PairingPayload(
                deviceId = query["id"].orEmpty(),
                deviceName = query["name"].orEmpty(),
                platform = DevicePlatform.parse(query["plat"]),
                host = query["host"],
                port = if (port in 1..65535) port else ProtocolConstants.DEFAULT_PORT,
                keyMaterial = key,
            )
        }

        private fun parseQuery(raw: String): Map<String, String> = raw.split('&')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null else decode(pair.substring(0, index)) to decode(pair.substring(index + 1))
            }
            .toMap()

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: UnsupportedEncodingException) {
            value
        } catch (e: IllegalArgumentException) {
            value
        }

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
