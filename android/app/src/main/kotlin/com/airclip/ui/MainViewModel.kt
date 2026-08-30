package com.airclip.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airclip.AirClipApp
import com.airclip.R
import com.airclip.core.clipboard.ClipContent
import com.airclip.core.clipboard.ClipKind
import com.airclip.core.crypto.Codecs
import com.airclip.core.crypto.PairingPayload
import com.airclip.core.net.ReceivedContent
import com.airclip.core.net.SyncPeer
import com.airclip.core.protocol.DevicePlatform
import com.airclip.core.sync.PublishSource
import com.airclip.data.AirClipSettings
import com.airclip.data.HistoryEntry
import com.airclip.data.PairingSecret
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

    val fingerprint: StateFlow<String?> = runtime.crypto
        .map { box -> box?.fingerprint }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    private val _capabilities = MutableStateFlow(Capabilities())
    val capabilities: StateFlow<Capabilities> = _capabilities.asStateFlow()

    private val _localAddress = MutableStateFlow<String?>(null)
    val localAddress: StateFlow<String?> = _localAddress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Snackbar text. One channel, so a send started from any screen is reported the same way. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * What the PC scans. Only a 32-byte key can travel in a QR code, so a passphrase pairing has no
     * URI — the pair screen says so rather than showing an unusable code.
     */
    val pairingUri: StateFlow<String?> =
        combine(runtime.keyVault.secret, settings, _localAddress) { secret, current, address ->
            val key = (secret as? PairingSecret.RawKey)?.material ?: return@combine null
            PairingPayload(
                deviceId = current.deviceId,
                deviceName = current.deviceName,
                platform = DevicePlatform.ANDROID,
                host = address,
                port = current.listenPort,
                keyMaterial = key,
            ).toUri()
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

    fun generateKey() = viewModelScope.launch {
        val secret = runtime.keyVault.generate()
        val print = withContext(Dispatchers.Default) { secret.cryptoBox().fingerprint }
        _messages.emit(context.getString(R.string.pair_success, print))
    }

    /**
     * Takes a scanned QR payload, a pasted `airclip://pair` URI, a bare base64 key, or a shared
     * passphrase — in that order, because only the last one cannot be told apart from arbitrary text.
     */
    fun applyPairingText(text: String) = viewModelScope.launch {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _messages.emit(context.getString(R.string.pair_invalid))
            return@launch
        }

        val payload = PairingPayload.parse(trimmed)
        val secret = when {
            payload != null -> runtime.keyVault.saveKeyMaterial(payload.keyMaterial)
            else -> Codecs.decodeKeyMaterial(trimmed)?.let { runtime.keyVault.saveKeyMaterial(it) }
                ?: runtime.keyVault.savePassphrase(trimmed)
        }

        if (secret == null) {
            _messages.emit(context.getString(R.string.pair_invalid))
            return@launch
        }
        val print = withContext(Dispatchers.Default) { secret.cryptoBox().fingerprint }
        _messages.emit(context.getString(R.string.pair_success, print))
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
