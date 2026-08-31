package com.airclip.platform.shizuku

import com.airclip.core.diag.AirClipLog
import com.airclip.core.diag.LogTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Walks every Shizuku path one step at a time and writes what it finds to [AirClipLog], stopping at
 * the first step that fails.
 *
 * "Shizuku is granted but the clipboard is never monitored" has at least seven distinct causes — the
 * service not running, authorisation not actually granted, the platform blocking the hidden-API lookup
 * inside the app process, the helper process failing to launch, the ROM having no standard clipboard
 * service, the platform refusing a shell-UID read, and the two app-side switches (同步服务 / Shizuku
 * 轮询监听) simply being off. Each of them looks identical from the settings screen, so the point of
 * this class is to say which one it is, in one tap.
 *
 * The order mirrors [ShizukuClipboardBackend]: the in-process direct path first, and the spawned
 * helper only if that one cannot answer. A device where the direct path works never needs the helper,
 * and saying so is more useful than reporting a failure the user does not have to care about.
 *
 * The log watcher is checked after both, and separately, because it is not a read path: it reports
 * *when* the clipboard changed, which is the half that survives on devices where neither privileged
 * read does. Its `logcat` is also how the helper process's own crash gets into this app's log instead
 * of only onto a PC — the one thing the previous verdict had to send the user away for.
 */
object ShizukuDoctor {

    private const val BIND_TIMEOUT_MS = 8_000L

    /** Kept short on purpose: the app's own buffer is 1200 lines and the user still has to read it. */
    private const val REPORT_LINES = 60

    suspend fun run(
        gate: ShizukuGate,
        backend: ShizukuClipboardBackend,
        serviceRunning: Boolean,
        pollingEnabled: Boolean,
        pollMillis: Long,
        overlayGranted: Boolean,
    ): String {
        AirClipLog.section(LogTag.DOCTOR, "Shizuku 自检开始")

        gate.refresh()
        AirClipLog.i(LogTag.DOCTOR, "1/6 探测 Shizuku · ${gate.probe()}")
        when (gate.availability.value) {
            ShizukuAvailability.NOT_INSTALLED -> return finish(
                "没有检测到 Shizuku 或 Sui。请先安装 Shizuku 并启动它的服务。",
            )

            ShizukuAvailability.NOT_RUNNING -> return finish(
                "Shizuku 已安装，但服务没有运行。打开 Shizuku 应用，用无线调试或 root 启动服务；" +
                    "手机每次重启后都需要重新启动一次。",
            )

            ShizukuAvailability.PERMISSION_REQUIRED -> return finish(
                "Shizuku 服务在运行，但还没有给 AirClip 授权。点「请求 Shizuku 授权」，并在弹窗里选择允许。" +
                    if (gate.needsRuntimePermission()) "（检测到旧版 Shizuku：需要在系统权限设置里手动授予 API 权限）" else "",
            )

            ShizukuAvailability.READY -> AirClipLog.i(LogTag.DOCTOR, "1/6 通过：Shizuku 已授权")
        }

        // The direct path is the whole pipeline in one binder call, so if it answers, nothing else
        // matters — and the two switches at the end are then the only thing left that can be wrong.
        val direct = probeDirect(backend)
        val outcome = if (direct is Probe.Ok) direct else probeHelper(backend)

        // Always checked, whichever way the read went: it is the trigger, not a reader. The system log
        // is only transcribed when a read path failed — that is when the helper's own crash matters, and
        // sixty lines of ROM chatter on a healthy device would only bury the verdict.
        val watcher = probeWatcher(backend, dump = outcome is Probe.Failed)

        return when (outcome) {
            is Probe.Failed -> finish(outcome.conclusion + watcherAdvice(watcher, overlayGranted))
            is Probe.Ok -> switches(
                serviceRunning = serviceRunning,
                pollingEnabled = pollingEnabled,
                pollMillis = pollMillis,
                path = if (direct is Probe.Ok) "直连（不需要辅助进程）" else "辅助进程",
                empty = outcome.text.isEmpty(),
                watcher = watcher,
            )
        }
    }

    /**
     * The third path, and the one borrowed from `kdeconnect-android-shizuku`: shell may read the system
     * log, and the platform writes a line every time it refuses this app the clipboard.
     *
     * Returns whether the app can read the log at all. Cheap — one `logcat -d -t 5`.
     *
     * @param dump also transcribe the interesting part of the system log into the app's own buffer.
     */
    private suspend fun probeWatcher(backend: ShizukuClipboardBackend, dump: Boolean): Boolean {
        AirClipLog.i(LogTag.DOCTOR, "附加检查 · 日志监听（只负责发现变化，读取仍走上面的路）…")
        AirClipLog.i(LogTag.DOCTOR, "  ${backend.logcat.describe()}")

        if (!backend.logcat.isUsable()) {
            AirClipLog.w(
                LogTag.DOCTOR,
                "  不可用：${ShizukuShell.failure ?: "logcat 没有输出"}。" +
                    "Shizuku 14 计划移除 newProcess，若日后失效，这里会是第一处报出来的地方",
            )
            return false
        }
        AirClipLog.i(LogTag.DOCTOR, "  可用：能以 shell 身份读系统日志，剪贴板每次变化都会被立刻发现")
        if (dump) dumpSystemLog(backend)
        return true
    }

