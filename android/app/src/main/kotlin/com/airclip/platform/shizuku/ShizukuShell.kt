package com.airclip.platform.shizuku

import android.os.ParcelFileDescriptor
import com.airclip.core.diag.AirClipLog
import com.airclip.core.diag.LogTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs a command inside the Shizuku server's own shell and hands back its output.
 *
 * This is the third door into privileged territory, and the only one that needs neither a spawned
 * helper process nor a hidden API: `IShizukuService.newProcess` is `Runtime.exec` executed by the
 * Shizuku server, so the command runs as uid 2000 (shell) or 0 (root). AirClip uses it for exactly one
 * thing — reading `logcat`, which shell may do and an app may not — and that turns out to be enough to
 * monitor the clipboard. See [ShizukuLogcatWatcher].
 *
 * `Shizuku.newProcess` became private in API 13 ("planned to be removed from Shizuku API 14"), so the
 * call goes through the AIDL interface directly. [Shizuku.getBinder] is documented as "normal apps
 * should not use this method", which is fair warning that this is a deprecated corner of the API; it is
 * also the only remaining public door to it, and every failure here is reported rather than thrown.
 */
internal object ShizukuShell {

    /** Why the last spawn failed, for callers that must explain a dead path instead of retrying it. */
    @Volatile
    var failure: String? = null
        private set

    /** Whether a command could be spawned at all — the Shizuku binder is present and authorised. */
    fun isReachable(): Boolean = service() != null

    /**
     * Spawns [command] and returns it with stdout already wrapped for line reading, or `null` when the
     * server refused. The caller owns the result and must [ShizukuProcess.close] it.
     */
    fun start(command: List<String>): ShizukuProcess? {
        val service = service() ?: run {
            failure = "Shizuku binder 不可用（服务未运行或未授权）"
            AirClipLog.w(LogTag.SHIZUKU_LOG, "无法执行 shell 命令：${failure}")
            return null
        }

        val text = command.joinToString(" ")
        return runCatching {
            val remote = service.newProcess(command.toTypedArray(), null, null)
                ?: error("newProcess 返回 null")
            val stdout = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
            failure = null
            AirClipLog.d(LogTag.SHIZUKU_LOG, "以 Shizuku 身份启动：$text")
            ShizukuProcess(remote, BufferedReader(InputStreamReader(stdout)))
        }.getOrElse { error ->
            // Shizuku 14 is expected to drop newProcess entirely; when it does, this is the line that
            // will say so, and the caller falls back to the other two paths rather than breaking.
            failure = describeError(error)
            AirClipLog.e(LogTag.SHIZUKU_LOG, "启动 shell 命令失败：$text", error)
            null
        }
    }

    /**
     * Runs a command that ends by itself and collects its output.
     *
     * Bounded twice over — [maxLines] and [timeoutMillis] — because this is a diagnostic path and a
     * wedged `logcat` must never be the reason the self-test hangs. `null` means the command could not
     * be started at all; an empty list means it started and said nothing.
     */
    suspend fun capture(
        command: List<String>,
        maxLines: Int = 2_000,
        timeoutMillis: Long = 5_000,
    ): List<String>? {
        val process = start(command) ?: return null
        return withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            val lines = ArrayList<String>()
            try {
                while (lines.size < maxLines && System.currentTimeMillis() < deadline) {
                    // Ready first, alive second: a process that has already exited may still have a
                    // pipe full of output, and that output is the whole point of the call.
                    if (process.stdout.ready()) {
                        lines.add(process.stdout.readLine() ?: break)
                    } else {
                        if (!process.isAlive) break
                        delay(POLL_MILLIS)
                    }
                }
            } catch (error: Throwable) {
                AirClipLog.w(LogTag.SHIZUKU_LOG, "读取命令输出中断", error)
            }

            if (lines.isEmpty()) {
                val complaint = process.drainStderr()
                if (complaint.isNotBlank()) {
                    AirClipLog.w(LogTag.SHIZUKU_LOG, "命令没有输出，stderr：${complaint.trim()}")
                }
            }
            process.close()
            lines
        }
    }

    /**
     * The same proxy [Shizuku] builds internally, from the same binder. Rebuilt per call rather than
     * cached: the Shizuku server restarting hands out a new binder and invalidates the old proxy.
     */
    private fun service(): IShizukuService? = runCatching {
        Shizuku.getBinder()?.let(IShizukuService.Stub::asInterface)
    }.getOrNull()

    private const val POLL_MILLIS = 20L
}

/**
 * A command running in the Shizuku server's shell. Since Shizuku 11 the process is killed when this
 * app's process dies, so a leaked one cannot outlive the app — but it can outlive the *feature*, which
 * is why every caller closes it in a `finally`.
 */
internal class ShizukuProcess internal constructor(
    private val remote: IRemoteProcess,
    val stdout: BufferedReader,
) : AutoCloseable {

    val isAlive: Boolean get() = runCatching { remote.alive() }.getOrDefault(false)

    /**
     * Whatever is already sitting in stderr, without waiting for more. A blocking read would hang
     * forever on a live process that simply has nothing to complain about, and the one thing this is
     * for — "logcat rejected my arguments" — is written before the process exits.
     */
    fun drainStderr(): String = runCatching {
        ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream).use { stream ->
            val available = stream.available()
            if (available <= 0) return@use ""
            val buffer = ByteArray(minOf(available, STDERR_LIMIT))
            val read = stream.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }
    }.getOrDefault("")

    override fun close() {
        runCatching { stdout.close() }
        runCatching { remote.destroy() }
    }

    private companion object {
        const val STDERR_LIMIT = 8 * 1024
    }
}
