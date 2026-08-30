package com.airclip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.airclip.AirClipApp
import kotlinx.coroutines.launch

/**
 * Brings the sync service back after a reboot or an app update — but only when the user asked for
 * 开机自启 *and* the service was running when the process last went away, so an explicit 停止 is
 * never undone by a reboot.
 *
 * Android 15 no longer lets a `dataSync` foreground service start from `BOOT_COMPLETED`, so the
 * start is best-effort: on those builds the user has to open AirClip once. Everything here is
 * therefore wrapped rather than allowed to crash the boot broadcast.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return

        val appContext = context.applicationContext
        val runtime = AirClipApp.runtime(appContext)

        // Reading settings is suspending, and a receiver that returns first would be killed.
        val pending = goAsync()
        runtime.scope.launch {
            try {
                val settings = runtime.settingsStore.current()
                if (settings.startOnBoot && settings.serviceEnabled) {
                    SyncForegroundService.start(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
