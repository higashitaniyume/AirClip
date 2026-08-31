package com.airclip.platform.shizuku

import android.content.ClipData
import android.os.IBinder
import java.lang.reflect.Method

/**
 * Talks to the hidden `IClipboard` system service through reflection, for whichever privileged
 * identity the caller can arrange.
 *
 * Both Shizuku strategies need exactly this and differ only in *how* the transaction reaches
 * `system_server`: [AirClipShizukuService] runs inside a Shizuku-spawned shell process and holds a
 * plain binder, while [ShizukuDirectClipboard] stays in the app process and holds a
 * `ShizukuBinderWrapper`, which forwards each transaction through the Shizuku server so the platform
 * still sees shell. The reflection, the calling-package guessing and the signature matching are
 * identical, so they live here once.
 *
 * Signatures changed almost every release:
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
 * second is the device id. A ROM that adds another parameter gets `null`/`0` and a logged warning
 * instead of a crash.
 *
 * Nothing here may touch `AirClipLog`: half of the callers live in a process that has never loaded
 * the app's own logging object. Lines go out through [note] instead, and the owner decides where they
 * land.
 *
 * @param label how this instance identifies itself in [note] — "辅助进程" or "直连".
 * @param callingUid the uid **the platform will attribute the call to**, which is not necessarily
 *   this process's uid: the direct path runs as the app but is billed to the Shizuku server.
 * @param binder resolves the clipboard binder, already wrapped if the strategy needs it.
 */
internal class ClipboardReflect(
    private val label: String,
    private val callingUid: () -> Int,
    private val binder: () -> IBinder?,
    private val note: (String) -> Unit,
) {

    private val lock = Any()

    private var resolved = false
    private var service: Any? = null
    private var getMethod: Method? = null
    private var setMethod: Method? = null

    /** The calling package the platform accepted, once one has; candidates are retried until then. */
    @Volatile
    private var accepted: String? = null

    /** Why [resolve] gave up, for callers that must explain a dead path rather than just retry it. */
    @Volatile
    var failure: String? = null
        private set

    /** True once the service and both methods are in hand. Resolves on first call. */
    fun isUsable(): Boolean = synchronized(lock) { resolve() != null && getMethod != null }

    /**
     * `null` means "no answer": the service was never resolved, the method is missing, or every
     * calling-package candidate was refused. An empty string means the platform answered with an
     * empty clip. [note] carries which of those it was.
     */
    fun text(): String? {
        val (service, method) = synchronized(lock) { resolve() to getMethod }
        if (service == null || method == null) return null

        for (pkg in callingPackages()) {
            val outcome = runCatching { method.invoke(service, *fillArguments(method, pkg, null)) }
            val error = outcome.exceptionOrNull()
            if (error != null) {
                note("$label getPrimaryClip[$pkg] 抛出 ${describeError(error)}")
                // A transport-level failure invalidates the cached proxy, not the package guess: the
                // Shizuku server may have restarted underneath us, and the next call should re-resolve.
                if (isTransportFailure(error)) invalidate()
                continue
            }

            val clip = outcome.getOrNull() as? ClipData
            if (clip == null) {
                // ClipboardService returns null both for an empty clipboard and for a refused read —
                // it logs "Denying clipboard access" itself and hands back nothing either way.
                note("$label getPrimaryClip[$pkg] 返回 null（剪贴板为空，或系统静默拒绝了读取）")
                continue
            }

            accepted = pkg
            if (clip.itemCount == 0) {
                note("$label getPrimaryClip[$pkg] 成功，但 itemCount=0")
                return ""
            }
            val item = clip.getItemAt(0)
            val text = item.text?.toString() ?: item.uri?.toString() ?: ""
            note(
                "$label getPrimaryClip[$pkg] 成功 items=${clip.itemCount} " +
                    "mime=${runCatching { clip.description?.getMimeType(0) }.getOrNull()} len=${text.length}",
            )
            return text
        }
        return null
    }

    fun setText(text: String?): Boolean {
        val (service, method) = synchronized(lock) { resolve() to setMethod }
        if (service == null || method == null) return false
        val clip = ClipData.newPlainText(LABEL, text.orEmpty())

        for (pkg in callingPackages()) {
            val outcome = runCatching { method.invoke(service, *fillArguments(method, pkg, clip)) }
            val error = outcome.exceptionOrNull()
            if (error == null) {
                accepted = pkg
                note("$label setPrimaryClip[$pkg] 成功 len=${text?.length ?: 0}")
                return true
            }
            note("$label setPrimaryClip[$pkg] 抛出 ${describeError(error)}")
            if (isTransportFailure(error)) invalidate()
        }
        return false
    }

    /**
     * Everything the settings screen and the self-test need to judge this path. The literals
     * `未解析` and `缺失` are what [ShizukuDoctor] keys on, so they must survive edits.
     */
    fun describe(): String = buildString {
        val service = synchronized(lock) { resolve() }
        append("clipboard 服务=").append(service?.javaClass?.name ?: "未解析")
        failure?.let { append("（").append(it).append("）") }
        append("\n  getPrimaryClip=").append(synchronized(lock) { getMethod }?.let(::signature) ?: "缺失")
        append("\n  setPrimaryClip=").append(synchronized(lock) { setMethod }?.let(::signature) ?: "缺失")
        append("\n  callingPackage=")
            .append(accepted?.let { "$it（已被系统接受）" } ?: "未确定，候选 ${callingPackages().joinToString("、")}")
    }

    /** Drops every cached handle so the next call resolves from scratch. */
    fun invalidate() = synchronized(lock) {
        if (!resolved) return@synchronized
        note("$label 丢弃已缓存的 clipboard 代理，下次调用会重新解析")
        resolved = false
        service = null
        getMethod = null
        setMethod = null
    }

    /** Caller must hold [lock]. Resolves once and remembers the outcome, including failure. */
    private fun resolve(): Any? {
        if (resolved) return service
        resolved = true

        val target = runCatching(binder).getOrElse { error ->
            val reason = "取剪贴板 binder 失败 ${describeError(error)}"
            failure = reason
            note("$label $reason")
            null
        }
        if (target == null) {
            if (failure == null) {
                failure = "系统没有返回 clipboard binder"
                note("$label ServiceManager 没有给出 clipboard 服务 —— 这台 ROM 可能没有标准剪贴板服务")
            }
            return null
        }
        note(
            "$label clipboard binder alive=${runCatching { target.isBinderAlive }.getOrNull()} " +
                "descriptor=${runCatching { target.interfaceDescriptor }.getOrNull()}",
        )

        service = runCatching {
            Class.forName(STUB_CLASS)
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, target)
        }.getOrElse { error ->
            // On a normal app process this is where the hidden-API policy bites; the helper process
            // has no such policy, so the same line succeeding there is itself the diagnosis.
            val reason = "反射 IClipboard\$Stub 失败 ${describeError(error)}"
            failure = reason
            note("$label $reason（系统可能屏蔽了隐藏 API）")
            null
        }
        val current = service ?: return null

        failure = null
        getMethod = findMethod(current, "getPrimaryClip")
        setMethod = findMethod(current, "setPrimaryClip")
        return current
    }

    /** Prefers the overload with the most parameters: that is the one the running platform declares. */
    private fun findMethod(service: Any, name: String): Method? {
        val candidates = service.javaClass.methods.filter { it.name == name }
        if (candidates.isEmpty()) {
            note("$label $name 在 ${service.javaClass.name} 上不存在")
            return null
        }
        if (candidates.size > 1) {
            note("$label $name 有多个重载：${candidates.joinToString(" / ") { signature(it) }}")
        }
        return candidates.maxByOrNull { it.parameterCount }?.also { note("$label 选用 ${signature(it)}") }
    }

    private fun signature(method: Method): String =
        method.parameterTypes.joinToString(", ", "${method.name}(", ")") { it.simpleName }

    /**
     * Candidate values for `IClipboard`'s `callingPackage` argument, best first.
     *
     * The platform runs `AppOpsManager.checkPackage(callingUid, callingPackage)` before anything else,
     * so the name has to belong to the uid the call is attributed to: `com.android.shell` is uid 2000
     * and throws outright under a root-mode Shizuku or Sui, where `"root"` — one of the pseudo-packages
     * `AppOpsService.resolveUid` accepts — is the name that passes. Whichever the ROM accepts is
     * remembered in [accepted], so the retry costs one extra binder round trip, once.
     */
    private fun callingPackages(): List<String> {
        accepted?.let { return listOf(it) }
        return when (runCatching(callingUid).getOrDefault(-1)) {
            UID_SHELL -> listOf(SHELL_PACKAGE)
            UID_ROOT -> listOf("root", SHELL_PACKAGE)
            else -> listOf(SHELL_PACKAGE, "root")
        }
    }

    private fun fillArguments(method: Method, callingPackage: String, clip: ClipData?): Array<Any?> {
        var stringsSeen = 0
        var intsSeen = 0
        return method.parameterTypes.map { type ->
            when {
                ClipData::class.java.isAssignableFrom(type) -> clip
                type == String::class.java -> if (stringsSeen++ == 0) callingPackage else null
                type == Int::class.javaPrimitiveType -> if (intsSeen++ == 0) userId() else DEVICE_ID_DEFAULT
                else -> null.also { note("$label ${method.name} 有未识别的参数类型 ${type.name}") }
            }
        }.toTypedArray()
    }

    private companion object {
        const val STUB_CLASS = "android.content.IClipboard\$Stub"

        /** The uid a call may be attributed to; `android.os.Process`' constants are not public API. */
        const val UID_ROOT = 0
        const val UID_SHELL = 2000

        /** Holds `READ_CLIPBOARD_IN_BACKGROUND`, which is the whole reason this plan works. */
        const val SHELL_PACKAGE = "com.android.shell"
        const val DEVICE_ID_DEFAULT = 0
        const val LABEL = "AirClip"
    }
}

