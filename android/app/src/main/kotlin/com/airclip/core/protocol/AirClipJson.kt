package com.airclip.core.protocol

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration used on the wire. It is tuned to produce byte-compatible output with
 * the Windows client's `AirClipJson`:
 *
 *  - `explicitNulls = false` matches `JsonIgnoreCondition.WhenWritingNull` (drop null width/height).
 *  - `encodeDefaults = true` keeps `version`/`hash` on the wire even when they equal their defaults.
 *  - `ignoreUnknownKeys = true` lets either side add fields without breaking the other.
 */
object AirClipJson {
    val format: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun encode(message: ClipMessage): String = format.encodeToString(ClipMessage.serializer(), message)

    /** Returns `null` instead of throwing: a malformed frame must not tear down the connection. */
    fun decodeOrNull(json: String): ClipMessage? = runCatching {
        format.decodeFromString(ClipMessage.serializer(), json)
    }.getOrNull()
}
