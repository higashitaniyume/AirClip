package com.airclip.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airclip.AirClipApp
import com.airclip.BuildConfig
import com.airclip.R
import com.airclip.core.clipboard.ClipContent
import com.airclip.core.clipboard.ClipKind
import com.airclip.core.crypto.PairingInvite
import com.airclip.core.diag.AirClipLog
import com.airclip.core.diag.LogEntry
import com.airclip.core.net.ReceivedContent
import com.airclip.core.net.SyncPeer
import com.airclip.core.sync.PublishSource
import com.airclip.data.AirClipSettings
import com.airclip.data.HistoryEntry
import com.airclip.platform.shizuku.ShizukuDoctor
import com.airclip.runtime.AirClipEvent
import com.airclip.runtime.SendFeedback
import com.airclip.runtime.SendOutcome
import com.airclip.service.SyncForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The single view model behind all five screens. It owns no state of its own beyond what the UI
 * needs to *ask* the system (capabilities, this device's LAN address): everything else is a view onto
 * `AirClipRuntime`, so the UI, the notification and the tile can never disagree.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime = AirClipApp.runtime(application)
    private val context: Application get() = getApplication()

    val settings: StateFlow<AirClipSettings> = runtime.settings
    val isRunning: StateFlow<Boolean> = runtime.isRunning
    val isPaused: StateFlow<Boolean> = runtime.isPaused
    val peers: StateFlow<List<SyncPeer>> = runtime.peers
    val status: StateFlow<String> = runtime.status
    val history: StateFlow<List<HistoryEntry>> = runtime.history.entries
    val lastReceived: StateFlow<ReceivedContent?> = runtime.lastReceived
    val shizuku = runtime.shizukuAvailability
    val lastReadSource: StateFlow<PublishSource?> = runtime.engine.lastReadSource
    val usesPlaintextVault: StateFlow<Boolean> = runtime.keyVault.usesPlaintextFallback

    /** The 诊断日志 screen. A view onto the process-wide buffer, not a copy of it. */
    val logEntries: StateFlow<List<LogEntry>> = AirClipLog.entries
    val logIncludeContent: StateFlow<Boolean> = AirClipLog.includeContentState
    val logKeepTrace: StateFlow<Boolean> = AirClipLog.keepTraceState

    /** What the Shizuku helper process last said about itself; empty until it has been bound. */
    val shizukuDiagnostics: StateFlow<String> = runtime.shizuku.diagnostics
    val shizukuConnected: StateFlow<Boolean> = runtime.shizuku.connected

    private val _doctorResult = MutableStateFlow<String?>(null)

    /**
     * The self-test's conclusion, kept on screen rather than only in a snackbar: it is a paragraph
     * naming the one step that failed, and a snackbar shows two lines.
     */
    val doctorResult: StateFlow<String?> = _doctorResult.asStateFlow()

    private val _doctorRunning = MutableStateFlow(false)
    val doctorRunning: StateFlow<Boolean> = _doctorRunning.asStateFlow()

    /** Eight hex characters; the PC shows the same eight when both devices hold the same secret. */
    val fingerprint: StateFlow<String?> = runtime.pairingKey
        .map { key -> key?.fingerprint }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    /**
     * The pairing code itself, `A1B2-C3D4-…`, for the user to read off this screen and type into the
     * PC. It is the fallback for every case where a QR scan is not possible, which on a desktop is most
     * of them.
     */
    val pairingCode: StateFlow<String?> = runtime.pairingKey
        .map { key -> key?.code }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    private val _capabilities = MutableStateFlow(Capabilities())
    val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()

    private val _localAddress = MutableStateFlow<String?>(null)
    val localAddress: StateFlow<String?> = _localAddress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Snackbar text. One channel, so a send started from any screen is reported the same way. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * What the PC scans. Unlike the previous scheme every pairing style has one, because a phrase
     * stretches to the same twenty bytes a generated code carries — the invite holds the code, so the
     * two devices end up with one secret however it was created.
     */
    val pairingUri: StateFlow<String?> =
        combine(runtime.pairingKey, settings) { key, current ->
            key?.createInvite(
                deviceName = current.deviceName,
                serviceName = fullServiceName(current.nsdServiceType),
                port = current.listenPort,
            )?.toUri()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    init {
        refresh()
        viewModelScope.launch { runtime.events.collect(::announce) }
    }

    /** Called on every resume: all of these can change while the user is in system settings. */
    fun refresh() {
        _capabilities.value = SystemAccess.probe(context)
        runtime.shizukuGate.refresh()
        viewModelScope.launch { _localAddress.value = withContext(Dispatchers.IO) { localIpv4() } }
    }

    fun setServiceEnabled(enabled: Boolean) {
        if (enabled) SyncForegroundService.start(context) else SyncForegroundService.requestStop(context)
    }

    fun togglePause() = runtime.setPaused(!isPaused.value)

    /**
     * The activity is focused while this runs, which is the platform's condition for a read — hence
     * the explicit read window rather than relying on whatever else happens to be open.
     */
    fun sendNow() = viewModelScope.launch {
        val outcome = runtime.withReadWindow { runtime.sendClipboard(PublishSource.MANUAL) }
        // A send that happened (or failed) arrives as an event; these two never got that far.
        if (outcome is SendOutcome.ServiceOff || outcome is SendOutcome.Paused) {
            SendFeedback.message(context, outcome)?.let { _messages.emit(it) }
        }
    }

    fun rescan() = runtime.rescan()

    fun connect(peer: SyncPeer) = viewModelScope.launch { runtime.transport.connect(peer) }

    fun disconnect(peer: SyncPeer) = viewModelScope.launch { runtime.transport.disconnect(peer.deviceId) }

    fun update(transform: (AirClipSettings) -> AirClipSettings) {
        viewModelScope.launch { runtime.settingsStore.update(transform) }
    }

    fun copyEntry(entry: HistoryEntry) = viewModelScope.launch {
        val content = runtime.history.restore(entry)
        if (content == null) {
            _messages.emit(context.getString(R.string.history_unavailable))
            return@launch
        }
        val written = runtime.copyLocally(content)
        _messages.emit(context.getString(if (written) R.string.notif_copied else R.string.toast_copy_failed))
    }

    fun resendEntry(entry: HistoryEntry) = viewModelScope.launch {
        val content = runtime.history.restore(entry)
        if (content == null) {
            _messages.emit(context.getString(R.string.history_unavailable))
            return@launch
        }
        val outcome = runtime.resend(content)
        if (outcome is SendOutcome.ServiceOff || outcome is SendOutcome.Paused) {
            SendFeedback.message(context, outcome)?.let { _messages.emit(it) }
        }
    }

    fun deleteEntry(entry: HistoryEntry) = viewModelScope.launch { runtime.history.remove(entry.id) }

    fun clearHistory() = viewModelScope.launch { runtime.history.clear() }

    /** Makes this device the origin of a new group: every other device has to be re-paired to it. */
    fun generateKey() = viewModelScope.launch {
        val secret = runtime.keyVault.generate()
        val print = withContext(Dispatchers.Default) { secret.pairingKey()?.fingerprint }
        _messages.emit(
            print?.let { context.getString(R.string.pair_success, it) }
                ?: context.getString(R.string.pair_invalid),
        )
    }

    /**
     * Takes a scanned `airclip://pair` invite, a typed pairing code (grouped or not, any case), or a
     * shared phrase written as `pass:我们的口令`. Anything else is reported as invalid rather than being
     * treated as a phrase: guessing turns one typo into a device that pairs with nothing and says
     * nothing about it.
     */
    fun applyPairingText(text: String) = viewModelScope.launch {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _messages.emit(context.getString(R.string.pair_invalid))
            return@launch
        }

        // Runs PBKDF2 for a phrase, so it stays on the IO dispatcher inside the vault.
        val secret = runtime.keyVault.saveText(trimmed)
        if (secret == null) {
            _messages.emit(context.getString(R.string.pair_invalid))
            return@launch
        }

        val print = withContext(Dispatchers.Default) { secret.pairingKey()?.fingerprint }
        _messages.emit(
            print?.let { context.getString(R.string.pair_success, it) }
                ?: context.getString(R.string.pair_invalid),
        )
    }

    fun forgetKey() = viewModelScope.launch { runtime.keyVault.clear() }

    /**
     * Puts the pairing URI on the clipboard for the user to send to the PC by whatever means. This
     * goes through the local-only write, so the key does not get broadcast to peers as a clip.
     */
    fun copyPairingLink(link: String) = viewModelScope.launch {
        val written = runtime.copyLocally(ClipContent.fromText(link))
        _messages.emit(context.getString(if (written) R.string.pair_copied else R.string.toast_copy_failed))
    }

    /** The same local-only write as [copyPairingLink]; only the confirmation differs. */
    fun copyPairingCode(code: String) = viewModelScope.launch {
        val written = runtime.copyLocally(ClipContent.fromText(code))
        _messages.emit(
            context.getString(if (written) R.string.pair_code_copied else R.string.toast_copy_failed),
        )
    }

    /** Pre-v11 Shizuku has no consent dialog; the caller has to request a runtime permission instead. */
    fun shizukuNeedsRuntimePermission(): Boolean = runtime.shizukuGate.needsRuntimePermission()

    fun requestShizuku() {
        runtime.shizukuGate.requestPermission()
        if (runtime.shizukuGate.needsRuntimePermission()) {
            _messages.tryEmit(context.getString(R.string.settings_shizuku_pre_v11))
        }
    }

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    /**
     * Runs the Shizuku self-test. Guarded against re-entry because it binds the helper process and
     * waits up to eight seconds for it — two overlapping runs would interleave in the log and make it
     * unreadable, which defeats the purpose.
     */
    fun runShizukuDoctor() {
        if (_doctorRunning.value) return
        _doctorRunning.value = true
        viewModelScope.launch {
            val conclusion = runCatching {
                ShizukuDoctor.run(
                    gate = runtime.shizukuGate,
                    backend = runtime.shizuku,
                    serviceRunning = isRunning.value,
                    pollingEnabled = settings.value.shizukuPolling,
                    pollMillis = settings.value.shizukuPollMillis,
                    // Probed fresh rather than read from [capabilities]: the user may well have just
                    // granted it because a previous run asked them to.
                    overlayGranted = Settings.canDrawOverlays(context),
                )
            }.getOrElse { error -> context.getString(R.string.log_doctor_crashed, AirClipLog.describe(error)) }
            _doctorResult.value = conclusion
            _doctorRunning.value = false
        }
    }

    fun setLogIncludeContent(on: Boolean) {
        AirClipLog.includeContent = on
    }

    fun setLogKeepTrace(on: Boolean) {
        AirClipLog.keepTrace = on
    }

    fun clearLog() {
        _doctorResult.value = null
        AirClipLog.clear()
    }

    /** Local-only write, so a copied log never goes out to the PC as a clip. */
    fun copyLog() = viewModelScope.launch {
        val written = runtime.copyLocally(ClipContent.fromText(AirClipLog.dump(header())))
        _messages.emit(context.getString(if (written) R.string.log_copied else R.string.toast_copy_failed))
    }

    /**
     * Shares the log as a file rather than as `EXTRA_TEXT`: a full buffer is well over a hundred
     * kilobytes, and messaging apps silently truncate a string that size.
     */
    fun shareLog(host: Context) = viewModelScope.launch {
        val uri = withContext(Dispatchers.IO) { writeLogFile(AirClipLog.dump(header())) }
        if (uri == null) {
            _messages.emit(context.getString(R.string.log_share_failed))
            return@launch
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.log_share))
        runCatching { host.startActivity(chooser) }
            .onFailure { _messages.emit(context.getString(R.string.log_share_failed)) }
    }

    /**
     * The first line of any export. Every one of these is something the reader of a bug report would
     * otherwise have to ask for, and two of them (the polling switch, the service state) are the usual
     * answer to "Shizuku 已授权但没反应".
     */
    private fun header(): String {
        val current = settings.value
        return "AirClip ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.SDK_INT} · " +
            "${Build.MANUFACTURER}/${Build.MODEL} · Shizuku=${shizuku.value} " +
            "直连=${if (runtime.shizuku.direct.isUsable()) "可用" else "不可用"} " +
            "辅助进程=${if (shizukuConnected.value) "已连接" else "未连接"} " +
            "日志监听[${runtime.shizuku.logcat.describe()}] " +
            "悬浮窗=${if (Settings.canDrawOverlays(context)) "已授予" else "未授予"} " +
            "轮询=${if (current.shizukuPolling) "开" else "关"}(${current.shizukuPollMillis}ms) " +
            "同步服务=${if (isRunning.value) "运行中" else "已停止"}"
    }

    /** One file, overwritten each time: these exist to leave the app, not to accumulate in the cache. */
    private fun writeLogFile(dump: String): Uri? = runCatching {
        val dir = File(context.cacheDir, "logs")
        dir.mkdirs()
        val file = File(dir, "airclip-log.txt")
        file.writeText(dump)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }.getOrNull()

    private suspend fun announce(event: AirClipEvent) {
        val text = when (event) {
            is AirClipEvent.Sent -> SendFeedback.message(context, SendOutcome.Sent(event.peers, event.content))
            is AirClipEvent.SendFailed -> SendFeedback.message(context, SendOutcome.Failed(event.reason))
            is AirClipEvent.Received -> describe(event.received, event.applied)
            is AirClipEvent.Notice -> event.message
        }
        text?.let { _messages.emit(it) }
    }

    private fun describe(received: ReceivedContent, applied: Boolean): String {
        if (applied) return context.getString(R.string.notif_copied)
        val content = received.content
        return when (content.kind) {
            ClipKind.TEXT -> context.getString(
                R.string.notif_received_text,
                content.text.orEmpty().replace('\n', ' ').trim().take(40),
            )

            ClipKind.IMAGE -> context.getString(
                R.string.notif_received_image,
                content.image?.width ?: 0,
                content.image?.height ?: 0,
            )
        }
    }

    /**
     * `NsdManager` wants `_airclip._tcp.`; the invite carries the full mDNS name the PC resolves. Kept
     * in step with whatever service type this device is actually advertising, so a customised type does
     * not send the other end looking in the wrong place.
     */
    private fun fullServiceName(serviceType: String): String {
        val trimmed = serviceType.trim().trim('.')
        return if (trimmed.isEmpty()) PairingInvite.DEFAULT_SERVICE else "$trimmed.local."
    }

    /**
     * The address the PC has to reach, which is the first thing to check when discovery finds
     * nothing. Link-local (169.254.x) addresses are skipped: they mean DHCP never completed.
     */
    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private companion object {
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
