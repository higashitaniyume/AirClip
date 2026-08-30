package com.airclip.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.getSystemService
import com.airclip.AirClipApp
import com.airclip.R
import com.airclip.core.sync.PublishSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 方案 A-2: picks up a copy the user made in *another* app, without AirClip having to be the
 * keyboard.
 *
 * Two platform facts shape everything here. An accessibility service is one of the few things
 * Android still lets run indefinitely in the background — but it gets no clipboard privilege at all:
 * reads are allowed only for the default IME or a UID that owns the focused window. And an app that
 * is neither is not even *told* about clipboard changes, because AOSP runs the same access check
 * before dispatching `OnPrimaryClipChangedListener`.
 *
 * So the cue is the accessibility event a 复制 tap produces, and the read is completed by
 * momentarily adding a focusable 1×1 overlay — the flicker the user pays for the convenience, and
 * the only documented way this path can work at all. The clipboard listener is registered anyway:
 * where it does fire (AirClip is also the IME, or the app is in front) it is the faster, flicker-free
 * signal for the same work.
 */
class AirClipAccessibilityService : AccessibilityService() {

    private val runtime by lazy { AirClipApp.runtime(this) }

    /** Windows and views may only be touched from the main thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val clipboard: ClipboardManager? get() = applicationContext.getSystemService()
    private val windows: WindowManager? get() = applicationContext.getSystemService()

    private var overlay: View? = null
    private var relayJob: Job? = null
    private var copyLabels: List<String> = emptyList()

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { trigger() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        copyLabels = resources.getStringArray(R.array.copy_action_labels).map { it.lowercase() }
        runCatching { clipboard?.addPrimaryClipChangedListener(clipListener) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val tap = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        if (tap && looksLikeCopy(event)) trigger()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        scope.cancel()
        super.onDestroy()
    }

    private fun detach() {
        runCatching { clipboard?.removePrimaryClipChangedListener(clipListener) }
        relayJob?.cancel()
        relayJob = null
        removeOverlay()
    }

    /**
     * Coalesces the several cues one copy produces: every new cue cancels the pending relay, so the
     * overlay flashes at most once per copy.
     */
    private fun trigger() {
        if (!runtime.isRunning.value || runtime.isPaused.value) return

        relayJob?.cancel()
        relayJob = scope.launch {
            delay(runtime.settings.value.debounceMs.toLong())
            relay()
        }
    }

    private suspend fun relay() {
        // Already readable: the IME is selected, or a relay activity is up. No flicker owed.
        if (runtime.hasReadWindow) {
            runtime.sendClipboard(PublishSource.ACCESSIBILITY)
            return
        }

        val assist = runtime.settings.value.overlayAssist && Settings.canDrawOverlays(this)
        val view = if (assist) addOverlay() else null
        if (view == null) {
            // Shizuku is the only door left, and the reader tries it before reporting a denial.
            runtime.sendClipboard(PublishSource.ACCESSIBILITY)
            return
        }

        try {
            // Claim the read window only if focus truly arrived, so a denial is still diagnosed as one.
            if (awaitWindowFocus(view)) {
                runtime.withReadWindow { runtime.sendClipboard(PublishSource.ACCESSIBILITY) }
            } else {
                runtime.sendClipboard(PublishSource.ACCESSIBILITY)
            }
        } finally {
            removeOverlay()
        }
    }

    /**
     * A 1×1, untouchable, *focusable* overlay: focus is the entire point, which is why
     * `FLAG_NOT_FOCUSABLE` is conspicuously absent. It takes focus away from the foreground app for a
     * few frames, and that is why it sits behind a setting.
     */
    private fun addOverlay(): View? {
        removeOverlay()
        val manager = windows ?: return null
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val view = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        runCatching { manager.addView(view, params) }.getOrElse { return null }
        overlay = view
        view.requestFocus()
        return view
    }

    private fun removeOverlay() {
        overlay?.let { view -> runCatching { windows?.removeView(view) } }
        overlay = null
    }

    /** `false` once [FOCUS_TIMEOUT_MS] is up: some ROMs never focus a window this small. */
    private suspend fun awaitWindowFocus(view: View): Boolean {
        if (view.hasWindowFocus()) return true

        return withTimeoutOrNull(FOCUS_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val observer = view.viewTreeObserver
                val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (!hasFocus) return
                        runCatching { observer.removeOnWindowFocusChangeListener(this) }
                        if (continuation.isActive) continuation.resume(true)
                    }
                }
                observer.addOnWindowFocusChangeListener(listener)
                continuation.invokeOnCancellation {
                    runCatching { observer.removeOnWindowFocusChangeListener(listener) }
                }
            }
        } ?: false
    }

    /**
     * The floating text toolbar is drawn by the app being copied from, so its 复制 button is an
     * ordinary labelled view. Matching that label is a heuristic — the labels live in a resource array
     * so an unusual ROM wording can be added without touching code — and a false positive costs one
     * suppressed re-read, because the loop guard drops content that already went out.
     */
    private fun looksLikeCopy(event: AccessibilityEvent): Boolean {
        val spoken = event.text.mapNotNull { entry -> entry?.toString() }
        val described = listOfNotNull(event.contentDescription?.toString())
        return (spoken + described).any { candidate ->
            val text = candidate.trim().lowercase()
            text.isNotEmpty() && copyLabels.any { label -> text.contains(label) }
        }
    }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 400L
    }
}
