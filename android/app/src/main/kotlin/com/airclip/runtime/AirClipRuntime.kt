package com.airclip.runtime

import android.content.Context
import com.airclip.core.clipboard.ClipContent
import com.airclip.core.clipboard.ClipboardOptions
import com.airclip.core.clipboard.ClipboardReadFailure
import com.airclip.core.crypto.CryptoBox
import com.airclip.core.net.ReceivedContent
import com.airclip.core.protocol.DeviceIdentity
import com.airclip.core.sync.ClipboardSyncEngine
import com.airclip.core.sync.LoopGuard
import com.airclip.core.sync.PublishResult
import com.airclip.core.sync.PublishSource
import com.airclip.data.AirClipSettings
import com.airclip.data.HistoryStore
import com.airclip.data.KeyVault
import com.airclip.data.SettingsStore
import com.airclip.platform.clipboard.AndroidClipboardReader
import com.airclip.platform.clipboard.AndroidClipboardWriter
import com.airclip.platform.net.AirClipTransport
import com.airclip.platform.shizuku.ShizukuClipboardBackend
import com.airclip.platform.shizuku.ShizukuGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/** Result of one "send my clipboard" attempt, in the words the UI and the services need. */
sealed interface SendOutcome {
    data class Sent(val peers: Int, val content: ClipContent) : SendOutcome

    /** Recognised as an echo of content that just came in; deliberately dropped. */
    data object Suppressed : SendOutcome

    data object ServiceOff : SendOutcome

    data object Paused : SendOutcome

    data class Failed(val reason: ClipboardReadFailure) : SendOutcome
}

/** Things worth telling the user about, wherever they happen to be looking. */
sealed interface AirClipEvent {
    data class Sent(val peers: Int, val content: ClipContent) : AirClipEvent

    data class SendFailed(val reason: ClipboardReadFailure) : AirClipEvent

    data class Received(val received: ReceivedContent, val applied: Boolean) : AirClipEvent

    data class Notice(val message: String) : AirClipEvent
}

/**
 * The single application-scoped object that owns every moving part: settings, keys, the clipboard
 * reader/writer pair, the loop guard, the transport and the history.
 *
 * There is deliberately no dependency-injection framework. Five entry points (the activity, the
 * foreground service, the IME, the accessibility service and the tile) all need the *same* engine
 * instance — a graph that lives in `Application` is the shortest honest way to guarantee that.
 */
class AirClipRuntime(context: Context) {

    private val appContext = context.applicationContext

