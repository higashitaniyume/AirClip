package com.airclip.service

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.getSystemService
import com.airclip.AirClipApp
import com.airclip.R
import com.airclip.core.clipboard.ClipKind
import com.airclip.core.sync.PublishSource
import com.airclip.runtime.SendFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 方案 A-1, and the sturdiest of the four read paths.
 *
 * The platform hands `getPrimaryClip()` to the *selected* IME unconditionally — no window focus
 * required — and the system only binds the IME it has selected. So the mere existence of this
 * service means the whole process may read the clipboard, which is why it opens a read window for
 * its entire lifetime rather than only while its input view is on screen. That is also what turns
 * the clipboard listener registered here into a genuine background monitor.
 *
 * The input view is deliberately not a keyboard: three relay buttons and a status line. Anyone who
 * needs to actually type switches back with the third one.
 */
class AirClipInputMethodService : InputMethodService() {

    private val runtime by lazy { AirClipApp.runtime(this) }

    /** Views are touched from here, so the scope is the main thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val clipboard: ClipboardManager? get() = applicationContext.getSystemService()
    private var statusView: TextView? = null
    private var statusJob: Job? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Debounced and loop-guarded downstream; a single copy fires this more than once.
        runtime.notifyClipboardChanged(PublishSource.IME)
    }

    override fun onCreate() {
        super.onCreate()
        runtime.openReadWindow()
        runCatching { clipboard?.addPrimaryClipChangedListener(clipListener) }
    }

    override fun onDestroy() {
        runCatching { clipboard?.removePrimaryClipChangedListener(clipListener) }
        runtime.closeReadWindow()
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.view_ime_strip, null)
        statusView = view.findViewById(R.id.ime_status)

        view.findViewById<Button>(R.id.ime_send).setOnClickListener { send() }
        view.findViewById<Button>(R.id.ime_paste).setOnClickListener { pasteLast() }
        view.findViewById<Button>(R.id.ime_switch).setOnClickListener { switchBack() }
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        statusJob?.cancel()
        statusJob = scope.launch {
            combine(runtime.isRunning, runtime.isPaused, runtime.peers) { running, paused, peers ->
                statusText(running, paused, peers.count { it.isConnected })
            }.collect { text -> statusView?.text = text }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        statusJob?.cancel()
        statusJob = null
        super.onFinishInputView(finishingInput)
    }

    private fun send() {
        scope.launch {
            val outcome = runtime.sendClipboard(PublishSource.IME)
            SendFeedback.message(this@AirClipInputMethodService, outcome)?.let(::toast)
        }
    }

    /**
     * Writes the last thing a peer sent straight into the focused field, which is the whole reason a
     * clipboard relay keyboard is worth having. Images (and a missing editor) fall back to the
     * clipboard.
     */
    private fun pasteLast() {
        scope.launch {
            val content = runtime.lastReceived.value?.content
            if (content == null) {
                toast(getString(R.string.toast_nothing_received))
                return@launch
            }

            if (content.kind == ClipKind.TEXT) {
                val text = content.text.orEmpty()
                if (text.isNotEmpty() && currentInputConnection?.commitText(text, 1) == true) {
                    return@launch
                }
            }

            val written = runtime.applyReceived(content)
            toast(getString(if (written) R.string.notif_copied else R.string.toast_copy_failed))
        }
    }

    /** `false` means there is nothing to go back to, so let the user choose. */
    private fun switchBack() {
        val switched = runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
        if (!switched) {
            runCatching { applicationContext.getSystemService<InputMethodManager>()?.showInputMethodPicker() }
        }
    }

    /** The hint explains why this keyboard exists; once syncing, the live state is more useful. */
    private fun statusText(running: Boolean, paused: Boolean, connected: Int): String = when {
        !running -> getString(R.string.ime_hint)
        paused -> getString(R.string.home_state_paused)
        connected > 0 -> getString(R.string.home_state_connected, connected)
        else -> getString(R.string.home_state_offline)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
