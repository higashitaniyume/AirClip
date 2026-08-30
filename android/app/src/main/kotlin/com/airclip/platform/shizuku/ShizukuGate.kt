package com.airclip.platform.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuAvailability {
    /** The Shizuku manager app is not installed at all. */
    NOT_INSTALLED,

    /** Installed, but its service was never started (or died after a reboot). */
    NOT_RUNNING,

    /** Service reachable; the user has not granted AirClip access yet. */
    PERMISSION_REQUIRED,

    READY,
}

/**
 * Tracks whether the Shizuku service is reachable and authorised. Kept separate from
 * [ShizukuClipboardBackend] so the settings screen can explain *which* step is missing without
 * having a bound user service.
 */
class ShizukuGate(private val context: Context) {

    private val _availability = MutableStateFlow(ShizukuAvailability.NOT_INSTALLED)
    val availability: StateFlow<ShizukuAvailability> = _availability.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun attach() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun detach() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    fun refresh() {
        _availability.value = when {
            !Shizuku.pingBinder() -> if (isManagerInstalled()) {
                ShizukuAvailability.NOT_RUNNING
            } else {
                ShizukuAvailability.NOT_INSTALLED
            }

            isGranted() -> ShizukuAvailability.READY
            else -> ShizukuAvailability.PERMISSION_REQUIRED
        }
    }

    fun isGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Shows Shizuku's own consent dialog. Pre-v11 managers instead rely on the
     * `moe.shizuku.manager.permission.API_V23` runtime permission, which the caller must request
     * from an Activity — [needsRuntimePermission] reports that case.
     */
    fun requestPermission(requestCode: Int = REQUEST_CODE) {
        if (!Shizuku.pingBinder()) {
            refresh()
            return
        }
        if (Shizuku.isPreV11()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(requestCode)
        }
        refresh()
    }

    fun needsRuntimePermission(): Boolean = Shizuku.pingBinder() && Shizuku.isPreV11()

    private fun isManagerInstalled(): Boolean = MANAGER_PACKAGES.any { name ->
        runCatching { context.packageManager.getPackageInfo(name, 0) }.isSuccess
    }

    companion object {
        const val REQUEST_CODE = 0xA17C
        const val RUNTIME_PERMISSION = "moe.shizuku.manager.permission.API_V23"

        /** Shizuku's own package, plus Sui, which serves the same binder. */
        private val MANAGER_PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.redirect")
    }
}