/** The user id this process reports; `UserHandle.myUserId` is not public API. */
internal fun userId(): Int = runCatching {
    Class.forName("android.os.UserHandle").getMethod("myUserId").invoke(null) as Int
}.getOrDefault(0)

/** The clipboard service name, so callers need not import `Context` for one constant. */
internal const val CLIPBOARD_SERVICE_NAME = "clipboard"

/** Reflection wraps everything in `InvocationTargetException`; the cause is the whole story. */
internal fun describeError(error: Throwable): String {
    var root = error
    var guard = 0
    while (guard++ < 8) {
        val next = root.cause ?: break
        if (next === root) break
        root = next
    }
    val head = "${error.javaClass.simpleName}: ${error.message}"
    return if (root === error) head else "$head ← ${root.javaClass.simpleName}: ${root.message}"
}

/**
 * True when the failure says "this proxy is no longer usable" rather than "the platform said no".
 * A `SecurityException` means the call arrived and was rejected — the proxy is fine and the next
 * candidate package is worth trying. A dead binder means the far side is gone.
 */
internal fun isTransportFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    var guard = 0
    while (current != null && guard++ < 8) {
        if (current is android.os.DeadObjectException) return true
        val name = current.javaClass.name
        if (name.endsWith("DeadSystemException") || name.endsWith("TransactionTooLargeException")) return true
        if (current is IllegalStateException && current.message?.contains("binder", ignoreCase = true) == true) {
            return true
        }
        current = current.cause
    }
    return false
}
