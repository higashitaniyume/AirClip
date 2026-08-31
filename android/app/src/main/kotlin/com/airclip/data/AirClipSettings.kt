package com.airclip.data

import com.airclip.core.clipboard.ClipboardOptions
import com.airclip.core.protocol.ProtocolConstants
import java.util.UUID

/**
 * Everything the user can configure. The first block mirrors `AirClip.App.Services.AppSettings`
 * field for field so the two clients stay comparable; the second block is Android-only, because the
 * platform's clipboard restrictions have no Windows counterpart.
 */
data class AirClipSettings(
    val deviceId: String = "",
    val deviceName: String = "",
    /** Advertised mDNS type. Stored in the full `_airclip._tcp.local.` form, like the Windows side. */
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val listenPort: Int = ProtocolConstants.DEFAULT_PORT,
    val syncImages: Boolean = true,
    val honorSensitiveMarkers: Boolean = true,
    val keepHistory: Boolean = true,
    val historyLimit: Int = 100,
    val maxTextKb: Int = 2048,
    val maxImageKb: Int = 8192,
    val debounceMs: Int = 120,
    val notifyOnReceive: Boolean = true,

    /** The home switch: what the user asked for, which is what `BootReceiver` restores. */
    val serviceEnabled: Boolean = false,
    val startOnBoot: Boolean = false,

    /** Off means received content only raises a notification with 一键复制. */
    val autoApplyRemote: Boolean = true,

    /** Extra "收到内容" toast on top of the notification; cheap reassurance while pairing. */
    val toastOnReceive: Boolean = true,

    /** Let the accessibility path flash a 1x1 focusable overlay so the platform allows the read. */
    val overlayAssist: Boolean = true,

    /** Shizuku is the only backend that has to poll, so it is opt-in. */
    val shizukuPolling: Boolean = false,
    val shizukuPollMillis: Long = 1_000,
) {
    /** The mDNS type in the two-label form `NsdManager` insists on. */
    val nsdServiceType: String
        get() = serviceName.trim()
            .removeSuffix(".")
            .removeSuffix(".local")
            .let { if (it.endsWith(".")) it else "$it." }
            .ifBlank { ProtocolConstants.SERVICE_TYPE }

    fun clipboardOptions(): ClipboardOptions = ClipboardOptions(
        debounceMillis = debounceMs.toLong(),
        maxTextBytes = maxTextKb * 1024,
        maxImageBytes = maxImageKb * 1024,
        honorSensitiveMarkers = honorSensitiveMarkers,
        syncImages = syncImages,
    )

    /** Port of `AppSettings.EnsureIdentity()`: fills machine-specific gaps and clamps the ranges. */
    fun normalised(fallbackName: String = "Android"): AirClipSettings = copy(
        deviceId = deviceId.ifBlank { UUID.randomUUID().toString().replace("-", "").take(12) },
        deviceName = deviceName.ifBlank { fallbackName }.take(48),
        serviceName = serviceName.ifBlank { DEFAULT_SERVICE_NAME },
        listenPort = if (listenPort in 1024..65535) listenPort else ProtocolConstants.DEFAULT_PORT,
        historyLimit = historyLimit.coerceIn(10, 500),
        maxTextKb = maxTextKb.coerceIn(1, 32 * 1024),
        maxImageKb = maxImageKb.coerceIn(16, 64 * 1024),
        debounceMs = debounceMs.coerceIn(20, 2_000),
        shizukuPollMillis = shizukuPollMillis.coerceIn(250, 10_000),
    )

    companion object {
        const val DEFAULT_SERVICE_NAME = "_airclip._tcp.local."
    }
}
