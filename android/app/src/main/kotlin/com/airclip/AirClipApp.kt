package com.airclip

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.airclip.core.diag.AirClipLog
import com.airclip.core.diag.LogTag
import com.airclip.runtime.AirClipRuntime
import com.airclip.service.SyncNotifications

/**
 * Owns the one [AirClipRuntime] instance. The activity, the foreground service, the IME, the
 * accessibility service and the tile all reach it through [runtime]; sharing one clipboard engine
 * is what keeps the loop guard meaningful across those five entry points.
 */
class AirClipApp : Application() {

    lateinit var runtime: AirClipRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        // First line in the buffer, and the one the reader of a bug report needs before any other:
        // the ring buffer is per-process, so the pid says whether a later line came from the app or
        // from a second process that also loaded this Application.
        AirClipLog.section(LogTag.APP, "进程启动")
        AirClipLog.i(
            LogTag.APP,
            "AirClip ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.SDK_INT} · " +
                "${Build.MANUFACTURER}/${Build.MODEL} · pid=${Process.myPid()}",
        )
        runtime = AirClipRuntime(this)
        // Channels must exist before the service posts its first notification, which can happen
        // before any UI is created (boot, tile tap).
        SyncNotifications.ensureChannels(this)
    }

    override fun onTerminate() {
        if (::runtime.isInitialized) runtime.release()
        super.onTerminate()
    }

    companion object {
        /** Safe from any component context in this process. */
        fun runtime(context: Context): AirClipRuntime = (context.applicationContext as AirClipApp).runtime
    }
}
