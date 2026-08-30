package com.airclip.core.net

import com.airclip.core.clipboard.ClipContent
import com.airclip.core.protocol.DevicePlatform
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A device on the LAN, discovered or connected. Mirrors the Windows `SyncPeer` record. */
data class SyncPeer(
    val deviceId: String,
    val deviceName: String,
    val platform: DevicePlatform,
    val host: String,
    val port: Int,
    val isConnected: Boolean,
    val roundTripMillis: Long? = null,
    /** From the peer's mDNS TXT record; a mismatch with ours means pairing keys differ. */
    val remoteFingerprint: String? = null,
    val isPaired: Boolean = false,
    val isEncrypted: Boolean = false,
) {
    val endpoint: String get() = "$host:$port"
}

data class ReceivedContent(
    val content: ClipContent,
    val fromDeviceId: String,
    val fromDeviceName: String,
    val wasEncrypted: Boolean,
)

/**
 * The networking seam. `AirClipTransport` is the mDNS + WebSocket implementation; keeping the
 * interface here lets the UI and the foreground service be written without touching Ktor.
 */
interface SyncTransport {
    val peers: StateFlow<List<SyncPeer>>

    val isListening: StateFlow<Boolean>

    /** Clipboard content that arrived from a peer and should be applied locally. */
    val received: SharedFlow<ReceivedContent>

    /** Human-readable transport diagnostics for the UI's status line. */
    val status: StateFlow<String>

    suspend fun start()

    suspend fun stop()

    /** Sends content to every connected peer; returns how many accepted it. */
    suspend fun broadcast(content: ClipContent): Int

    /** Dials a peer immediately instead of waiting for the discovery loop. */
    suspend fun connect(peer: SyncPeer)

    suspend fun disconnect(deviceId: String)
}
