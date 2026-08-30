package com.airclip.platform.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.airclip.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Client half of the Shizuku backend: binds [AirClipShizukuService] into a shell-UID process and
 * forwards clipboard calls to it. Every call is bounded by a timeout — a wedged binder must never
 * hang the clipboard pipeline.
 */
class ShizukuClipboardBackend(
    private val context: Context,
    private val gate: ShizukuGate,
) {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    @Volatile
    private var service: IShizukuClipboard? = null

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, AirClipShizukuService::class.java.name))
            .daemon(false) // Die with the app: a lingering shell process reading clipboards is not okay.
            .processNameSuffix("clip")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let(IShizukuClipboard.Stub::asInterface)
            _connected.value = service != null
            _diagnostics.value = service?.let { runCatching { it.describeBackend() }.getOrDefault("") }.orEmpty()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _connected.value = false
        }
    }

    /** No-op unless Shizuku is both reachable and authorised, so it is safe to call speculatively. */
    fun bind() {
        if (!gate.isGranted() || service != null) return
        runCatching { Shizuku.bindUserService(serviceArgs, connection) }
            .onFailure { _diagnostics.value = "bind failed: $it" }
    }

    fun unbind() {
        if (service == null && !_connected.value) return
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        service = null
        _connected.value = false
    }

    /** `null` when Shizuku is unavailable; an empty string means "clipboard is genuinely empty". */
    suspend fun getText(): String? = call { it.primaryClipText }

    suspend fun setText(text: String): Boolean = call { it.setPrimaryClipText(text) } ?: false

    /**
     * Polls the shell-side clipboard and emits every change.
     *
     * Polling is deliberate and confined to this one backend: `IClipboard`'s change callback needs a
     * `IOnPrimaryClipChangedListener` stub, and registering one from a Shizuku user service is far
     * more fragile than an interval read. The IME and accessibility backends are event-driven, so
     * this path only runs when the user picked Shizuku as their read strategy.
     */
    fun textChanges(intervalMillis: Long): Flow<String> = flow {
        var previous: String? = null
        while (true) {
            val current = getText()
            if (current != null && current.isNotEmpty() && current != previous) {
                previous = current
                emit(current)
            }
            delay(intervalMillis.coerceAtLeast(250))
        }
    }

    private suspend fun <T> call(block: (IShizukuClipboard) -> T): T? {
        val target = service ?: run {
            bind()
            return null
        }

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(CALL_TIMEOUT_MS) {
                runCatching { block(target) }
                    .onFailure { _diagnostics.value = "call failed: $it" }
                    .getOrNull()
            }
        }
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 2_500L
    }
}
