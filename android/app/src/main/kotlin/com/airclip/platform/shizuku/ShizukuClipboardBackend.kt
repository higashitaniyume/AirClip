package com.airclip.platform.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import com.airclip.BuildConfig
import com.airclip.core.diag.AirClipLog
import com.airclip.core.diag.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Both privileged clipboard strategies behind one API: the in-process [ShizukuDirectClipboard] first,
 * and the Shizuku-spawned [AirClipShizukuService] as the fallback.
 *
 * The order is the lesson from the device this was debugged on. Binding a user service asks the
 * Shizuku server to spawn `app_process`, load this APK and instantiate a class; when any of that
 * fails, Shizuku 13 reports **nothing** — no exception from `bindUserService`, no `onNullBinding`, no
 * callback at all. That device polled for minutes with authorisation granted and polling on, and the
 * pipeline never produced a single read. The direct path needs none of those steps, so it is tried
 * first and the helper is only spawned while the direct path has no answer.
 *
 * Every call is bounded by a timeout — a wedged binder must never hang the clipboard pipeline — and
 * every step is written to [AirClipLog], because the failure this plan actually hits ("Shizuku says
 * granted, nothing syncs") is invisible from the outside.
 */
class ShizukuClipboardBackend(
    private val context: Context,
    private val gate: ShizukuGate,
    /** Used only for fire-and-forget diagnostics, so a binder callback never blocks the main thread. */
    private val scope: CoroutineScope,
) {

    /** Tried before the helper: same privilege, no extra process, nothing to spawn. */
    val direct = ShizukuDirectClipboard(gate)

    /**
     * Not a read path at all — the *trigger*. It reports when the clipboard changed; either path above
     * (or a focused window) then does the reading. See [ShizukuLogcatWatcher].
     */
    val logcat = ShizukuLogcatWatcher(context, gate)

    private val _connected = MutableStateFlow(false)

    /** Whether the *helper* is connected. The direct path needs no connection at all. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _diagnostics = MutableStateFlow("")

    /** What the two paths last said about themselves. Shown on the settings screen. */
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    @Volatile
    private var service: IShizukuClipboard? = null

    /** Stops the poll loop from firing a fresh bind on every tick while one is still in flight. */
    private val binding = AtomicBoolean(false)

    @Volatile
    private var bindStartedAt = 0L

    /** True while the direct path answered last, in which case spawning a helper is pure risk. */
    @Volatile
    private var directLeads = false

    /** Which path last answered; logged only when it changes, so a switch costs one line. */
    @Volatile
    private var source: String? = null

    /** "Helper not connected" deserves one warning, not one per poll tick. */
    @Volatile
    private var announcedDisconnect = false

    /**
     * The helper's last self-description, kept as a field rather than re-parsed out of [diagnostics].
     * Three blocks now share that string, and recovering one of them by splitting on its own header
     * would break the moment a header appeared in the text it introduces.
     */
    @Volatile
    private var helperDescription: String? = null

    private val component by lazy {
        ComponentName(context.packageName, AirClipShizukuService::class.java.name)
    }

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(component)
            .daemon(false) // Die with the app: a lingering shell process reading clipboards is not okay.
            .processNameSuffix("clip")
            // Never true, not even on debug builds: Shizuku passes this straight through to the
            // shell-spawned process, and a ROM that refuses those JDWP options kills the helper before
            // it can call back — indistinguishable from the silence this class exists to explain.
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding.set(false)
            announcedDisconnect = false
            val alive = runCatching { binder?.pingBinder() == true }.getOrDefault(false)
            service = binder?.takeIf { alive }?.let(IShizukuClipboard.Stub::asInterface)
            _connected.value = service != null

            if (service == null) {
                _diagnostics.value = "辅助进程返回了不可用的 binder"
                AirClipLog.e(
                    LogTag.SHIZUKU,
                    "用户服务连接回调拿到空/死 binder（alive=$alive）—— 辅助进程很可能启动即崩溃，" +
                        "可在 Logcat 里搜索 AirClipSvc",
                )
                return
            }
            AirClipLog.i(LogTag.SHIZUKU, "用户服务已连接 ${name?.flattenToShortString()}")
            // describeBackend() and drainLog() are blocking binder calls into a shell process; a
            // wedged helper would ANR the caller, and this callback runs on the main thread.
            scope.launch {
                describe()
                drainRemoteLog()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            AirClipLog.w(LogTag.SHIZUKU, "用户服务断开 ${name?.flattenToShortString()}")
            reset()
        }

        override fun onBindingDied(name: ComponentName?) {
            AirClipLog.w(LogTag.SHIZUKU, "用户服务绑定已失效 ${name?.flattenToShortString()}")
            reset()
        }

        override fun onNullBinding(name: ComponentName?) {
            AirClipLog.e(
                LogTag.SHIZUKU,
                "用户服务返回空绑定 ${name?.flattenToShortString()} —— 检查 AirClipShizukuService 是否被混淆或缺少构造函数",
            )
            reset()
        }
    }

    /**
     * No-op unless Shizuku is reachable, authorised, and the direct path is not already working.
     *
     * @param force bind even when the direct path is answering — the self-test needs to report on the
     *   helper regardless, otherwise a working device would show an unexplained "未连接".
     */
    fun bind(force: Boolean = false) {
        if (service != null) return
        if (!gate.isGranted()) {
            AirClipLog.d(
                LogTag.SHIZUKU,
                "不绑定用户服务：尚未取得授权，当前状态 ${gate.availability.value}",
            )
            return
        }
        if (directLeads && !force) {
            AirClipLog.t(LogTag.SHIZUKU, "不绑定用户服务：直连正在正常工作，不需要额外的 shell 进程")
            return
        }

        val now = System.currentTimeMillis()
        if (!binding.compareAndSet(false, true)) {
            val waited = now - bindStartedAt
            if (waited < BIND_RETRY_MS) {
                AirClipLog.t(LogTag.SHIZUKU, "跳过绑定：上一次绑定仍在进行（已等待 ${waited}ms）")
                return
            }
            AirClipLog.w(LogTag.SHIZUKU, "上一次绑定 ${waited}ms 没有任何回调，重新发起")
            clearStaleRecord()
        }
        bindStartedAt = now

        AirClipLog.i(
            LogTag.SHIZUKU,
            "绑定用户服务 ${component.flattenToShortString()} version=${BuildConfig.VERSION_CODE} " +
                "daemon=false · ${describeServerRecord()}",
        )
        runCatching { Shizuku.bindUserService(serviceArgs, connection) }
            .onFailure { error ->
                binding.set(false)
                _diagnostics.value = "bindUserService 失败：${AirClipLog.describe(error)}"
                AirClipLog.e(LogTag.SHIZUKU, "bindUserService 抛出异常，Shizuku 没能启动辅助进程", error)
            }
    }

    /**
     * Shizuku keeps one user-service record per (package, class, version). If the shell process died
     * before it could call back, that record survives holding a dead binder, and every later bind is
     * answered from it — silently, forever, across app restarts. Removing it is the only way back, and
     * a stale record is the most likely explanation when `bindUserService` never calls back at all.
     */
    private fun clearStaleRecord() {
        service = null
        _connected.value = false
        AirClipLog.w(LogTag.SHIZUKU, "移除 Shizuku 服务器上的旧用户服务记录，再重新创建一个")
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
            .onFailure { AirClipLog.w(LogTag.SHIZUKU, "移除旧用户服务记录失败", it) }
    }

    /**
     * What the Shizuku server itself thinks about our user service, without asking it to start one.
     * This is the line that separates "the server never spawned anything" from "the server believes a
     * service is running and is answering our binds from a dead record".
     */
    fun describeServerRecord(): String = when (val version = peek()) {
        null -> "服务器状态查询失败"
        -1 -> "服务器上没有该服务的记录"
        else -> "服务器认为服务已在运行 version=$version"
    }

    /** Also delivers the binder if the server has a live record, which is a free recovery path. */
    private fun peek(): Int? = runCatching { Shizuku.peekUserService(serviceArgs, connection) }
        .onFailure { AirClipLog.w(LogTag.SHIZUKU, "peekUserService 调用失败", it) }
        .getOrNull()

    fun unbind() {
        if (service == null && !_connected.value && !binding.get()) return
        AirClipLog.i(LogTag.SHIZUKU, "解绑用户服务")
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
            .onFailure { AirClipLog.w(LogTag.SHIZUKU, "unbindUserService 失败", it) }
        reset()
    }

    /**
     * `null` when neither path has an answer; an empty string means "clipboard is genuinely empty".
     *
     * The direct path goes first: it is one binder call with no process to spawn, so when it works
     * there is no reason to have a shell helper at all.
     */
    suspend fun getText(): String? {
        directCall("读取") { direct.text() }?.let { text ->
            takeLead("直连")
            return text
        }
        directLeads = false
        return call("getPrimaryClipText") { it.primaryClipText }?.also { takeLead("辅助进程") }
    }

    suspend fun setText(text: String): Boolean {
        if (directCall("写入") { direct.setText(text) } == true) {
            takeLead("直连")
            return true
        }
        directLeads = false
        return (call("setPrimaryClipText") { it.setPrimaryClipText(text) } ?: false)
            .also { if (it) takeLead("辅助进程") }
    }

    /**
     * Helper-only read, for the self-test: [getText] would answer from the direct path and say nothing
     * about the helper, which is the half the user needs a verdict on when the direct path is blocked.
     */
    suspend fun helperText(): String? = call("getPrimaryClipText") { it.primaryClipText }

    /** Asks the helper what it managed to resolve. Also refreshes [diagnostics]. */
    suspend fun describe(): String? = call("describeBackend") { it.describeBackend() }
        ?.also { text ->
            AirClipLog.i(LogTag.SHIZUKU, "辅助进程自述 $text")
            refreshDiagnostics(text)
        }

    /** Copies the helper process's own journal into the app log. Returns how many lines arrived. */
    suspend fun drainRemoteLog(): Int {
        val target = service ?: return 0
        val text = withContext(Dispatchers.IO) {
            withTimeoutOrNull(CALL_TIMEOUT_MS) { runCatching { target.drainLog() }.getOrNull() }
        }.orEmpty()

        val lines = text.split('\n').filter { it.isNotBlank() }
        lines.forEach { AirClipLog.i(LogTag.SHIZUKU_SVC, it) }
        return lines.size
    }

    /** All three halves in one block, so the settings screen shows why whichever path is dead is dead. */
    fun refreshDiagnostics(helperText: String? = null) {
        helperText?.let { helperDescription = it }
        _diagnostics.value = buildString {
            append(DIRECT_HEADER).append('\n').append(direct.describe())
            helperDescription?.takeIf { it.isNotBlank() }?.let {
                append('\n').append(HELPER_HEADER).append('\n').append(it)
            }
            append('\n').append(LOGCAT_HEADER).append('\n').append("  ").append(logcat.describe())
        }
    }

    private fun takeLead(next: String) {
        directLeads = next == "直连"
        if (source == next) return
        val previous = source
        source = next
        AirClipLog.i(
            LogTag.SHIZUKU,
            if (previous == null) "剪贴板走「$next」路径" else "剪贴板读写路径 $previous → $next",
        )
        refreshDiagnostics()
    }

    private suspend fun <T> directCall(label: String, block: () -> T): T? {
        val outcome = withContext(Dispatchers.IO) {
            withTimeoutOrNull(CALL_TIMEOUT_MS) { runCatching(block) }
        }
        if (outcome == null) {
            AirClipLog.e(LogTag.SHIZUKU_DIRECT, "直连$label 超过 ${CALL_TIMEOUT_MS}ms 无响应")
            return null
        }
        outcome.exceptionOrNull()?.let { error ->
            AirClipLog.e(LogTag.SHIZUKU_DIRECT, "直连$label 失败", error)
            direct.invalidate()
            return null
        }
        return outcome.getOrNull()
    }

    /**
     * Polls the privileged clipboard and emits every change.
     *
     * Polling is deliberate and confined to this one backend: `IClipboard`'s change callback needs a
     * `IOnPrimaryClipChangedListener` stub, and registering one from a shell identity is far more
     * fragile than an interval read. The IME and accessibility backends are event-driven, so this path
     * only runs when the user picked Shizuku as their read strategy.
     */
    fun textChanges(intervalMillis: Long): Flow<String> = flow {
        val interval = intervalMillis.coerceAtLeast(250)
        AirClipLog.i(LogTag.SHIZUKU, "开始轮询剪贴板，间隔 ${interval}ms")

        var previous: String? = null
        var misses = 0
        var ticks = 0L

        while (true) {
            val current = getText()
            ticks++

            if (current == null) {
                misses++
                if (misses == 1 || misses % MISS_REPORT_EVERY == 0) {
                    AirClipLog.w(
                        LogTag.SHIZUKU,
                        "连续第 $misses 次读取无结果：直连和辅助进程都没有给出内容。" +
                            "先复制一段文字，再运行「Shizuku 自检」就能看到具体原因",
                    )
                    // A connected helper that still hands back nothing has already written down why,
                    // and a null return is a *successful* binder call — so no other path pulls that
                    // journal over. This is the one case the log would otherwise never explain.
                    if (_connected.value) drainRemoteLog()
                }
            } else {
                if (misses > 0) {
                    AirClipLog.i(LogTag.SHIZUKU, "读取恢复正常（此前连续 $misses 次无结果）")
                    misses = 0
                }
                if (current.isNotEmpty() && current != previous) {
                    val first = previous == null
                    previous = current
                    AirClipLog.i(
                        LogTag.SHIZUKU,
                        if (first) {
                            "轮询首次读到内容 ${AirClipLog.redact(current)}，按现有逻辑会立即上报一次"
                        } else {
                            "检测到剪贴板变化 ${AirClipLog.redact(current)}"
                        },
                    )
                    emit(current)
                } else {
                    AirClipLog.t(LogTag.SHIZUKU, "轮询 #$ticks 无变化 ${AirClipLog.redact(current)}")
                }
            }

            if (ticks % HEARTBEAT_EVERY == 0L) {
                AirClipLog.d(
                    LogTag.SHIZUKU,
                    "轮询存活 #$ticks 路径=${source ?: "尚无"} 辅助进程已连接=${_connected.value} " +
                        "当前连续无结果=$misses",
                )
            }
            delay(interval)
        }
    }

    private suspend fun <T> call(label: String, block: (IShizukuClipboard) -> T): T? {
        val target = service ?: run {
            if (announcedDisconnect) {
                AirClipLog.t(LogTag.SHIZUKU, "$label 无法执行：辅助进程未连接")
            } else {
                announcedDisconnect = true
                AirClipLog.w(
                    LogTag.SHIZUKU,
                    "$label 无法执行：辅助进程未连接，开始尝试绑定；" +
                        "重复出现的同一句只会记在「详细轮询」里，不再重复刷屏",
                )
            }
            bind()
            return null
        }

        val result = withContext(Dispatchers.IO) {
            withTimeoutOrNull(CALL_TIMEOUT_MS) { runCatching { block(target) } }
        }

        if (result == null) {
            _diagnostics.value = "$label 超时（${CALL_TIMEOUT_MS}ms）"
            AirClipLog.e(LogTag.SHIZUKU, "$label 超过 ${CALL_TIMEOUT_MS}ms 无响应，辅助进程可能卡死")
            pullRemoteLog()
            return null
        }

        result.exceptionOrNull()?.let { error ->
            _diagnostics.value = "$label 失败：${AirClipLog.describe(error)}"
            AirClipLog.e(LogTag.SHIZUKU, "$label 调用失败", error)
            if (error is DeadObjectException) {
                AirClipLog.w(LogTag.SHIZUKU, "辅助进程已消失，重置连接，下一次调用会重新绑定")
                reset()
            } else {
                pullRemoteLog()
            }
            return null
        }
        return result.getOrNull()
    }

    /** Fire and forget: the helper's own notes are the interesting half of any failure. */
    private fun pullRemoteLog() {
        scope.launch { drainRemoteLog() }
    }

    private fun reset() {
        service = null
        binding.set(false)
        _connected.value = false
        announcedDisconnect = false
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 2_500L

        /** How long a bind may stay silent before the poll loop is allowed to try again. */
        const val BIND_RETRY_MS = 10_000L

        /** A failing poll would otherwise write one warning per second. */
        const val MISS_REPORT_EVERY = 30

        const val HEARTBEAT_EVERY = 60L

        const val DIRECT_HEADER = "直连："
        const val HELPER_HEADER = "辅助进程："
        const val LOGCAT_HEADER = "日志监听："
    }
}
