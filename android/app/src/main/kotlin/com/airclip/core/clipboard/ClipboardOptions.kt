package com.airclip.core.clipboard

/**
 * Mirrors `AirClip.Core.Clipboard.ClipboardOptions`. Read live on every clipboard item unless noted,
 * so the settings screen can change limits without restarting the service.
 */
data class ClipboardOptions(
    /** A single copy can raise several change callbacks; coalesce them. */
    val debounceMillis: Long = 120,

    /** How long after writing remote data we refuse to publish local changes. */
    val remoteWriteSuppressionMillis: Long = 2_000,

    /** Lifetime of a hash in the recently-seen set. */
    val hashTtlMillis: Long = 20_000,

    val maxTextBytes: Int = 2 * 1024 * 1024,

    val maxImageBytes: Int = 8 * 1024 * 1024,

    /**
     * Skip items a password manager marked with `ClipDescription.EXTRA_IS_SENSITIVE`
     * (`"android.content.extra.IS_SENSITIVE"` on API < 33).
     */
    val honorSensitiveMarkers: Boolean = true,

    val syncImages: Boolean = true,
) {
    companion object {
        val Default = ClipboardOptions()
    }
}

/** Reads the system clipboard on demand. Implementations decide *how* (foreground, IME, Shizuku). */
interface ClipboardReader {
    /** `null` when the clipboard is empty, filtered, or the platform denied the read. */
    suspend fun read(): ClipContent?

    /** Why the last [read] returned `null`, for the UI's capability hints. */
    val lastFailure: ClipboardReadFailure?
}

enum class ClipboardReadFailure {
    EMPTY,
    FILTERED_SENSITIVE,
    UNSUPPORTED_MIME,
    TOO_LARGE,

    /** Android 10+ refused the read: no focus, not the active IME, and no Shizuku backend. */
    DENIED_BACKGROUND,
    ERROR,
}

interface ClipboardWriter {
    suspend fun write(content: ClipContent): Boolean
}
