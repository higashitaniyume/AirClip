package com.airclip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import com.airclip.AirClipApp
import com.airclip.R
import com.airclip.core.clipboard.ClipContent
import com.airclip.runtime.AirClipRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles the notification actions that do *not* need a focused window: writing the clipboard,
 * pausing, and stopping. 发送剪贴板 is an activity intent instead — see `ClipboardRelayActivity`.
 */
class ClipActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val runtime = AirClipApp.runtime(appContext)

        // The work is suspending, so keep the broadcast alive and run it on the app-scoped scope.
        val pending = goAsync()
        runtime.scope.launch {
            try {
                when (intent.action) {
                    ACTION_COPY -> copy(appContext, runtime, intent.getStringExtra(EXTRA_HASH))
                    ACTION_PAUSE -> runtime.setPaused(true)
                    ACTION_RESUME -> runtime.setPaused(false)
                    ACTION_STOP -> SyncForegroundService.requestStop(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun copy(context: Context, runtime: AirClipRuntime, hash: String?) {
        val content = resolve(runtime, hash)
        if (content == null) {
            toast(context, R.string.toast_nothing_received)
            return
        }

        val written = runtime.applyReceived(content)
        toast(context, if (written) R.string.notif_copied else R.string.toast_copy_failed)
        if (written) SyncNotifications.cancel(context, SyncNotifications.ID_RECEIVED)
    }

    /** The hash keeps a stale notification from copying whatever happens to have arrived since. */
    private suspend fun resolve(runtime: AirClipRuntime, hash: String?): ClipContent? {
        val last = runtime.lastReceived.value?.content
        if (hash == null || last?.hash == hash) return last

        val entry = runtime.history.entries.value.firstOrNull { it.hash == hash } ?: return null
        return runtime.history.restore(entry)
    }

    private suspend fun toast(context: Context, @StringRes message: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_COPY = "com.airclip.action.COPY"
        const val ACTION_PAUSE = "com.airclip.action.PAUSE"
        const val ACTION_RESUME = "com.airclip.action.RESUME"
        const val ACTION_STOP = "com.airclip.action.STOP"

        /** Content hash of the item a 一键复制 action was created for. */
        const val EXTRA_HASH = "com.airclip.extra.HASH"
    }
}
