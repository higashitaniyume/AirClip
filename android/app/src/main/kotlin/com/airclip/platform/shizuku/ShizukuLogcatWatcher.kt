package com.airclip.platform.shizuku

import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService
import com.airclip.core.diag.AirClipLog
import com.airclip.core.diag.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Notices clipboard changes by watching the platform *refuse* them, which is the one signal Android
 * 10+ still gives a background app.
 *
 * The idea is borrowed from `kdeconnect-android-shizuku`, and it is a good deal more robust than
 * either of AirClip's other two Shizuku paths because it touches no hidden API and spawns no helper:
 *
 *  1. The app registers an ordinary [ClipboardManager.OnPrimaryClipChangedListener]. It will not fire
 *     while the app is in the background — but registering it is what makes the platform *try*.
 *  2. On every clipboard change `ClipboardService` walks its listeners and calls `clipboardAccessAllowed`
 *     for each one. For an unfocused app that check fails, and the failure is logged:
 *     `Denying clipboard access to com.airclip, application is not in focus nor is it a system service`.
 *  3. That line names this app and is emitted *at the moment of the change*. An app may not read
 *     `logcat`, but shell may — and [ShizukuShell] runs `logcat` as shell.
 *  4. So the denial is the change notification. What to do with it is the caller's business: read
 *     through Shizuku if either privileged path works, or briefly show a focusable window and let the
 *     ordinary framework read succeed.
 *
 * The listener is therefore registered here rather than by whoever consumes the flow: it is not how
 * the events arrive, it is what causes them to exist. When it does fire — the app happens to be
 * focused — that is a real change too, and it is emitted as well.
 *
 * Cost is one shell `logcat -T 1 *:W` and a substring test per line. Warning level rather than error:
 * whether the denial is logged at `W` or `E` has moved between Android releases and OEM ROMs.
 */
class ShizukuLogcatWatcher(private val context: Context, private val gate: ShizukuGate) {

    /** One phrase describing where this path stands, for [describe] and the settings screen. */
    @Volatile
    private var state: String = "未启动"

    /** How many changes this path has reported since the process started, across reopened `logcat`s. */
    @Volatile
    private var reported = 0L

    /**
     * Emits once per detected clipboard change, carrying the line (or callback) that betrayed it.
     *
     * Never throws and never completes on its own while it can still work: a `logcat` that dies is
     * reopened after a short delay, because the Shizuku server restarting is normal and the user should
     * not have to toggle anything to recover from it.
     */
    fun changes(): Flow<String> = channelFlow {
        if (!gate.isGranted()) {
            state = "未授权"
            AirClipLog.w(LogTag.SHIZUKU_LOG, "不启动日志监听：Shizuku 尚未授权（${gate.availability.value}）")
            return@channelFlow
        }

        val clipboard = context.getSystemService<ClipboardManager>()
        // The provocation, not the transport: see the class comment. A change while AirClip happens to
        // be focused fires this instead of being denied, and both mean the same thing to the caller.
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            reported++
            AirClipLog.i(LogTag.SHIZUKU_LOG, "系统直接回调了剪贴板变化（此刻本应用有焦点）")
            trySend(SOURCE_CALLBACK)
        }
        withContext(Dispatchers.Main) {
            runCatching { clipboard?.addPrimaryClipChangedListener(listener) }
                .onFailure { AirClipLog.w(LogTag.SHIZUKU_LOG, "注册剪贴板变化监听失败", it) }
        }

