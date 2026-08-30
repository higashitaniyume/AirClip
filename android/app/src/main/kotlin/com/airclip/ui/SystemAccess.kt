package com.airclip.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.airclip.R
import com.airclip.service.AirClipAccessibilityService
import com.airclip.service.ClipboardTileService

/**
 * Which of the background-read plans the system currently allows. Nothing here can be granted by
 * the app itself — every one of them is a switch in a system settings screen, which is why the home
 * screen shows them as a checklist rather than asking for permissions.
 */
data class Capabilities(
    val imeEnabled: Boolean = false,
    val imeSelected: Boolean = false,
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val notifications: Boolean = false,
    val batteryUnrestricted: Boolean = false,
)

/** Probes and system-settings shortcuts. Every launch is best-effort: OEMs remove these screens. */
object SystemAccess {

    fun probe(context: Context): Capabilities = Capabilities(
        imeEnabled = isImeEnabled(context),
        imeSelected = isImeSelected(context),
        accessibility = isAccessibilityEnabled(context),
        overlay = Settings.canDrawOverlays(context),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        batteryUnrestricted = isBatteryUnrestricted(context),
    )

    private fun isImeEnabled(context: Context): Boolean = runCatching {
        context.getSystemService<InputMethodManager>()
            ?.enabledInputMethodList
            ?.any { it.packageName == context.packageName } == true
    }.getOrDefault(false)

    /** The selected IME is the one component the platform lets read the clipboard unconditionally. */
    private fun isImeSelected(context: Context): Boolean = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("${context.packageName}/") == true
    }.getOrDefault(false)

    private fun isAccessibilityEnabled(context: Context): Boolean = runCatching {
        val target = AirClipAccessibilityService::class.java.name
        context.getSystemService<AccessibilityManager>()
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { info -> info.id.contains(target) } == true
    }.getOrDefault(false)

    private fun isBatteryUnrestricted(context: Context): Boolean = runCatching {
        context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    fun openImeSettings(context: Context) = launch(context, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))

    /** The picker is the only way to *select* an IME; enabling it is not enough. */
    fun showImePicker(context: Context) {
        runCatching { context.getSystemService<InputMethodManager>()?.showInputMethodPicker() }
    }

    fun openAccessibilitySettings(context: Context) =
        launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openOverlaySettings(context: Context) = launch(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)),
    )

    fun openNotificationSettings(context: Context) = launch(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    /**
     * The app-specific dialog needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which is
     * policy-restricted; the system list needs nothing and lands the user in the same place.
     */
    fun openBatterySettings(context: Context) =
        launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    /** `false` when neither Shizuku nor Sui is installed, so the caller can say so. */
    fun openShizuku(context: Context): Boolean {
        val intent = SHIZUKU_PACKAGES.firstNotNullOfOrNull { name ->
            context.packageManager.getLaunchIntentForPackage(name)
        } ?: return false
        launch(context, intent)
        return true
    }

    /** Android 13+ can ask the shade to add the tile; older versions have to be told to do it by hand. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun requestAddTile(context: Context, onResult: (Boolean) -> Unit) {
        val manager = context.getSystemService<StatusBarManager>() ?: return onResult(false)
        runCatching {
            manager.requestAddTileService(
                ComponentName(context, ClipboardTileService::class.java),
                context.getString(R.string.tile_label),
                Icon.createWithResource(context, R.drawable.ic_tile),
                context.mainExecutor,
            ) { result ->
                onResult(
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
                )
            }
        }.onFailure { onResult(false) }
    }

    private fun launch(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private val SHIZUKU_PACKAGES = listOf("moe.shizuku.privileged.api", "moe.shizuku.redirect")
}
