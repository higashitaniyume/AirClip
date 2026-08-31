package com.airclip.platform.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.airclip.core.diag.AirClipLog
import com.airclip.core.diag.LogTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuAvailability {
    /** The Shizuku manager app is not installed at all. */
    NOT_INSTALLED,

    /** Installed, but its service was never started (or died after a reboot). */
    NOT_RUNNING,

    /** Service reachable; the user has not granted AirClip access yet. */
    PERMISSION_REQUIRED,

    READY,
}

/**
 * Tracks whether the Shizuku service is reachable and authorised. Kept separate from
 * [ShizukuClipboardBackend] so the settings screen can explain *which* step is missing without
 * having a bound user service.
 */
class ShizukuGate(private val context: Context) {

    private val _availability = MutableStateFlow(ShizukuAvailability.NOT_INSTALLED)
    val availability: StateFlow<ShizukuAvailability> = _availability.asStateFlow()

    /**
     * Fired on every change (and once on the first probe). The runtime uses it to bind the user
     * service and start polling the moment authorisation arrives — without it, granting Shizuku while
     * the service is already running does nothing until the next restart.
     */
    @Volatile
    var onAvailabilityChanged: ((ShizukuAvailability) -> Unit)? = null

    private var attached = false
    private var reported = false

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        AirClipLog.i(LogTag.SHIZUKU, "收到 Shizuku binder")
        refresh()
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        AirClipLog.w(LogTag.SHIZUKU, "Shizuku binder 已失效（服务被停止或设备重启后未启动）")
        refresh()
    }

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val verdict = if (grantResult == PackageManager.PERMISSION_GRANTED) "同意" else "拒绝"
        AirClipLog.i(LogTag.SHIZUKU, "授权结果 requestCode=$requestCode → $verdict")
        refresh()
    }

    fun attach() {
        if (attached) return
        attached = true
        AirClipLog.d(LogTag.SHIZUKU, "注册 Shizuku 监听器")
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun detach() {
        if (!attached) return
        attached = false
        AirClipLog.d(LogTag.SHIZUKU, "注销 Shizuku 监听器")
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    fun refresh() {
        val alive = pinged()
        val next = when {
            !alive -> if (isManagerInstalled()) {
                ShizukuAvailability.NOT_RUNNING
            } else {
                ShizukuAvailability.NOT_INSTALLED
            }

            isGranted() -> ShizukuAvailability.READY
            else -> ShizukuAvailability.PERMISSION_REQUIRED
        }

        val previous = _availability.value
        _availability.value = next
        if (previous == next && reported) {
            AirClipLog.t(LogTag.SHIZUKU, "状态仍为 $next")
            return
        }
        reported = true
        AirClipLog.i(LogTag.SHIZUKU, "状态 → $next · ${probe()}")
        onAvailabilityChanged?.invoke(next)
    }

    fun isGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Shows Shizuku's own consent dialog. Pre-v11 managers instead rely on the
     * `moe.shizuku.manager.permission.API_V23` runtime permission, which the caller must request
     * from an Activity — [needsRuntimePermission] reports that case.
     */
    fun requestPermission(requestCode: Int = REQUEST_CODE) {
        if (!pinged()) {
            AirClipLog.w(LogTag.SHIZUKU, "无法请求授权：Shizuku 服务未运行，请先在 Shizuku 应用里启动服务")
            refresh()
            return
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            AirClipLog.w(LogTag.SHIZUKU, "旧版 Shizuku 没有授权弹窗，需在系统权限里授予 $RUNTIME_PERMISSION")
            return
        }
        if (runCatching { Shizuku.checkSelfPermission() }.getOrNull() == PackageManager.PERMISSION_GRANTED) {
            AirClipLog.i(LogTag.SHIZUKU, "已处于授权状态，无需再次请求")
        } else {
            AirClipLog.i(LogTag.SHIZUKU, "弹出 Shizuku 授权对话框 requestCode=$requestCode")
            runCatching { Shizuku.requestPermission(requestCode) }
                .onFailure { AirClipLog.e(LogTag.SHIZUKU, "requestPermission 调用失败", it) }
        }
        refresh()
    }

    fun needsRuntimePermission(): Boolean =
        runCatching { Shizuku.pingBinder() && Shizuku.isPreV11() }.getOrDefault(false)

    /** One line covering every question the self-test asks about this half of the plan. */
    fun probe(): String = buildString {
        val alive = pinged()
        append("binder=").append(if (alive) "已连接" else "不可用")
        if (alive) {
            append(" 服务版本=").append(text { Shizuku.getVersion().toString() })
            append(" 服务身份uid=").append(text { "${Shizuku.getUid()}${uidLabel()}" })
            append(" preV11=").append(text { Shizuku.isPreV11().toString() })
            append(" 本应用授权=").append(
                text {
                    when (Shizuku.checkSelfPermission()) {
                        PackageManager.PERMISSION_GRANTED -> "已授权"
                        else -> "未授权"
                    }
                },
            )
            append(" 可再次弹窗=").append(text { Shizuku.shouldShowRequestPermissionRationale().toString() })
        }
        append(" 管理器=").append(managerSummary())
    }

    private fun pinged(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Every one of these throws once the binder is gone, and a probe must never be the thing that dies. */
    private fun text(block: () -> String): String = runCatching(block).getOrDefault("查询失败")

    private fun uidLabel(): String = when (runCatching { Shizuku.getUid() }.getOrDefault(-1)) {
        0 -> "(root)"
        2000 -> "(shell)"
        else -> ""
    }

    @Suppress("DEPRECATION")
    private fun managerSummary(): String {
        val found = MANAGER_PACKAGES.mapNotNull { name ->
            runCatching {
                val info = context.packageManager.getPackageInfo(name, 0)
                "$name ${info.versionName}"
            }.getOrNull()
        }
        return if (found.isEmpty()) "未安装（使用 Sui 时没有管理器应用，属正常）" else found.joinToString("、")
    }

    private fun isManagerInstalled(): Boolean = MANAGER_PACKAGES.any { name ->
        runCatching { context.packageManager.getPackageInfo(name, 0) }.isSuccess
    }

    companion object {
        const val REQUEST_CODE = 0xA17C
        const val RUNTIME_PERMISSION = "moe.shizuku.manager.permission.API_V23"

        /** Shizuku's own package, plus Sui, which serves the same binder. */
        private val MANAGER_PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.redirect")
    }
}