    /**
     * Copies the interesting part of the system log into the app's own buffer.
     *
     * This is the answer to the one question the self-test could not previously answer on the phone:
     * when the helper process dies before it can call back, its stack trace goes to `logcat` and
     * nowhere else. Now it goes here too.
     */
    private suspend fun dumpSystemLog(backend: ShizukuClipboardBackend) {
        val lines = backend.logcat.report()
        when {
            lines == null -> AirClipLog.w(LogTag.DOCTOR, "  读取系统日志失败：${ShizukuShell.failure}")
            lines.isEmpty() -> AirClipLog.i(
                LogTag.DOCTOR,
                "  系统日志里没有 Shizuku / 辅助进程 / 崩溃相关的记录（最近 1500 行内）",
            )

            else -> {
                AirClipLog.section(LogTag.DOCTOR, "系统日志摘录（命中 ${lines.size} 行，取最后 $REPORT_LINES 行）")
                lines.takeLast(REPORT_LINES).forEach { AirClipLog.i(LogTag.SHIZUKU_SVC, it) }
            }
        }
    }

    /**
     * What the third path adds to a verdict where neither read path works: it cannot read the
     * clipboard, but it can say *when* to try — and a moment of window focus turns that into a read.
     */
    private fun watcherAdvice(usable: Boolean, overlayGranted: Boolean): String = when {
        !usable -> "日志监听这条路也不通，所以后台完全发现不了剪贴板变化，" +
            "剩下的可靠办法是启用 AirClip 的输入法或无障碍服务。"

        overlayGranted -> "不过日志监听是通的：变化会被立刻发现，随后 AirClip 会瞬间弹出一个 1×1 窗口取得焦点再读，" +
            "而「显示在其他应用上层」权限已经授予——现在复制一段文字试试，应该能同步。"

        else -> "不过日志监听是通的：变化会被立刻发现，但读取还差一个焦点。" +
            "请授予「显示在其他应用上层」（悬浮窗）权限，之后每次检测到变化都会瞬间弹一个 1×1 窗口完成读取。"
    }

    /**
     * Steps 2 and 3: does the in-process path resolve, and does it read anything?
     *
     * [Probe.Ok] carries the text — possibly empty, which still means the path works. A failure here
     * carries no conclusion: the helper is still worth trying, and it gets to write the verdict.
     */
    private suspend fun probeDirect(backend: ShizukuClipboardBackend): Probe {
        val direct = backend.direct
        AirClipLog.i(LogTag.DOCTOR, "2/6 解析直连（借 Shizuku 身份，不启动辅助进程）…")
        withContext(Dispatchers.IO) { direct.describe() }
            .lines()
            .forEach { AirClipLog.i(LogTag.DOCTOR, "  $it".trimEnd()) }
        backend.refreshDiagnostics()

        if (!withContext(Dispatchers.IO) { direct.isUsable() }) {
            AirClipLog.w(
                LogTag.DOCTOR,
                "2/6 未通过：${direct.failure() ?: "直连拿不到 IClipboard"}。" +
                    "这台系统大概屏蔽了应用进程里的隐藏 API，改走辅助进程",
            )
            return Probe.Failed()
        }
        AirClipLog.i(LogTag.DOCTOR, "2/6 通过：直连已解析到剪贴板接口")

        AirClipLog.i(LogTag.DOCTOR, "3/6 用直连读取剪贴板…")
        val text = withContext(Dispatchers.IO) { direct.text() }
        if (text == null) {
            AirClipLog.w(LogTag.DOCTOR, "3/6 未通过：直连接口可用却读不到内容，改走辅助进程")
            return Probe.Failed()
        }
        AirClipLog.i(LogTag.DOCTOR, "3/6 通过：直连读到 ${AirClipLog.redact(text)}")
        return Probe.Ok(text)
    }

