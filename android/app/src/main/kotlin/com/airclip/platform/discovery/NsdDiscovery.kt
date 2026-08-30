package com.airclip.platform.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.getSystemService
import com.airclip.core.protocol.DevicePlatform
import com.airclip.core.protocol.DeviceIdentity
import com.airclip.core.protocol.ProtocolConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * mDNS advertising and discovery over the platform's `NsdManager`.
 *
 * Two platform quirks shape this class:
 *  - `resolveService` only handles one request at a time and silently fails the rest, so resolves
 *    are serialised behind [resolveMutex] and fed from a queue.
 *  - several OEM builds drop multicast unless a `WifiManager.MulticastLock` is held, so one is taken
 *    for as long as discovery runs.
 */
class NsdDiscovery(
    context: Context,
    private val scope: CoroutineScope,
    /** Read on every register/discover call so the settings screen can change the mDNS type. */
    private val serviceType: () -> String = { ProtocolConstants.SERVICE_TYPE },
) {
    private val appContext = context.applicationContext
    private val nsd: NsdManager? = appContext.getSystemService()

    private val _services = MutableStateFlow<List<DiscoveredService>>(emptyList())
    val services: StateFlow<List<DiscoveredService>> = _services.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val found = LinkedHashMap<String, DiscoveredService>()
    private val foundLock = Any()
    private val resolveMutex = Mutex()
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.BUFFERED)

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var localDeviceId: String = ""

    init {
        scope.launch {
            for (info in resolveQueue) {
                resolveMutex.withLock { resolve(info) }
            }
        }
    }

    /** Advertises this device. [fingerprint] is published so peers can spot a key mismatch early. */
    fun register(identity: DeviceIdentity, port: Int, fingerprint: String?) {
        val manager = nsd ?: return
        localDeviceId = identity.id
        unregister()

        val advertisedType = serviceType()
        val info = NsdServiceInfo().apply {
            // Suffixing with the device id keeps two phones on one LAN from colliding.
            serviceName = "AirClip-${identity.name.take(24)}-${identity.id.take(6)}"
            serviceType = advertisedType
            this.port = port
            setAttribute(ProtocolConstants.TXT_DEVICE_ID, identity.id)
            setAttribute(ProtocolConstants.TXT_DEVICE_NAME, identity.name)
            setAttribute(ProtocolConstants.TXT_PLATFORM, "android")
            setAttribute(ProtocolConstants.TXT_VERSION, ProtocolConstants.VERSION)
            fingerprint?.let { setAttribute(ProtocolConstants.TXT_KEY_FINGERPRINT, it) }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _lastError.value = "mDNS 注册失败 ($errorCode)"
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }

        registrationListener = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { _lastError.value = "mDNS 注册异常：${it.message}" }
    }

    fun unregister() {
        val manager = nsd ?: return
        registrationListener?.let { runCatching { manager.unregisterService(it) } }
        registrationListener = null
    }
    fun startDiscovery() {
        val manager = nsd ?: run {
            _lastError.value = "系统不支持 NsdManager"
            return
        }
        if (discoveryListener != null) return

        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _lastError.value = null
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                resolveQueue.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                removeByServiceName(info.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _lastError.value = "mDNS 发现失败 ($errorCode)"
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }
        }

        discoveryListener = listener
        runCatching {
            manager.discoverServices(serviceType(), NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            discoveryListener = null
            _lastError.value = "mDNS 发现异常：${it.message}"
        }
    }

    fun stopDiscovery() {
        val manager = nsd ?: return
        discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        discoveryListener = null
        releaseMulticastLock()
        synchronized(foundLock) { found.clear() }
        _services.value = emptyList()
    }

    /** Drops the cache and re-runs discovery: what the "重新扫描" button does. */
    fun rescan() {
        stopDiscovery()
        startDiscovery()
    }
    private suspend fun resolve(info: NsdServiceInfo) {
        val manager = nsd ?: return
        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(info)
                    }
                }
                @Suppress("DEPRECATION") // registerServiceInfoCallback is API 34+; this path is 29+.
                runCatching { manager.resolveService(info, listener) }
                    .onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        } ?: return

        toDiscovered(resolved)?.let { service ->
            if (service.deviceId.isNotEmpty() && service.deviceId == localDeviceId) return
            synchronized(foundLock) { found[service.deviceId.ifEmpty { service.serviceName }] = service }
            publish()
        }
    }

    private fun toDiscovered(info: NsdServiceInfo): DiscoveredService? {
        val host = hostAddress(info) ?: return null
        val attributes = info.attributes.orEmpty()
        fun attribute(key: String): String? = attributes[key]?.toString(Charsets.UTF_8)?.takeIf { it.isNotEmpty() }

        return DiscoveredService(
            serviceName = info.serviceName.orEmpty(),
            deviceId = attribute(ProtocolConstants.TXT_DEVICE_ID).orEmpty(),
            deviceName = attribute(ProtocolConstants.TXT_DEVICE_NAME) ?: info.serviceName.orEmpty(),
            platform = DevicePlatform.parse(attribute(ProtocolConstants.TXT_PLATFORM)),
            host = host,
            port = if (info.port in 1..65535) info.port else ProtocolConstants.DEFAULT_PORT,
            fingerprint = attribute(ProtocolConstants.TXT_KEY_FINGERPRINT),
        )
    }

    /** Prefers IPv4: link-local IPv6 needs a scope id that a plain WebSocket URL cannot carry. */
    private fun hostAddress(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }

        val preferred = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        return preferred?.hostAddress?.takeIf { it.isNotEmpty() }
    }

    private fun removeByServiceName(serviceName: String?) {
        if (serviceName.isNullOrEmpty()) return
        val changed = synchronized(foundLock) {
            found.entries.removeAll { it.value.serviceName == serviceName }
        }
        if (changed) publish()
    }

    private fun publish() {
        _services.value = synchronized(foundLock) { found.values.toList() }
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi: WifiManager = appContext.getSystemService() ?: return
        multicastLock = runCatching {
            wifi.createMulticastLock("airclip-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 6_000L
    }
}
