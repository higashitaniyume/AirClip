package com.airclip.platform.shizuku

import android.os.Build
import java.lang.reflect.Method

/**
 * Asks the runtime to stop hiding non-SDK members from reflection, so the app process can look up
 * `IClipboard$Stub.asInterface` at all.
 *
 * The Shizuku-spawned helper process needs none of this: it is started by `app_process` rather than
 * forked from zygote as an app, so no hidden-API policy is installed there. The in-process
 * [ShizukuDirectClipboard] path is a normal app process on API 28+, where the same lookup is either
 * warned about or blocked outright depending on the platform's list.
 *
 * The trick is that the runtime attributes a reflective call to the class that made it. Calling
 * `Class.getDeclaredMethod` *through reflection* makes `java.lang.Class` the caller, and platform
 * classes are exempt — which is exactly enough to reach `VMRuntime.setHiddenApiExemptions` and lift
 * the restriction for everything (`"L"` prefixes every JNI class descriptor).
 *
 * This is best effort by design. Whether it still works is a per-release, per-ROM question, so the
 * outcome is a string the log and the self-test can quote rather than a boolean that hides the
 * reason. When it fails, the user service path is still there.
 */
internal object HiddenApiExemption {

    @Volatile
    private var outcome: String? = null

    /** Runs once per process; later calls return the first outcome verbatim. */
    fun ensure(): String {
        outcome?.let { return it }
        return synchronized(this) {
            outcome ?: attempt().also { outcome = it }
        }
    }

    private fun attempt(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "无需豁免（API ${Build.VERSION.SDK_INT} 不限制隐藏 API）"
        }
        return runCatching {
            val classClass = Class::class.java
            val forName = classClass.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = classClass.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                arrayOf<Class<*>>()::class.java,
            )

            val vmRuntimeClass = forName.invoke(null, VM_RUNTIME) as Class<*>
            val getRuntime = getDeclaredMethod
                .invoke(vmRuntimeClass, "getRuntime", arrayOf<Class<*>>()) as Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf<Class<*>>(Array<String>::class.java),
            ) as Method

            setExemptions.invoke(getRuntime.invoke(null), arrayOf(EVERYTHING))
            "已申请隐藏 API 豁免"
        }.getOrElse { "隐藏 API 豁免失败 ${describeError(it)}" }
    }

    private const val VM_RUNTIME = "dalvik.system.VMRuntime"

    /** Every class descriptor starts with `L`, so this one prefix covers the whole platform. */
    private const val EVERYTHING = "L"
}
