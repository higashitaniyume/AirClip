package com.airclip.platform.discovery

import com.airclip.core.protocol.DevicePlatform

/** One `_airclip._tcp` service that mDNS resolved to an address we can dial. */
data class DiscoveredService(
    val serviceName: String,
    val deviceId: String,
    val deviceName: String,
    val platform: DevicePlatform,
    val host: String,
    val port: Int,
    /** Peer's pairing-key fingerprint from the TXT record, when it published one. */
    val fingerprint: String?,
)
