package com.airclip.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.airclip.AirClipApp
import com.airclip.R
import com.airclip.runtime.AirClipEvent
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps discovery and the WebSocket listener alive, and owns the persistent notification the spec
 * asks for — including its 一键复制 action, which is what makes the receive path work even when the
 * user turned automatic writing off.
 *
 * The sync state itself lives in `AirClipRuntime`, not here: the IME, the tile and the UI must keep
 * working across a service restart, and a service that owns its own engine cannot promise that.
 */
class SyncForegroundService : LifecycleService() {

    private val runtime by lazy { AirClipApp.runtime(this) }
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        SyncNotifications.ensureChannels(this)
        // Must happen within seconds of the start request, before the runtime is even up.
        promote(getString(R.string.home_state_offline), paused = false, copyHash = null)
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        acquireWifiLock()
        lifecycleScope.launch { runtime.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWifiLock()
        if (runtime.isRunning.value) {
            // Not a user decision: the system reclaimed us. Keep serviceEnabled so boot restores it.
            runtime.scope.launch { runtime.stop(remember = false) }
        }
        super.onDestroy()
    }

    private fun observe() {
        lifecycleScope.launch {
            combine(
                runtime.isPaused,
                runtime.peers,
                runtime.status,
                runtime.lastReceived,
            ) { paused, peers, status, last ->
                OngoingState(paused, peers.count { it.isConnected }, status, last?.content?.hash)
            }
                .distinctUntilChanged()
                .collect { state -> promote(summary(state), state.paused, state.copyHash) }
        }

        lifecycleScope.launch {
            runtime.events.collect { event -> if (event is AirClipEvent.Received) announce(event) }
        }
    }

    private fun announce(event: AirClipEvent.Received) {
        val settings = runtime.settings.value
        if (settings.notifyOnReceive) {
            SyncNotifications.show(
                this,
                SyncNotifications.ID_RECEIVED,
                SyncNotifications.received(this, event.received, event.applied),
            )
        }
        // Only worth a toast when the clipboard actually changed; otherwise the action is the message.
        if (settings.toastOnReceive && event.applied) {
            Toast.makeText(this, R.string.notif_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun promote(text: String, paused: Boolean, copyHash: String?) {
        val notification = SyncNotifications.ongoing(this, text, paused, copyHash)
        runCatching {
            ServiceCompat.startForeground(
                this,
                SyncNotifications.ID_SYNC,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
    }

    private fun summary(state: OngoingState): String {
        val primary = when {
            state.paused -> getString(R.string.home_state_paused)
            state.connected > 0 -> getString(R.string.home_state_connected, state.connected)
            else -> getString(R.string.home_state_offline)
        }
        return if (state.status.isBlank() || state.status == primary) {
            primary
        } else {
            "$primary · ${state.status}"
        }
    }

    private fun shutdown() {
        lifecycleScope.launch {
            runtime.stop(remember = true)
            releaseWifiLock()
            ServiceCompat.stopForeground(this@SyncForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            SyncNotifications.cancel(this@SyncForegroundService, SyncNotifications.ID_SYNC)
            stopSelf()
        }
    }

    /**
     * Wi-Fi sleeps aggressively with the screen off, which drops both the mDNS advertisement and the
     * socket. The low-latency mode is only honoured while the app is visible, so this is a cheap lock.
     */
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wifi = applicationContext.getSystemService<WifiManager>() ?: return
        wifiLock = runCatching {
            wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "airclip-sync").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseWifiLock() {
        wifiLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLock = null
    }

    private data class OngoingState(
        val paused: Boolean,
        val connected: Int,
        val status: String,
        val copyHash: String?,
    )

    companion object {
        private const val ACTION_STOP = "com.airclip.service.STOP"

        /** Android 12+ throws when an app starts a foreground service from the background. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, SyncForegroundService::class.java))
            }
        }

        fun requestStop(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