    /**
     * Steps 4 and 5: the fallback path — and the one that fails silently, so its verdict has to spell
     * out what the user can actually do about it.
     */
    private suspend fun probeHelper(backend: ShizukuClipboardBackend): Probe {
        AirClipLog.i(LogTag.DOCTOR, "4/6 启动辅助进程…")
        backend.bind(force = true)
        val connected = withTimeoutOrNull(BIND_TIMEOUT_MS) { backend.connected.first { it } } != null
        if (!connected) {
            backend.drainRemoteLog()
            val record = backend.describeServerRecord()
            AirClipLog.w(LogTag.DOCTOR, "4/6 未通过 · Shizuku 服务器侧：$record")
            return Probe.Failed(
                "两条读取路都不通：直连被系统挡在隐藏 API 之外，辅助进程也在 ${BIND_TIMEOUT_MS / 1000} 秒内" +
                    "没有任何回调——bindUserService 既不抛异常也不回调，这是 Shizuku 的盲区。" +
                    "服务器侧状态：$record。可以试两件事：① 打开 Shizuku 应用重启一次服务，" +
                    "残留的旧服务记录会被一起清掉；② 如果这台机器是用 Android Studio 直接 Run 装的，" +
                    "请勾选 Run/Debug configurations 里的「Always install with package manager」再重装，" +
                    "否则 shell 进程加载到的是旧 APK。辅助进程自己的崩溃已经抄进本机日志（筛选 Shizuku/svc 那一档），" +
                    "不必再连电脑看 adb logcat。",
            )
        }
        AirClipLog.i(LogTag.DOCTOR, "4/6 通过：辅助进程已连接")

        AirClipLog.i(LogTag.DOCTOR, "5/6 询问辅助进程解析到了什么…")
        val described = backend.describe()
        backend.drainRemoteLog()
        when {
            described == null -> return Probe.Failed("辅助进程连上了却不响应调用，日志里有超时或异常的详情。")

            described.contains("未解析") -> return Probe.Failed(
                "辅助进程拿不到系统剪贴板服务。这台 ROM 大概改动或移除了标准的 clipboard 服务，" +
                    "所以两条特权读取路都指望不上。",
            )

            described.contains("缺失") -> return Probe.Failed(
                "找到了剪贴板服务，但没匹配到 getPrimaryClip/setPrimaryClip 的方法签名。" +
                    "请把日志里 describeBackend 那几行贴出来。",
            )

            else -> AirClipLog.i(LogTag.DOCTOR, "5/6 通过：辅助进程可用")
        }

        val text = backend.helperText()
        backend.drainRemoteLog()
        if (text == null) {
            return Probe.Failed(
                "辅助进程活着，但读不到剪贴板。若此刻剪贴板确实有内容，就是系统拒绝了 shell 身份的读取——" +
                    "日志里 Shizuku/svc 那几行会写明是 SecurityException 还是静默返回 null。" +
                    "若剪贴板本来是空的，请先复制一段文字再自检一次。",
            )
        }
        AirClipLog.i(LogTag.DOCTOR, "5/6 辅助进程读到 ${AirClipLog.redact(text)}")
        return Probe.Ok(text)
    }

    /**
     * Step 6: the two app-side switches. Reached only when a privileged read actually worked, so
     * everything it can say is about the app's own state rather than the platform's.
     */
    private fun switches(
        serviceRunning: Boolean,
        pollingEnabled: Boolean,
        pollMillis: Long,
        path: String,
        empty: Boolean,
        watcher: Boolean,
    ): String {
        AirClipLog.i(
            LogTag.DOCTOR,
            "6/6 检查开关 · 读取路径=$path 同步服务=${if (serviceRunning) "已开启" else "未开启"} " +
                "Shizuku轮询=${if (pollingEnabled) "已开启" else "未开启"} 间隔=${pollMillis}ms " +
                "日志监听=${if (watcher) "可用" else "不可用"}",
        )
        return when {
            !serviceRunning -> finish(
                "$path 读取是通的，但「同步服务」没有开启，所以没有任何东西在监听剪贴板。" +
                    "回到首页打开「同步服务」开关。",
            )

            // The watcher is event-driven and needs no switch, so with it up the poll interval is only
            // a safety net — saying "this is why nothing happens" would now be false.
            !pollingEnabled -> finish(
                if (watcher) {
                    "$path 读取是通的，「Shizuku 轮询监听」虽然关着，但日志监听已经在负责发现变化，" +
                        "复制后应该就会同步。想更保险的话可以把轮询也打开。"
                } else {
                    "$path 读取是通的，但「Shizuku 轮询监听」是关闭的，日志监听也不可用——" +
                        "没有任何东西会去发现剪贴板变化。到设置→后台读取里把轮询打开。"
                },
            )

            empty -> finish(
                "$path 读取是通的，开关也都开着，只是此刻剪贴板是空的。" +
                    "先复制一段文字，再运行一次自检就能看到完整结果。",
            )

            else -> finish(
                "全部通过：$path 可以读到剪贴板，轮询已开启（间隔 ${pollMillis}ms）" +
                    (if (watcher) "，日志监听也在工作（复制的瞬间就会上报）。" else "。") +
                    "如果内容仍然没到电脑，问题在配对或网络那一侧，请看设备页的连接状态。",
            )
        }
    }

    private fun finish(conclusion: String): String {
        AirClipLog.w(LogTag.DOCTOR, "结论：$conclusion")
        AirClipLog.section(LogTag.DOCTOR, "Shizuku 自检结束")
        return conclusion
    }

    /** One path's outcome. [Failed.conclusion] is only filled in by the last path standing. */
    private sealed interface Probe {
        data class Ok(val text: String) : Probe
        data class Failed(val conclusion: String = "") : Probe
    }
}
