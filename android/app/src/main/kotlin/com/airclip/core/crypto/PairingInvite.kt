package com.airclip.core.crypto

import com.airclip.core.protocol.ProtocolConstants
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The payload behind a pairing QR code: `airclip://pair?v=1&k=…&n=…&s=…&p=…`. The other device scans
 * it, joins the group and knows where to look, all without anyone typing thirty-two characters.
 *
 * A port of `AirClip.Crypto.PairingInvite`, down to the one-letter parameter names — a QR code is only
 * useful if the device on the other side of the camera reads the same field names this one writes.
 *
 * [toString] deliberately does *not* return the URI. The URI contains the group secret, and a type
 * whose default string form is a secret ends up in a log file sooner or later; call [toUri] when the
 * secret is genuinely what is wanted.
 *
 * Parsed with `java.net` rather than `android.net.Uri` to keep this class testable off-device.
 */
class PairingInvite(
    val key: PairingKey,
    deviceName: String,
    serviceName: String,
    port: Int,
) {
    val deviceName: String = deviceName.trim().ifBlank { "AirClip" }
    val serviceName: String = serviceName.trim().ifBlank { DEFAULT_SERVICE }
    val port: Int = if (port in 1..65535) port else ProtocolConstants.DEFAULT_PORT

    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(PAIR_HOST).append("?v=").append(VERSION)
        append("&k=").append(encode(key.code))
        append("&n=").append(encode(deviceName))
        append("&s=").append(encode(serviceName))
        append("&p=").append(port)
    }

    /** Safe to log: describes the invite by fingerprint and omits the secret entirely. */
    override fun toString(): String =
        "配对邀请 $deviceName · $serviceName · :$port · ${key.fingerprint}"

    companion object {
        const val SCHEME = "airclip"
        const val PAIR_HOST = "pair"
        const val VERSION = "1"

        /** The full mDNS name. `NsdManager` wants the shorter `ProtocolConstants.SERVICE_TYPE`. */
        const val DEFAULT_SERVICE = "_airclip._tcp.local."

        /** `null` for anything that is not an `airclip://pair` URI carrying a usable pairing code. */
        fun parse(text: String): PairingInvite? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
            // Both airclip://pair?... and airclip:pair?... are accepted; QR scanners differ.
            val target = uri.authority ?: uri.path?.trim('/')
            if (!PAIR_HOST.equals(target, ignoreCase = true)) return null

            val query = parseQuery(uri.rawQuery ?: return null)
            val key = query["k"]?.let(PairingKey::parse) ?: return null
            val port = query["p"]?.toIntOrNull() ?: ProtocolConstants.DEFAULT_PORT

            return PairingInvite(
                key = key,
                deviceName = query["n"].orEmpty(),
                serviceName = query["s"].orEmpty(),
                port = port,
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