    /** Outlives every component that talks to it, so it is never a child of a service or activity. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsStore = SettingsStore(appContext)
    val keyVault = KeyVault(appContext)

    val settings: StateFlow<AirClipSettings> =
        settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, AirClipSettings())

    private val _identity = MutableStateFlow(DeviceIdentity("", "Android"))
    val identity: StateFlow<DeviceIdentity> = _identity.asStateFlow()

    /** `null` until the user pairs; PBKDF2 for passphrases runs off the main thread here. */
    val crypto: StateFlow<CryptoBox?> = keyVault.secret
        .map { secret -> secret?.let { runCatching(it::cryptoBox).getOrNull() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val shizukuGate = ShizukuGate(appContext)
    val shizuku = ShizukuClipboardBackend(appContext, shizukuGate)

    /**
     * How many components currently give this process the window focus (or IME role) that Android
     * 10+ demands before it will hand over the clipboard. A counter rather than a flag: the IME and
     * the relay activity can easily overlap.
     */
    private val readWindows = AtomicInteger(0)

    val writer = AndroidClipboardWriter(appContext, shizuku)
    val reader = AndroidClipboardReader(
        context = appContext,
        options = { settings.value.clipboardOptions() },
        shizuku = shizuku,
        foregroundReadAllowed = { readWindows.get() > 0 },
    )

    val loopGuard = LoopGuard(
        hashTtlMillis = ClipboardOptions.Default.hashTtlMillis,
        remoteWriteSuppressionMillis = ClipboardOptions.Default.remoteWriteSuppressionMillis,
    )

    val engine = ClipboardSyncEngine(reader, writer, loopGuard) { settings.value.clipboardOptions() }

    val history = HistoryStore(appContext) { settings.value.historyLimit }

    val transport = AirClipTransport(
        context = appContext,
        scope = scope,
        identity = { _identity.value },
        listenPort = { settings.value.listenPort },
        serviceType = { settings.value.nsdServiceType },
        cryptoProvider = { crypto.value },
        requireEncryption = { settings.value.requireEncryption },
    )

    val peers = transport.peers
    val status = transport.status
    val isListening = transport.isListening
    val shizukuAvailability = shizukuGate.availability

    private val _isRunning = MutableStateFlow(false)

    /** What the user asked for. [isListening] is whether the socket actually came up. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _lastReceived = MutableStateFlow<ReceivedContent?>(null)

    /** Backs the notification's 一键复制 and the IME's 粘贴最近收到的内容. */
    val lastReceived: StateFlow<ReceivedContent?> = _lastReceived.asStateFlow()

    private val _events = MutableSharedFlow<AirClipEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<AirClipEvent> = _events.asSharedFlow()

    private val lifecycle = Mutex()
    private var pollJob: Job? = null
    private var debounceJob: Job? = null

    init {
        scope.launch {
            settings.collect { current -> _identity.value = DeviceIdentity(current.deviceId, current.deviceName) }
        }
        scope.launch { transport.received.collect(::handleReceived) }

        // A changed port or mDNS type means the listener and the advertisement are both stale.
        scope.launch {
            settings.map { NetworkShape(it.listenPort, it.nsdServiceType, it.deviceId, it.deviceName) }
                .distinctUntilChanged()
                .drop(1)
                .collect { restartTransport() }
        }
        scope.launch {
            settings.map { it.shizukuPolling to it.shizukuPollMillis }
                .distinctUntilChanged()
                .drop(1)
                .collect { restartShizukuPolling() }
        }
        scope.launch {
            settings.map { it.historyLimit }.distinctUntilChanged().drop(1).collect { history.trim() }
        }
    }

    /** Brings up discovery, the listener and the Shizuku binding. Idempotent. */
    suspend fun start() {
        lifecycle.withLock {
            if (_isRunning.value) return@withLock

            val current = settingsStore.ensureIdentity()
            _identity.value = DeviceIdentity(current.deviceId, current.deviceName)
            _isRunning.value = true
            _isPaused.value = false

            history.load()
            shizukuGate.attach()
            shizuku.bind()
            transport.start()
        }
        restartShizukuPolling()
        settingsStore.update { it.copy(serviceEnabled = true) }
    }

    /**
     * [remember] `false` keeps `serviceEnabled` set, so a stop the user did not ask for (the system
     * reclaiming the service) still restarts on boot.
     */
    suspend fun stop(remember: Boolean = true) {
        lifecycle.withLock {
            pollJob?.cancel()
            pollJob = null
            debounceJob?.cancel()
            debounceJob = null

            transport.stop()
            shizuku.unbind()
            shizukuGate.detach()
            _isRunning.value = false
            _isPaused.value = false
        }
        if (remember) settingsStore.update { it.copy(serviceEnabled = false) }
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    fun rescan() = transport.rescan()

    /** Only the app's own teardown path; the runtime otherwise lives as long as the process. */
    fun release() {
        transport.release()
        scope.cancel()
    }

    /**
     * Reads the clipboard and sends it to every connected peer. This is the one send path: the tile,
     * the notification, the IME, the accessibility service and the UI all end up here, so the loop
     * guard and the size limits are applied exactly once.
     */
    suspend fun sendClipboard(source: PublishSource): SendOutcome {
        if (!_isRunning.value) return SendOutcome.ServiceOff
        if (_isPaused.value) return SendOutcome.Paused

        return when (val result = engine.publishCurrent(source)) {
            is PublishResult.Sent -> deliver(result.content)
            PublishResult.Suppressed -> SendOutcome.Suppressed
            is PublishResult.Failed -> {
                _events.emit(AirClipEvent.SendFailed(result.reason))
                SendOutcome.Failed(result.reason)
            }
        }
    }

    /**
     * Coalesces the burst of change callbacks a single copy produces. The listeners (IME,
     * accessibility) call this; explicit user taps call [sendClipboard] directly.
     */
    fun notifyClipboardChanged(source: PublishSource) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(settings.value.debounceMs.toLong())
            sendClipboard(source)
        }
    }

    /** Sends content the user picked out of history, bypassing the echo check they clearly meant to. */
    suspend fun resend(content: ClipContent): SendOutcome {
        if (!_isRunning.value) return SendOutcome.ServiceOff
        if (_isPaused.value) return SendOutcome.Paused
        loopGuard.remember(content.hash)
        return deliver(content)
    }

    /** Writes content back to this device's clipboard without sending it anywhere. */
    suspend fun copyLocally(content: ClipContent): Boolean = engine.writeLocalOnly(content)

    /** The notification's 一键复制: writes received content, guard first, explicit override second. */
    suspend fun applyReceived(content: ClipContent): Boolean =
        engine.applyRemote(content) || engine.writeLocalOnly(content)

    /**
     * Declares that this process now satisfies the platform's focus/IME rule. Paired with
     * [closeReadWindow]; prefer [withReadWindow] where the scope is a block.
     */
    fun openReadWindow() {
        readWindows.incrementAndGet()
    }

    fun closeReadWindow() {
        readWindows.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    /** Lets the accessibility path skip its overlay flicker when a read is already permitted. */
    val hasReadWindow: Boolean get() = readWindows.get() > 0

    suspend fun <T> withReadWindow(block: suspend () -> T): T {
        openReadWindow()
        return try {
            block()
        } finally {
            closeReadWindow()
        }
    }

    private suspend fun deliver(content: ClipContent): SendOutcome {
        val accepted = transport.broadcast(content)
        if (settings.value.keepHistory) history.record(content, fromDeviceName = null)
        _events.emit(AirClipEvent.Sent(accepted, content))
        return SendOutcome.Sent(accepted, content)
    }

    private suspend fun handleReceived(received: ReceivedContent) {
        _lastReceived.value = received
        if (settings.value.keepHistory) history.record(received.content, received.fromDeviceName)

        val applied = if (settings.value.autoApplyRemote && !_isPaused.value) {
            engine.applyRemote(received.content)
        } else {
            false
        }
        _events.emit(AirClipEvent.Received(received, applied))
    }

    private suspend fun restartTransport() {
        lifecycle.withLock {
            if (!_isRunning.value) return@withLock
            transport.stop()
            transport.start()
        }
    }

    private fun restartShizukuPolling() {
        pollJob?.cancel()
        pollJob = null

        val current = settings.value
        if (!current.shizukuPolling || !_isRunning.value) return
        pollJob = scope.launch {
            shizuku.textChanges(current.shizukuPollMillis).collect { text ->
                if (text.isNotEmpty()) sendContent(ClipContent.fromText(text), PublishSource.SHIZUKU)
            }
        }
    }

    private suspend fun sendContent(content: ClipContent, source: PublishSource): SendOutcome {
        if (!_isRunning.value) return SendOutcome.ServiceOff
        if (_isPaused.value) return SendOutcome.Paused

        return when (val result = engine.publish(content, source)) {
            is PublishResult.Sent -> deliver(result.content)
            PublishResult.Suppressed -> SendOutcome.Suppressed
            is PublishResult.Failed -> SendOutcome.Failed(result.reason)
        }
    }

    /** Only the fields whose change forces the listener and the advertisement to be rebuilt. */
    private data class NetworkShape(
        val port: Int,
        val serviceType: String,
        val deviceId: String,
        val deviceName: String,
    )
}
