package com.airclip

import android.app.Application
import android.content.Context
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
