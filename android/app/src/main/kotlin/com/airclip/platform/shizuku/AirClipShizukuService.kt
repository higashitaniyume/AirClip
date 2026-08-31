package com.airclip.platform.shizuku

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.airclip.core.diag.formatLogTime

/**
 * Runs inside the Shizuku-spawned process (shell UID, or root when Shizuku itself runs as root),
 * which is why it may talk to the hidden `IClipboard` service at all: `ClipboardService` exempts
 * `com.android.shell` — which holds `READ_CLIPBOARD_IN_BACKGROUND` — from the "must be the default
 * IME or have window focus" rule that blocks ordinary apps on Android 10+. This process also has no
 * hidden-API policy installed, so the reflection in [ClipboardReflect] needs no exemption here.
 *
 * The clipboard work itself lives in [ClipboardReflect], shared with [ShizukuDirectClipboard]; what
 * remains here is the part that only makes sense across a process boundary.
 *
 * This process cannot reach `AirClipLog` — it is a different process with its own heap — so it keeps
 * its own [journal] that the app drains over the binder. Every refusal the platform hands back is
 * recorded there, because "Shizuku is authorised but nothing syncs" is almost always answered by the
 * exact text of one `SecurityException`.
 */
class AirClipShizukuService() : IShizukuClipboard.Stub() {

    /** Shizuku instantiates whichever of the two constructors it finds; keep both. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private val lock = Any()
    private val journal = ArrayDeque<String>()

    private val reflect = ClipboardReflect(
        label = "辅助进程",
        // Here the process's own uid *is* the calling uid: the transaction leaves from this process.
        callingUid = Process::myUid,
        binder = ::rawClipboardBinder,
        note = ::note,
    )

    init {
        note(
            "helper started api=${Build.VERSION.SDK_INT} uid=${Process.myUid()} pid=${Process.myPid()} " +
                "user=${userId()} device=${Build.MANUFACTURER}/${Build.MODEL} build=${Build.DISPLAY}",
        )
    }

    /**
     * `null` means "no answer": the service was never resolved, the method is missing, or every
     * calling-package candidate was refused. An empty string means the platform answered with an
     * empty clip. The [journal] carries which of those it was.
     */
    override fun getPrimaryClipText(): String? = reflect.text()

    override fun setPrimaryClipText(text: String?): Boolean = reflect.setText(text)

    /** Everything the settings screen and the self-test need to judge this half of the plan. */
    override fun describeBackend(): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(" uid=").append(Process.myUid()).append(uidLabel())
        append(" user=").append(userId())
        append(" device=").append(Build.MANUFACTURER).append('/').append(Build.MODEL)
        append("\n  ").append(reflect.describe())
    }

    override fun drainLog(): String = synchronized(lock) {
        val text = journal.joinToString("\n")
        journal.clear()
        text
    }

    override fun destroy() {
        // Nothing to release: the binder proxy is owned by the system, and Shizuku kills the process.
        note("helper 收到 destroy")
    }

    /**
     * The unwrapped system binder. `ServiceManager` is not public API, but nothing in this process
     * enforces that, and no Shizuku round trip is needed either — the transaction already leaves from
     * a shell-uid process.
     */
    private fun rawClipboardBinder(): IBinder {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, CLIPBOARD_SERVICE_NAME) as? IBinder
        return binder ?: error("ServiceManager 返回 null —— 这台 ROM 没有标准剪贴板服务")
    }

    private fun uidLabel(): String = when (Process.myUid()) {
        UID_ROOT -> "(root)"
        UID_SHELL -> "(shell)"
        else -> ""
    }

    private fun note(message: String) {
        Log.i(LOGCAT_TAG, message)
        synchronized(lock) {
            journal.addLast("${formatLogTime(System.currentTimeMillis())} $message")
            while (journal.size > JOURNAL_CAPACITY) journal.removeFirst()
        }
    }

    private companion object {
        const val LOGCAT_TAG = "AirClipSvc"

        /** The uid this process runs as; `android.os.Process`' own constants are not public API. */
        const val UID_ROOT = 0
        const val UID_SHELL = 2000

        /** Bounded: nobody drains this while the app is closed. */
        const val JOURNAL_CAPACITY = 200
    }
}
