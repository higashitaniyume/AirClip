package com.airclip.platform.net

import android.content.Context
import com.airclip.core.clipboard.ClipContent
import com.airclip.core.crypto.CryptoBox
import com.airclip.core.net.ReceivedContent
import com.airclip.core.net.SyncPeer
import com.airclip.core.net.SyncTransport
import com.airclip.core.protocol.ClipMessageFactory
import com.airclip.core.protocol.DeviceIdentity
import com.airclip.core.protocol.DevicePlatform
import com.airclip.core.protocol.ProtocolConstants
import com.airclip.platform.discovery.DiscoveredService
import com.airclip.platform.discovery.NsdDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

/**
 * mDNS + WebSocket transport. Both platforms advertise *and* listen, so either side can open the
 * connection; the tie-break is lexicographic on device id (lower id dials), with a fallback timer so
 * a peer that never dials still gets connected. Duplicate links to the same device are closed on
 * arrival, keyed by the id from the handshake.
 */
class AirClipTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val identity: () -> DeviceIdentity,
    private val listenPort: () -> Int,
    private val serviceType: () -> String,
    private val cryptoProvider: () -> CryptoBox?,
    private val requireEncryption: () -> Boolean,
) : SyncTransport {

    private val discovery = NsdDiscovery(context, scope, serviceType)

    private val client = HttpClient(OkHttp) {
        install(ClientWebSockets) {
            // Ktor 3.0 takes these as milliseconds; the Duration-typed properties are a later API.
            pingIntervalMillis = PING_INTERVAL.inWholeMilliseconds
            maxFrameSize = MAX_FRAME_SIZE
        }
    }

    private val links = ConcurrentHashMap<String, PeerLink>()
    private val dialing = ConcurrentHashMap<String, Long>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    private var server: EmbeddedServer<*, *>? = null
    private var reconciler: Job? = null
    private var heartbeat: Job? = null
    private var errorWatcher: Job? = null

    private val _peers = MutableStateFlow<List<SyncPeer>>(emptyList())
    override val peers: StateFlow<List<SyncPeer>> = _peers.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _received = MutableSharedFlow<ReceivedContent>(extraBufferCapacity = 16)
    override val received: SharedFlow<ReceivedContent> = _received.asSharedFlow()

    private val _status = MutableStateFlow("未启动")
    override val status: StateFlow<String> = _status.asStateFlow()
    override suspend fun start() {
        if (server != null) return

        val port = listenPort()
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriodMillis = PING_INTERVAL.inWholeMilliseconds
                timeoutMillis = PING_TIMEOUT.inWholeMilliseconds
                maxFrameSize = MAX_FRAME_SIZE
            }
            routing {
                webSocket(ProtocolConstants.WS_PATH) {
                    val remoteHost = call.request.origin.remoteAddress
                    serve(this, dialedByUs = false, host = remoteHost, port = port)
                }
            }
        }

        runCatching { server?.start(wait = false) }
            .onFailure {
                _status.value = "端口 $port 监听失败：${it.message}"
                server = null
                return
            }

        _isListening.value = true
        _status.value = "正在监听 :$port"

        discovery.register(identity(), port, cryptoProvider()?.fingerprint)
        discovery.startDiscovery()

        reconciler = scope.launch { reconcileLoop() }
        heartbeat = scope.launch { heartbeatLoop() }
        // mDNS failures are silent otherwise, and they are the most common reason nothing appears.
        errorWatcher = scope.launch {
            discovery.lastError.collect { message -> if (message != null) _status.value = message }
        }
    }

    override suspend fun stop() {
        reconciler?.cancel()
        heartbeat?.cancel()
        errorWatcher?.cancel()
        reconciler = null
        heartbeat = null
        errorWatcher = null

        links.values.forEach { it.close("stopping") }
        links.clear()
        dialing.clear()
        cooldownUntil.clear()

        discovery.stopDiscovery()
        discovery.unregister()

        runCatching { server?.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS) }
        server = null
        _isListening.value = false
        _peers.value = emptyList()
        _status.value = "已停止"
    }

    override suspend fun broadcast(content: ClipContent): Int {
        val message = ClipMessageFactory.create(content, identity())
        var accepted = 0
        for (link in links.values) {
            if (link.send(message)) accepted++
        }
        _status.value = if (accepted > 0) "已发送到 $accepted 台设备" else "尚无已连接设备"
        return accepted
    }

    override suspend fun connect(peer: SyncPeer) {
        if (links.containsKey(peer.deviceId)) return
        cooldownUntil.remove(peer.deviceId)
        dial(
            DiscoveredService(
                serviceName = peer.deviceName,
                deviceId = peer.deviceId,
                deviceName = peer.deviceName,
                platform = peer.platform,
                host = peer.host,
                port = peer.port,
                fingerprint = peer.remoteFingerprint,
            ),
        )
    }

    override suspend fun disconnect(deviceId: String) {
        links.remove(deviceId)?.close("user disconnected")
        cooldownUntil[deviceId] = now() + MANUAL_DISCONNECT_COOLDOWN_MS
        publishPeers()
    }

    fun rescan() = discovery.rescan()

    fun release() {
        runCatching { client.close() }
    }
    /**
     * Drives one session to completion. The read loop stays on the session's own coroutine (Ktor
     * closes the socket as soon as the handler returns); a side job waits for the handshake so the
     * peer can be registered while the loop is already running.
     */
    private suspend fun serve(session: WebSocketSession, dialedByUs: Boolean, host: String, port: Int) {
        val link = PeerLink(session, identity(), cryptoProvider, requireEncryption, host, port, dialedByUs)
        val registeredId = AtomicReference<String?>(null)

        val registration = scope.launch {
            val remote = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                runCatching { link.remote.await() }.getOrNull()
            }

            if (remote == null || remote.id.isEmpty()) {
                link.close("handshake timeout")
                return@launch
            }

            // Both sides may have dialled at once; the first link to finish its handshake wins.
            val existing = links.putIfAbsent(remote.id, link)
            if (existing != null && existing !== link) {
                link.close("duplicate link")
                return@launch
            }

            registeredId.set(remote.id)
            dialing.remove(remote.id)
            cooldownUntil.remove(remote.id)
            _status.value = "已连接 ${remote.name}"
            publishPeers()
        }

        try {
            link.run { content, from ->
                _received.emit(ReceivedContent(content, from.id, from.name, link.isEncrypted))
            }
        } catch (e: Exception) {
            // Any transport-level failure ends the link; the reconciler will dial again later.
            _status.value = "连接中断：${e.message ?: e::class.java.simpleName}"
        } finally {
            registration.cancel()
            registeredId.get()?.let { id ->
                links.remove(id, link)
                cooldownUntil[id] = now() + DIAL_COOLDOWN_MS
            }
            publishPeers()
        }
    }

    private suspend fun dial(service: DiscoveredService) {
        if (service.deviceId.isEmpty() || links.containsKey(service.deviceId)) return
        dialing[service.deviceId] = now()
        runCatching {
            client.webSocket(wsUrl(service.host, service.port)) {
                serve(this, dialedByUs = true, host = service.host, port = service.port)
            }
        }.onFailure {
            cooldownUntil[service.deviceId] = now() + DIAL_COOLDOWN_MS
        }
        dialing.remove(service.deviceId)
    }
    private suspend fun reconcileLoop() {
        while (scope.isActive) {
            val localId = identity().id
            for (service in discovery.services.value) {
                val id = service.deviceId
                if (id.isEmpty() || links.containsKey(id) || dialing.containsKey(id)) continue
                if (now() < (cooldownUntil[id] ?: 0L)) continue

                // Lower device id dials. The higher one waits a beat and then dials anyway, so a peer
                // that never initiates (or lost its listener) still ends up connected.
                val patience = if (localId < id) 0L else DIAL_FALLBACK_MS
                scope.launch {
                    if (patience > 0) delay(patience)
                    if (!links.containsKey(id) && !dialing.containsKey(id)) dial(service)
                }
            }

            publishPeers()
            delay(RECONCILE_INTERVAL_MS)
        }
    }

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            for ((id, link) in links) {
                if (link.failedAuthCount >= MAX_AUTH_FAILURES) {
                    // Persistent decrypt failures mean the two sides hold different keys.
                    _status.value = "密钥不匹配，已断开 $id"
                    links.remove(id, link)
                    link.close("authentication failures")
                    continue
                }
                if (!link.sendPing()) {
                    links.remove(id, link)
                    link.close("ping failed")
                }
            }
            publishPeers()
        }
    }

    private fun publishPeers() {
        val connected = links.toMap()
        val discovered = discovery.services.value.associateBy { it.deviceId }
        val localFingerprint = cryptoProvider()?.fingerprint

        val merged = LinkedHashMap<String, SyncPeer>()
        for ((id, service) in discovered) {
            if (id.isEmpty()) continue
            val link = connected[id]
            merged[id] = SyncPeer(
                deviceId = id,
                deviceName = service.deviceName,
                platform = service.platform,
                host = link?.host ?: service.host,
                port = service.port,
                isConnected = link != null,
                roundTripMillis = link?.roundTripMillis,
                remoteFingerprint = service.fingerprint,
                isPaired = localFingerprint != null && localFingerprint == service.fingerprint,
                isEncrypted = link?.isEncrypted == true,
            )
        }

        // A peer that dialled us may not be in the mDNS cache yet; it still belongs in the list.
        for ((id, link) in connected) {
            if (merged.containsKey(id)) continue
            merged[id] = SyncPeer(
                deviceId = id,
                deviceName = id,
                platform = DevicePlatform.UNKNOWN,
                host = link.host,
                port = link.port,
                isConnected = true,
                roundTripMillis = link.roundTripMillis,
                isEncrypted = link.isEncrypted,
            )
        }

        _peers.value = merged.values.toList()
    }

    /** IPv6 literals need brackets in a URL; discovery prefers IPv4 but link-local can slip through. */
    private fun wsUrl(host: String, port: Int): String {
        val authority = if (host.contains(':')) "[${host.substringBefore('%')}]" else host
        return "ws://$authority:$port${ProtocolConstants.WS_PATH}"
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        val PING_INTERVAL = 20.seconds
        val PING_TIMEOUT = 45.seconds

        /** 8 MB images become ~11 MB of base64 JSON before sealing; leave generous headroom. */
        const val MAX_FRAME_SIZE = 48L * 1024 * 1024

        const val HANDSHAKE_TIMEOUT_MS = 8_000L
        const val RECONCILE_INTERVAL_MS = 3_000L
        const val HEARTBEAT_INTERVAL_MS = 15_000L
        const val DIAL_FALLBACK_MS = 5_000L
        const val DIAL_COOLDOWN_MS = 6_000L
        const val MANUAL_DISCONNECT_COOLDOWN_MS = 60_000L
        const val SHUTDOWN_GRACE_MS = 300L
        const val SHUTDOWN_TIMEOUT_MS = 1_500L
        const val MAX_AUTH_FAILURES = 5
    }
}
