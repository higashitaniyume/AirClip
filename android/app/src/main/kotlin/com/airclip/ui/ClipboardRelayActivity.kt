package com.airclip.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.airclip.AirClipApp
import com.airclip.core.sync.PublishSource
import com.airclip.runtime.SendFeedback
import com.airclip.runtime.SendOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The focus trick, and the only reason the tile and the notification can send anything.
 *
 * Android 10+ hands over `getPrimaryClip()` only to the default IME or to an app whose window has
 * focus. A `TileService` and a `BroadcastReceiver` have neither, so both start this activity: it is
 * transparent, 0×0 in effect, `noHistory`, and finishes as soon as the read is done — the user sees
 * at most a flicker.
 *
 * A notification action or a tile tap is a user-visible launch, which is what makes starting an
 * activity from the background legal here.
 */
class ClipboardRelayActivity : ComponentActivity() {

    private val runtime by lazy { AirClipApp.runtime(this) }
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No content view at all; the window exists purely to be focused.
        window.setLayout(1, 1)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window.setDimAmount(0f)
    }

    override fun onResume() {
        super.onResume()
        // Fallback: a few OEM launchers never report focus for a 1×1 translucent window.
        lifecycleScope.launch {
            delay(FOCUS_GRACE_MS)
            relay()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) relay()
    }

    private fun relay() {
        if (handled) return
        handled = true

        lifecycleScope.launch {
            val source = intent?.getStringExtra(EXTRA_SOURCE)
                ?.let { name -> runCatching { PublishSource.valueOf(name) }.getOrNull() }
                ?: PublishSource.TILE
            val quiet = intent?.getBooleanExtra(EXTRA_QUIET, false) == true

            val outcome = runtime.withReadWindow { runtime.sendClipboard(source) }
            // A quiet launch was nobody's tap: it happens on every copy, so a success toast each time
            // would be its own annoyance. Anything the user has to act on still gets shown.
            SendFeedback.message(this@ClipboardRelayActivity, outcome)
                ?.takeUnless { quiet && outcome is SendOutcome.Sent }
                ?.let { message ->
                    Toast.makeText(this@ClipboardRelayActivity, message, Toast.LENGTH_SHORT).show()
                }
            finish()
        }
    }

    companion object {
        private const val EXTRA_SOURCE = "com.airclip.extra.SOURCE"
        private const val EXTRA_QUIET = "com.airclip.extra.QUIET"
        private const val FOCUS_GRACE_MS = 450L

        /**
         * Send the clipboard as soon as this activity can legally read it.
         *
         * @param quiet suppress the "sent" toast, for automatic triggers rather than user taps.
         */
        fun sendIntent(context: Context, source: PublishSource, quiet: Boolean = false): Intent =
            Intent(context, ClipboardRelayActivity::class.java)
                .putExtra(EXTRA_SOURCE, source.name)
                .putExtra(EXTRA_QUIET, quiet)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
    }
}
