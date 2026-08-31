package com.airclip.platform.shizuku

import com.airclip.core.diag.AirClipLog
import com.airclip.core.diag.LogTag
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Reads and writes the clipboard from **this** process, with the Shizuku server's identity.
 *
 * [ShizukuBinderWrapper] rewrites every transaction into a `Shizuku.transactRemote` call, so the
 * binder that reaches `system_server` comes from the Shizuku process — uid 2000 (shell) or 0 (root).
 * `ClipboardService` therefore applies the `com.android.shell` exemption instead of the "must be the
 * default IME or have window focus" rule, which is the same privilege the helper process buys, minus
 * the process.
 *
 * That is the point of this class. The user service is one moving part too many: Shizuku has to spawn
 * `app_process`, that process has to load this APK, instantiate [AirClipShizukuService] and call back
 * — and when any of it fails, Shizuku 13 tells the client nothing at all. No callback, no exception,
 * no `onNullBinding`. The device this was written for sat in exactly that state: authorised, polling,
 * and permanently unbound. This path removes every one of those steps; what remains is a plain binder
 * call that either returns a `ClipData` or throws something we can print.
 *
 * The read is deliberately attempted before the user service in [ShizukuClipboardBackend], with the
 * helper kept as the fallback for ROMs where the hidden-API lookup is blocked in an app process.
 */
class ShizukuDirectClipboard(private val gate: ShizukuGate) {

    private val reflect = ClipboardReflect(
        label = "直连",
        // Not this process's uid: the transaction is executed by the Shizuku server, and the calling
        // package has to belong to *that* uid or AppOpsManager.checkPackage throws first.
        callingUid = { Shizuku.getUid() },
        binder = ::wrappedBinder,
        note = ::note,
    )

    /**
     * Last line written, so the poll loop does not bury the log.
     *
     * A successful read at 1 Hz produces the same sentence every second — same package, same length —
     * and one of those per tick would push everything else out of a 1200-line buffer within the hour.
     * The first occurrence is news and goes in at INFO; identical repeats are 详细轮询 material.
     */
    @Volatile
    private var lastNote: String? = null

    /** `null` when the path is unusable; an empty string means "clipboard is genuinely empty". */
    fun text(): String? {
        if (!ready("读取")) return null
        return reflect.text()
    }

    fun setText(text: String): Boolean {
        if (!ready("写入")) return false
        return reflect.setText(text)
    }

    /** Cheap probe used by the self-test: does the reflection resolve in this process at all? */
    fun isUsable(): Boolean = ready("探测") && reflect.isUsable()

    /** One block for the settings screen and the self-test; safe to call when Shizuku is down. */
    fun describe(): String = buildString {
        append("隐藏 API=").append(HiddenApiExemption.ensure())
        append("\n  Shizuku 身份uid=").append(runCatching { Shizuku.getUid() }.getOrNull() ?: "查询失败")
        if (!gate.isGranted()) {
            append("\n  未授权，直连不可用")
            return@buildString
        }
        append("\n  ").append(reflect.describe())
    }

    /** Why the path is dead, when it is — the wrapped binder or the reflection said so. */
    fun failure(): String? = reflect.failure

    /**
     * The Shizuku server restarting hands out a brand-new binder, and every proxy minted from the old
     * one is dead. The gate notices that; this is how the news reaches the cached proxy.
     */
    fun invalidate() = reflect.invalidate()

    private fun ready(action: String): Boolean {
        if (gate.isGranted()) return true
        AirClipLog.t(LogTag.SHIZUKU_DIRECT, "跳过直连$action：Shizuku 尚未授权（${gate.availability.value}）")
        return false
    }

    private fun note(message: String) {
        val repeated = message == lastNote
        lastNote = message
        if (repeated) {
            AirClipLog.t(LogTag.SHIZUKU_DIRECT, message)
        } else {
            AirClipLog.i(LogTag.SHIZUKU_DIRECT, message)
        }
    }

    /**
     * Throws rather than returning null when Shizuku is missing, so the reason ends up in
     * [ClipboardReflect.failure] instead of being flattened into "no clipboard service".
     */
    private fun wrappedBinder(): android.os.IBinder {
        // Must happen before the first hidden-member lookup, and the exemption is process-wide, so
        // one call here covers every later resolve.
        AirClipLog.d(LogTag.SHIZUKU_DIRECT, HiddenApiExemption.ensure())

        val raw = SystemServiceHelper.getSystemService(CLIPBOARD_SERVICE_NAME)
            ?: error("ServiceManager 没有 \"$CLIPBOARD_SERVICE_NAME\" 服务")
        AirClipLog.d(
            LogTag.SHIZUKU_DIRECT,
            "取得系统剪贴板 binder alive=${runCatching { raw.isBinderAlive }.getOrNull()}，" +
                "将通过 Shizuku transactRemote 转发",
        )
        return ShizukuBinderWrapper(raw)
    }
}
