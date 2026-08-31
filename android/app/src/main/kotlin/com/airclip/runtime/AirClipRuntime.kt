package com.airclip.runtime

import android.content.Context
import android.provider.Settings
import com.airclip.core.clipboard.ClipContent
import com.airclip.core.clipboard.ClipboardOptions
import com.airclip.core.clipboard.ClipboardReadFailure
import com.airclip.core.crypto.PairingKey
import com.airclip.core.diag.AirClipLog
import com.airclip.core.diag.LogTag
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
import com.airclip.platform.shizuku.ShizukuAvailability
import com.airclip.platform.shizuku.ShizukuClipboardBackend
import com.airclip.platform.shizuku.ShizukuGate
import com.airclip.ui.ClipboardRelayActivity
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

    /**
     * The group secret, or `null` until the user pairs. Derived once per stored secret and cached here
     * because a passphrase costs 200 000 PBKDF2 rounds; this flow's `map` runs on [scope], which is
     * [Dispatchers.Default], so that cost never lands on the main thread.
     */
    val pairingKey: StateFlow<PairingKey?> = keyVault.secret
        .map { secret -> secret?.let { runCatching(it::pairingKey).getOrNull() } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val shizukuGate = ShizukuGate(appContext)
    val shizuku = ShizukuClipboardBackend(appContext, shizukuGate, scope)

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
        keyProvider = { pairingKey.value },
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

    /** The `logcat` change watcher; independent of the poll loop and of its switch. */
    private var watchJob: Job? = null

    /** When the focus-window fallback last ran, so a busy clipboard cannot flicker without end. */
    @Volatile
    private var lastRelayAt = 0L

    init {
        // Attached for the whole process, not just while the service runs: the settings screen needs a
        // live availability state to explain itself, and granting Shizuku *while* the service is
        // already running has to rebind on its own — that silent gap is one of the reasons a granted
        // Shizuku ends up monitoring nothing.
        shizukuGate.onAvailabilityChanged = ::onShizukuAvailability
        shizukuGate.attach()

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
        // A new pairing key changes the fingerprint we advertise and invalidates every live link, so
        // the listener and the mDNS record both have to be rebuilt — otherwise the peer keeps seeing
        // the old fingerprint and reports a mismatch that no longer exists.
        scope.launch {
            pairingKey.map { it?.fingerprint }.distinctUntilChanged().drop(1).collect { restartTransport() }
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
            if (_isRunning.value) {
                AirClipLog.d(LogTag.RUNTIME, "start() 被忽略：同步服务已经在运行")
                return@withLock
            }

            val current = settingsStore.ensureIdentity()
            _identity.value = DeviceIdentity(current.deviceId, current.deviceName)
            _isRunning.value = true
            _isPaused.value = false

            AirClipLog.section(LogTag.RUNTIME, "同步服务启动")
            AirClipLog.i(
                LogTag.RUNTIME,
                "设备=${current.deviceName} 端口=${current.listenPort} " +
                    "Shizuku轮询=${if (current.shizukuPolling) "开" else "关"}(${current.shizukuPollMillis}ms) " +
                    "已配对=${pairingKey.value != null}",
            )

            history.load()
            shizukuGate.attach()
            shizuku.bind()
            transport.start()
        }
        restartShizukuPolling()
        restartShizukuWatch()
        settingsStore.update { it.copy(serviceEnabled = true) }
    }

    /**
     * [remember] `false` keeps `serviceEnabled` set, so a stop the user did not ask for (the system
     * reclaiming the service) still restarts on boot.
     */
    suspend fun stop(remember: Boolean = true) {
        lifecycle.withLock {
            AirClipLog.section(LogTag.RUNTIME, if (remember) "同步服务停止（用户）" else "同步服务停止（系统回收）")
            pollJob?.cancel()
            pollJob = null
            watchJob?.cancel()
            watchJob = null
            debounceJob?.cancel()
            debounceJob = null

            transport.stop()
            shizuku.unbind()
            // The gate stays attached: it is what notices a later grant. Only release() detaches.
            _isRunning.value = false
            _isPaused.value = false
        }
        if (remember) settingsStore.update { it.copy(serviceEnabled = false) }
    }

    fun setPaused(paused: Boolean) {
        AirClipLog.i(LogTag.RUNTIME, if (paused) "已暂停同步" else "已恢复同步")
        _isPaused.value = paused
    }

    fun rescan() {
        AirClipLog.i(LogTag.RUNTIME, "重新扫描局域网设备")
        transport.rescan()
    }

    /** Only the app's own teardown path; the runtime otherwise lives as long as the process. */
    fun release() {
        AirClipLog.i(LogTag.RUNTIME, "释放运行时")
        shizukuGate.detach()
        shizuku.unbind()
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

        val outcome = when (val result = engine.publishCurrent(source)) {
            is PublishResult.Sent -> deliver(result.content)
            PublishResult.Suppressed -> SendOutcome.Suppressed
            is PublishResult.Failed -> {
                _events.emit(AirClipEvent.SendFailed(result.reason))
                SendOutcome.Failed(result.reason)
            }
        }
        AirClipLog.i(LogTag.RUNTIME, "上报剪贴板（来源 $source）→ ${describeOutcome(outcome)}")
        return outcome
    }

    /**
     * Coalesces the burst of change callbacks a single copy produces. The listeners (IME,
     * accessibility) call this; explicit user taps call [sendClipboard] directly.
     *
     * @param then run with the outcome once the send has happened, for callers that have another card
     *   to play when the read was refused. Cancelled along with the send if another change arrives
     *   first, so it never runs for a superseded copy.
     */
    fun notifyClipboardChanged(source: PublishSource, then: (suspend (SendOutcome) -> Unit)? = null) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(settings.value.debounceMs.toLong())
            val outcome = sendClipboard(source)
            then?.invoke(outcome)
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
        AirClipLog.i(
            LogTag.RUNTIME,
            "收到来自 ${received.fromDeviceName.ifBlank { "未知设备" }} 的内容 ${received.content} " +
                "加密=${received.wasEncrypted} 写入本机=$applied",
        )
        _events.emit(AirClipEvent.Received(received, applied))
    }

    private suspend fun restartTransport() {
        lifecycle.withLock {
            if (!_isRunning.value) return@withLock
            transport.stop()
            transport.start()
        }
    }

    /**
     * (Re)starts the Shizuku poll loop, and — when it does not start one — says why.
     *
     * Both reasons for declining are silent from the UI's point of view, and between them they explain
     * most reports of "Shizuku 已授权但剪贴板没反应": the 轮询监听 switch defaults to off, and nothing
     * polls at all unless the foreground sync service is up.
     */
    private fun restartShizukuPolling() {
        pollJob?.cancel()
        pollJob = null

        val current = settings.value
        if (!current.shizukuPolling) {
            AirClipLog.w(
                LogTag.RUNTIME,
                "不启动 Shizuku 轮询：设置里的「Shizuku 轮询监听」是关闭的。" +
                    "即使 Shizuku 已授权，关掉这个开关就没有任何东西会去读剪贴板",
            )
            return
        }
        if (!_isRunning.value) {
            AirClipLog.w(LogTag.RUNTIME, "不启动 Shizuku 轮询：同步服务未运行，请先在首页打开「同步服务」")
            return
        }

        AirClipLog.i(LogTag.RUNTIME, "启动 Shizuku 轮询任务，间隔 ${current.shizukuPollMillis}ms")
        pollJob = scope.launch {
            shizuku.textChanges(current.shizukuPollMillis).collect { text ->
                if (text.isNotEmpty()) {
                    val outcome = sendContent(ClipContent.fromText(text), PublishSource.SHIZUKU)
                    AirClipLog.i(LogTag.RUNTIME, "轮询内容上报结果 ${describeOutcome(outcome)}")
                }
            }
        }
    }

    /**
     * (Re)starts the log watcher, which is the *trigger* half of the Shizuku plan and is deliberately
     * not tied to the 轮询监听 switch: it does no polling and no reading, it only notices the moment the
     * platform refuses this app the clipboard, which is the moment the clipboard changed.
     *
     * See [com.airclip.platform.shizuku.ShizukuLogcatWatcher]. Every read still goes through the
     * ordinary [reader], so whichever door happens to be open on this device is the one used.
     */
    private fun restartShizukuWatch() {
        watchJob?.cancel()
        watchJob = null

        if (!_isRunning.value) {
            AirClipLog.d(LogTag.RUNTIME, "不启动日志监听：同步服务未运行")
            return
        }
        if (shizukuGate.availability.value != ShizukuAvailability.READY) {
            AirClipLog.d(
                LogTag.RUNTIME,
                "不启动日志监听：Shizuku 状态为 ${shizukuGate.availability.value}，授权后会自动启动",
            )
            return
        }

        AirClipLog.i(
            LogTag.RUNTIME,
            "启动日志监听：借 Shizuku 的 shell 身份读系统日志，" +
                "系统每次拒绝本应用读取剪贴板都是一次「剪贴板变化」通知（与轮询开关无关，两者可以同时工作）",
        )
        watchJob = scope.launch {
            shizuku.logcat.changes().collect { evidence -> onClipboardSignal(evidence) }
        }
    }

    /**
     * One detected clipboard change. Debounced through [notifyClipboardChanged] because one copy can
     * produce several denial lines — the platform logs one per registered listener — and because the
     * IME or accessibility listener may report the same copy from the other side.
     */
    private fun onClipboardSignal(evidence: String) {
        if (!_isRunning.value || _isPaused.value) return
        AirClipLog.d(LogTag.RUNTIME, "日志监听报告剪贴板变化，${settings.value.debounceMs}ms 后读取并上报")
        notifyClipboardChanged(PublishSource.SHIZUKU) { outcome ->
            if (outcome is SendOutcome.Failed && outcome.reason == ClipboardReadFailure.DENIED_BACKGROUND) {
                // Knowing that it changed but not being allowed to see it is exactly the case the focus
                // window exists for; the evidence line goes in the log so a wrong match is visible too.
                AirClipLog.w(LogTag.RUNTIME, "变化已确认但读取被拒，改用前台窗口再试一次 · 依据：${evidence.takeLast(120)}")
                requestFocusedRead()
            }
        }
    }

    /**
     * The last door: a 1×1 window that takes focus for a few frames, which is the one condition under
     * which the platform hands an ordinary app the clipboard.
     *
     * Needs 悬浮窗 permission — not to draw anything, but because that is what exempts the app from the
     * ban on starting an activity from the background. Without it the start is silently dropped by the
     * system, so the missing permission is reported as an event rather than only logged.
     */
    private suspend fun requestFocusedRead() {
        val now = System.currentTimeMillis()
        if (now - lastRelayAt < RELAY_MIN_GAP_MS) {
            AirClipLog.w(LogTag.RUNTIME, "跳过前台读取窗口：${RELAY_MIN_GAP_MS}ms 内刚弹过一次")
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            AirClipLog.e(
                LogTag.RUNTIME,
                "无法弹出前台读取窗口：缺少「显示在其他应用上层」（悬浮窗）权限。" +
                    "两条 Shizuku 读取路径都被系统挡住时，这个权限是最后一条路——请在设置页授予",
            )
            _events.emit(AirClipEvent.Notice("检测到剪贴板变化但系统不允许后台读取，请授予「显示在其他应用上层」权限"))
            return
        }

        lastRelayAt = now
        AirClipLog.i(LogTag.RUNTIME, "弹出 1×1 前台窗口取得焦点后再读一次剪贴板")
        runCatching {
            appContext.startActivity(
                ClipboardRelayActivity.sendIntent(appContext, PublishSource.SHIZUKU, quiet = true),
            )
        }.onFailure { error ->
            AirClipLog.e(LogTag.RUNTIME, "启动前台读取窗口失败：系统拦下了后台启动的界面", error)
        }
    }

    /**
     * Shizuku becoming READY has to be able to start the pipeline by itself: the user grants
     * authorisation in another app's dialog, and before this hook the app would keep polling nothing
     * until the next restart.
     */
    private fun onShizukuAvailability(availability: ShizukuAvailability) {
        if (availability != ShizukuAvailability.READY) {
            if (pollJob != null) {
                AirClipLog.w(LogTag.RUNTIME, "Shizuku 变为 $availability，轮询将持续读不到内容")
            }
            // The watcher's shell is gone with the server, and a dead `logcat` would only be reopened
            // every few seconds to fail again.
            watchJob?.let {
                AirClipLog.w(LogTag.RUNTIME, "Shizuku 变为 $availability，停止日志监听")
                it.cancel()
                watchJob = null
            }
            return
        }
        AirClipLog.i(LogTag.RUNTIME, "Shizuku 已就绪，检查读取路径并重启轮询")
        // A shell process is only spawned when it is actually needed. If the in-process path resolves,
        // reads go through it and there is nothing to bind; if it cannot, the helper is the fallback and
        // binding now means the first read after authorisation already has somewhere to go.
        if (shizuku.direct.isUsable()) {
            AirClipLog.i(LogTag.RUNTIME, "直连可用，不启动辅助进程")
        } else {
            AirClipLog.i(LogTag.RUNTIME, "直连不可用，改为绑定辅助进程作为后备")
            shizuku.bind()
        }
        restartShizukuPolling()
        restartShizukuWatch()
    }

    private fun describeOutcome(outcome: SendOutcome): String = when (outcome) {
        is SendOutcome.Sent -> "已发送给 ${outcome.peers} 个设备"
        SendOutcome.Suppressed -> "被回环保护拦下（判定为刚收到的内容）"
        SendOutcome.ServiceOff -> "同步服务未运行"
        SendOutcome.Paused -> "同步已暂停"
        is SendOutcome.Failed -> "失败：${outcome.reason}"
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

    private companion object {
        /**
         * How long the focus-window fallback must wait between flashes. Long enough that a stream of
         * denials cannot turn into a strobe, short enough that two deliberate copies both get through.
         */
        const val RELAY_MIN_GAP_MS = 1_500L
    }
}
