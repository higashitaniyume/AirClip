package com.airclip.platform.shizuku

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.os.IBinder
import java.lang.reflect.Method

/**
 * Runs inside the Shizuku-spawned process (shell UID), which is why it may talk to the hidden
 * `IClipboard` service at all: `ClipboardService` exempts the shell from the "must be the default
 * IME or have focus" rule that blocks ordinary apps on Android 10+.
 *
 * Everything is reflection because `IClipboard`'s signature changed almost every release:
 *
 * ```
 * API 29  getPrimaryClip(String pkg)
 * API 30  getPrimaryClip(String pkg, int userId)
 * API 31  getPrimaryClip(String pkg, String attributionTag, int userId)
 * API 34  getPrimaryClip(String pkg, String attributionTag, int userId, int deviceId)
 * ```
 *
 * Rather than branch per release, [fillArguments] assigns by parameter type in declaration order:
 * first `String` is the calling package, second is the attribution tag, first `int` is the user id,
 * second is the device id.
 */
class AirClipShizukuService() : IShizukuClipboard.Stub() {

    /** Shizuku instantiates whichever of the two constructors it finds; keep both. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private val diagnostics = StringBuilder()
    private val clipboard: Any? by lazy { resolveClipboardService() }

    override fun getPrimaryClipText(): String? {
        val service = clipboard ?: return null
        val method = findMethod(service, "getPrimaryClip") ?: return null
        val clip = runCatching { method.invoke(service, *fillArguments(method, null)) as? ClipData }
            .onFailure { note("getPrimaryClip failed: ${it.cause ?: it}") }
            .getOrNull() ?: return null

        if (clip.itemCount == 0) return ""
        val item = clip.getItemAt(0)
        return item.text?.toString() ?: item.uri?.toString() ?: ""
    }

    override fun setPrimaryClipText(text: String?): Boolean {
        val service = clipboard ?: return false
        val method = findMethod(service, "setPrimaryClip") ?: return false
        val clip = ClipData.newPlainText(LABEL, text.orEmpty())
        return runCatching { method.invoke(service, *fillArguments(method, clip)) }
            .onFailure { note("setPrimaryClip failed: ${it.cause ?: it}") }
            .isSuccess
    }

    override fun describeBackend(): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(" uid=").append(android.os.Process.myUid())
        append(" service=").append(if (clipboard != null) "bound" else "missing")
        if (diagnostics.isNotEmpty()) append(' ').append(diagnostics)
    }

    override fun destroy() {
        // Nothing to release: the binder proxy is owned by the system, and Shizuku kills the process.
    }

    private fun resolveClipboardService(): Any? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager
            .getMethod("getService", String::class.java)
            .invoke(null, Context.CLIPBOARD_SERVICE) as? IBinder

        if (binder == null) {
            note("no clipboard binder")
            return@runCatching null
        }

        Class.forName("android.content.IClipboard\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }.onFailure { note("resolve failed: ${it.cause ?: it}") }.getOrNull()

    /** Prefers the overload with the most parameters: that is the one the running platform declares. */
    private fun findMethod(service: Any, name: String): Method? {
        val candidates = service.javaClass.methods.filter { it.name == name }
        if (candidates.isEmpty()) {
            note("$name not found")
            return null
        }
        return candidates.maxByOrNull { it.parameterCount }
    }

    private fun fillArguments(method: Method, clip: ClipData?): Array<Any?> {
        var stringsSeen = 0
        var intsSeen = 0
        return method.parameterTypes.map { type ->
            when {
                ClipData::class.java.isAssignableFrom(type) -> clip
                type == String::class.java -> if (stringsSeen++ == 0) CALLING_PACKAGE else null
                type == Int::class.javaPrimitiveType -> if (intsSeen++ == 0) userId() else DEVICE_ID_DEFAULT
                else -> null.also { note("unmapped parameter ${type.name} on ${method.name}") }
            }
        }.toTypedArray()
    }

    private fun userId(): Int = runCatching {
        Class.forName("android.os.UserHandle").getMethod("myUserId").invoke(null) as Int
    }.getOrDefault(0)

    private fun note(message: String) {
        if (diagnostics.length < 512) {
            diagnostics.append('[').append(message).append(']')
        }
    }

    private companion object {
        /** Must match the shell UID this process runs as, or the service rejects the call. */
        const val CALLING_PACKAGE = "com.android.shell"
        const val DEVICE_ID_DEFAULT = 0
        const val LABEL = "AirClip"
    }
}
