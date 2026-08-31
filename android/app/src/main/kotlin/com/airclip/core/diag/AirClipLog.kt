package com.airclip.core.diag

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

internal fun formatLogTime(millis: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(millis))

enum class LogLevel(val marker: String, val priority: Int) {
    /** Per-poll noise. Kept out of the buffer unless the user asks for it. */
    TRACE("T", Log.VERBOSE),
    DEBUG("D", Log.DEBUG),
    INFO("I", Log.INFO),
    WARN("W", Log.WARN),
    ERROR("E", Log.ERROR),
}

/** Where a line came from. Free-form, but these are the tags the diagnostics screen filters by. */
object LogTag {
    const val APP = "App"
    const val SHIZUKU = "Shizuku"

    /** Lines pulled out of the Shizuku helper process, which has its own buffer. */
    const val SHIZUKU_SVC = "Shizuku/svc"

    /** The in-process path that borrows Shizuku's identity without spawning a helper. */
    const val SHIZUKU_DIRECT = "Shizuku/直连"

    /** The `logcat` watcher: shell reads the system log, and a denial line is a change notification. */
    const val SHIZUKU_LOG = "Shizuku/日志"
    const val CLIPBOARD = "Clipboard"
    const val RUNTIME = "Runtime"
    const val DOCTOR = "自检"
}

data class LogEntry(
    val seq: Long,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    val time: String get() = formatLogTime(timestamp)

    /** One line, identical in the viewer and in an exported dump. */
    fun line(): String = "$time ${level.marker} $tag: $message"
}

/**
 * In-memory ring buffer behind the 诊断日志 screen, mirrored to Logcat under the tag `AirClip`.
 *
 * A process-wide object rather than something owned by the runtime: it is written from binder
 * threads, from the Shizuku poll loop, from services and from the UI, and all of those have to end up
 * in the one buffer the user reads. Every method is safe to call from any thread.
 */
object AirClipLog {

    /** Roughly an hour of Shizuku polling at the default interval. */
    const val CAPACITY = 1200

    private const val LOGCAT_TAG = "AirClip"

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private var nextSeq = 1L

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /**
     * Whether clipboard text may appear in the log. Off by default: a diagnostics log is meant to be
     * pasted into a bug report, and the whole point of this app is that the clipboard holds whatever
     * the user just copied. Lengths and hashes are always logged, which is what the read path
     * questions actually turn on.
     */
    private val _includeContent = MutableStateFlow(false)
    val includeContentState: StateFlow<Boolean> = _includeContent.asStateFlow()

    var includeContent: Boolean
        get() = _includeContent.value
        set(value) {
            if (_includeContent.value == value) return
            _includeContent.value = value
            i(LogTag.APP, if (value) "日志将记录剪贴板内容（分享前请留意隐私）" else "日志不再记录剪贴板内容")
        }

    /** Whether [t] lines are kept. Off by default; the poll loop is a line per second. */
    private val _keepTrace = MutableStateFlow(false)
    val keepTraceState: StateFlow<Boolean> = _keepTrace.asStateFlow()

    var keepTrace: Boolean
        get() = _keepTrace.value
        set(value) {
            if (_keepTrace.value == value) return
            _keepTrace.value = value
            i(LogTag.APP, if (value) "已开启详细轮询日志" else "已关闭详细轮询日志")
        }

    fun t(tag: String, message: String) = add(LogLevel.TRACE, tag, message, null)

    fun d(tag: String, message: String) = add(LogLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = add(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = add(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = add(LogLevel.ERROR, tag, message, error)

    /** A blank-line-and-title separator, so a self-test run is easy to find in a long buffer. */
    fun section(tag: String, title: String) = add(LogLevel.INFO, tag, "──── $title ────", null)

    /**
     * Clipboard text rendered for the log: the length always, the value only when the user opted in
     * via [includeContent].
     */
    fun redact(value: String?): String = when {
        value == null -> "null"
        value.isEmpty() -> "空"
        !includeContent -> "len=${value.length}"
        else -> "len=${value.length} «${value.replace('\n', '⏎').take(64)}»"
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
        i(LogTag.APP, "日志已清空")
    }

    fun snapshot(): List<LogEntry> = _entries.value

    /** Plain text for the clipboard, a share sheet or a file. [limit] keeps a share intent bindable. */
    fun dump(header: String? = null, limit: Int = CAPACITY): String {
        val lines = snapshot().let { if (it.size > limit) it.subList(it.size - limit, it.size) else it }
        return buildString {
            header?.let { append(it).append('\n') }
            lines.forEach { append(it.line()).append('\n') }
        }
    }

    /** `IllegalStateException: …` plus the root cause, which for reflection is the interesting half. */
    fun describe(error: Throwable): String {
        val root = rootCause(error)
        val head = "${error.javaClass.simpleName}: ${error.message}"
        return if (root === error) head else "$head ← ${root.javaClass.simpleName}: ${root.message}"
    }

    private fun add(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (level == LogLevel.TRACE && !keepTrace) return

        val text = if (error == null) message else "$message — ${describe(error)}"
        Log.println(level.priority, LOGCAT_TAG, "[$tag] $text")

        synchronized(lock) {
            buffer.addLast(LogEntry(nextSeq++, System.currentTimeMillis(), level, tag, text))
            while (buffer.size > CAPACITY) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
    }

    /** Bounded: a cause chain is acyclic by contract, but a corrupt one must not spin here. */
    private fun rootCause(error: Throwable): Throwable {
        var current = error
        var guard = 0
        while (guard++ < 8) {
            val next = current.cause ?: break
            if (next === current) break
            current = next
        }
        return current
    }
}