        try {
            withContext(Dispatchers.IO) { pump(channel) }
        } finally {
            state = "已停止"
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { clipboard?.removePrimaryClipChangedListener(listener) }
            }
        }
    }

    /** Cheap enough for the self-test: can this process read the system log through Shizuku at all? */
    suspend fun isUsable(): Boolean {
        if (!gate.isGranted()) return false
        val lines = ShizukuShell.capture(PROBE_COMMAND, maxLines = 5, timeoutMillis = PROBE_TIMEOUT_MS)
        return lines != null && lines.isNotEmpty()
    }

    /**
     * The last few hundred log lines that mention the Shizuku helper, the Shizuku server, or a Java
     * crash — the ones the self-test used to have to send the user to a PC for.
     *
     * Deliberately unfiltered on the `logcat` side and filtered here: the tag filterspec is one of the
     * few things whose accepted syntax has actually changed between releases, and getting it wrong
     * yields silence rather than an error.
     */
    suspend fun report(): List<String>? {
        val lines = ShizukuShell.capture(DUMP_COMMAND, timeoutMillis = DUMP_TIMEOUT_MS) ?: return null
        return lines.filter { line -> INTERESTING.any { line.contains(it) } }
    }

    /** Safe to call any time; makes no binder call that could block. */
    fun describe(): String = buildString {
        append("shell 可用=").append(if (ShizukuShell.isReachable()) "是" else "否")
        append(" 状态=").append(state)
        append(" 已报告变化=").append(reported)
        ShizukuShell.failure?.let { append(" 最近失败=").append(it) }
    }

    private suspend fun CoroutineScope.pump(out: SendChannel<String>) {
        var round = 0
        while (isActive) {
            val process = ShizukuShell.start(LIVE_COMMAND)
            if (process == null) {
                // A refusal to spawn is permanent until something else changes; retrying every few
                // seconds would only fill the log with the same sentence.
                state = "无法启动 logcat：${ShizukuShell.failure}"
                AirClipLog.e(LogTag.SHIZUKU_LOG, "日志监听不可用，放弃这条路：${ShizukuShell.failure}")
                return
            }

            round++
            state = "正在监听（第 $round 次）"
            AirClipLog.i(
                LogTag.SHIZUKU_LOG,
                "开始监听系统日志（第 $round 次）：匹配同时含「${context.packageName}」和「clipboard access」的行。" +
                    "系统每次拒绝本应用读取剪贴板都会写下这样一行，那一刻正是剪贴板发生变化的时刻",
            )

            val scanned = try {
                drain(process, out)
            } finally {
                process.close()
            }

            if (!isActive) return
            state = "logcat 已退出，等待重开"
            AirClipLog.w(
                LogTag.SHIZUKU_LOG,
                "logcat 进程结束（本轮扫描 $scanned 行），${RESTART_DELAY_MS}ms 后重新打开",
            )
            delay(RESTART_DELAY_MS)
        }
    }

    /**
     * Reads until the process ends or the collector goes away. `ready()` rather than a blocking
     * `readLine()` so cancellation lands within [IDLE_POLL_MS] instead of whenever the system next
     * happens to log something — a stopped sync service must not leave a shell process behind.
     */
    private suspend fun CoroutineScope.drain(process: ShizukuProcess, out: SendChannel<String>): Long {
        var scanned = 0L
        var matched = 0L
        var nearMisses = 0
        while (isActive) {
            if (!process.stdout.ready()) {
                if (!process.isAlive) break
                delay(IDLE_POLL_MS)
                continue
            }

            val line = runCatching { process.stdout.readLine() }.getOrNull() ?: break
            scanned++
            if (!isSignal(line)) {
                // A ROM that words the denial differently would otherwise be indistinguishable from a
                // ROM that never logs one. The first few near misses are printed verbatim so the actual
                // wording can be read off the phone instead of guessed at.
                if (nearMisses < NEAR_MISS_LIMIT && mentionsUs(line)) {
                    nearMisses++
                    AirClipLog.d(
                        LogTag.SHIZUKU_LOG,
                        "日志提到本应用但不像剪贴板拒绝记录（第 $nearMisses 条，只记前 $NEAR_MISS_LIMIT 条）：" +
                            line.takeLast(200),
                    )
                }
                continue
            }

            matched++
            reported++
            AirClipLog.i(
                LogTag.SHIZUKU_LOG,
                "系统日志第 $matched 次拒绝本应用读取剪贴板 —— 判定为剪贴板发生了变化：${line.takeLast(160)}",
            )
            out.trySend(line)
        }
        return scanned
    }

    /**
     * Our own mirrored log lines are excluded first: [AirClipLog] copies everything to logcat under the
     * tags `AirClip` and `AirClipSvc`, several of those lines discuss the clipboard, and a watcher that
     * answers its own output would spin forever. The tag is matched with its leading space and capitals,
     * which no lowercase package name can collide with.
     */
    private fun mentionsUs(line: String): Boolean =
        !line.contains(OWN_TAG) && line.contains(context.packageName)

    /**
     * The phrase rather than just the word: `ClipboardService` logs
     * `Denying clipboard access to <pkg>, application is not in focus …`, while a line that merely
     * *names* this app — an activity called `ClipboardRelayActivity`, say — must not be mistaken for a
     * change. Matching "clipboard access" keeps the wording drift between releases and ROMs while still
     * excluding our own class names, and [drain] prints the near misses so a ROM that words it some
     * third way can be spotted rather than guessed at.
     */
    private fun isSignal(line: String): Boolean =
        mentionsUs(line) && MARKERS.any { line.contains(it, ignoreCase = true) }

    private companion object {
        /** `-T 1` starts at the newest line: no timestamp formatting to get wrong. */
        val LIVE_COMMAND = listOf("logcat", "-v", "threadtime", "-T", "1", "*:W")
        val PROBE_COMMAND = listOf("logcat", "-d", "-v", "brief", "-t", "5")
        val DUMP_COMMAND = listOf("logcat", "-d", "-v", "threadtime", "-t", "1500")

        /** What the self-test pulls out of an unfiltered dump. */
        val INTERESTING = listOf("AirClipSvc", "ShizukuServer", "AndroidRuntime", "Shizuku")

        /**
         * What a refused clipboard read looks like in the log. `AppOpsManager`'s op name is included
         * because a few ROMs log the op rather than a sentence.
         */
        val MARKERS = listOf("clipboard access", "READ_CLIPBOARD_IN_BACKGROUND")

        const val SOURCE_CALLBACK = "系统回调"
        const val OWN_TAG = " AirClip"

        /** Enough to identify a differently worded denial; not enough to flood the buffer. */
        const val NEAR_MISS_LIMIT = 12

        const val IDLE_POLL_MS = 120L
        const val RESTART_DELAY_MS = 3_000L
        const val PROBE_TIMEOUT_MS = 3_000L
        const val DUMP_TIMEOUT_MS = 6_000L
    }
}
