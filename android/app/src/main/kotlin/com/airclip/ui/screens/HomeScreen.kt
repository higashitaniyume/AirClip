package com.airclip.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.airclip.R
import com.airclip.platform.shizuku.ShizukuAvailability
import com.airclip.ui.MainViewModel
import com.airclip.ui.SystemAccess
import com.airclip.ui.components.CapabilityRow
import com.airclip.ui.components.Hint
import com.airclip.ui.components.SectionCard
import com.airclip.ui.components.SettingSwitch
import com.airclip.ui.components.requestTile
import com.airclip.ui.components.sourceLabelRes

/**
 * State, the one-tap send, and the checklist of background-read plans. The checklist is the point of
 * this screen: on Android 10+ nothing AirClip does in the background works until the user turns on
 * at least one of these, and none of them can be granted from inside the app.
 */
@Composable
fun HomeScreen(vm: MainViewModel, onPair: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val running by vm.isRunning.collectAsState()
    val paused by vm.isPaused.collectAsState()
    val peers by vm.peers.collectAsState()
    val status by vm.status.collectAsState()
    val capabilities by vm.capabilities.collectAsState()
    val fingerprint by vm.fingerprint.collectAsState()
    val shizuku by vm.shizuku.collectAsState()
    val lastSource by vm.lastReadSource.collectAsState()
    val address by vm.localAddress.collectAsState()

    // The result only matters as a refresh trigger: the service runs either way, the notification is
    // what disappears without the permission.
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.refresh() }
    var askedNotifications by remember { mutableStateOf(false) }

    val connected = peers.count { it.isConnected }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = stringResource(R.string.home_section_status)) {
            val state = when {
                !running -> stringResource(R.string.toast_service_off)
                paused -> stringResource(R.string.home_state_paused)
                connected > 0 -> stringResource(R.string.home_state_connected, connected)
                else -> stringResource(R.string.home_state_offline)
            }
            Text(state, style = MaterialTheme.typography.headlineSmall)
            if (running && status.isNotBlank()) Hint(status)
            Hint(
                stringResource(
                    R.string.home_local_device,
                    settings.deviceName,
                    address ?: "—",
                    settings.listenPort,
                ),
            )

            // Delegated properties cannot be smart-cast, hence the local copy.
            val print = fingerprint
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val paired = if (print != null) {
                    stringResource(R.string.home_encrypted, print)
                } else {
                    // There is no unencrypted mode to describe: without a key nothing connects at all.
                    stringResource(R.string.home_unpaired)
                }
                Hint(paired, modifier = Modifier.weight(1f))
                if (print == null) {
                    TextButton(onClick = onPair) { Text(stringResource(R.string.home_pair_action)) }
                }
            }

            SettingSwitch(
                label = stringResource(R.string.home_sync_switch),
                hint = stringResource(R.string.home_sync_switch_hint),
                checked = running,
                onCheckedChange = { enabled ->
                    // Ask before starting, so the very first ongoing notification is visible.
                    if (enabled &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !capabilities.notifications
                    ) {
                        askedNotifications = true
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.setServiceEnabled(enabled)
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { vm.sendNow() },
                    enabled = running && !paused,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.home_send_now))
                }
                if (running) {
                    OutlinedButton(onClick = { vm.togglePause() }) {
                        Text(stringResource(if (paused) R.string.home_resume else R.string.home_pause))
                    }
                }
            }

            lastSource?.let { source ->
                Hint(stringResource(R.string.home_last_source, stringResource(sourceLabelRes(source))))
            }
        }

        SectionCard(title = stringResource(R.string.home_capabilities)) {
            Hint(stringResource(R.string.home_capability_hint))

            CapabilityRow(
                label = stringResource(R.string.home_capability_ime),
                // Enabled is not enough: only the *selected* IME may read the clipboard.
                ready = capabilities.imeEnabled && capabilities.imeSelected,
                actionLabel = stringResource(
                    if (capabilities.imeEnabled) R.string.settings_pick_ime else R.string.settings_enable_ime,
                ),
                onAction = {
                    if (capabilities.imeEnabled) {
                        SystemAccess.showImePicker(context)
                    } else {
                        SystemAccess.openImeSettings(context)
                    }
                },
            )

            CapabilityRow(
                label = stringResource(R.string.home_capability_accessibility),
                ready = capabilities.accessibility,
                actionLabel = stringResource(R.string.settings_enable_accessibility),
                onAction = { SystemAccess.openAccessibilitySettings(context) },
            )

            // Which step is missing decides what the button does, so both are built together.
            var shizukuLabel: String? = null
            var shizukuAction: (() -> Unit)? = null
            when (shizuku) {
                ShizukuAvailability.READY -> Unit

                ShizukuAvailability.PERMISSION_REQUIRED -> {
                    shizukuLabel = stringResource(R.string.settings_shizuku_request)
                    shizukuAction = { vm.requestShizuku() }
                }

                else -> {
                    shizukuLabel = stringResource(R.string.home_open_settings)
                    shizukuAction = {
                        if (!SystemAccess.openShizuku(context)) {
                            vm.notify(context.getString(R.string.settings_shizuku_missing))
                        }
                    }
                }
            }
            CapabilityRow(
                label = stringResource(R.string.home_capability_shizuku),
                ready = shizuku == ShizukuAvailability.READY,
                actionLabel = shizukuLabel,
                onAction = shizukuAction,
            )

            CapabilityRow(
                label = stringResource(R.string.home_capability_overlay),
                ready = capabilities.overlay,
                actionLabel = stringResource(R.string.settings_enable_overlay),
                onAction = { SystemAccess.openOverlaySettings(context) },
            )

            CapabilityRow(
                label = stringResource(R.string.home_capability_tile),
                // Nothing in the platform reports whether a tile has been added, so no claim is made.
                ready = null,
                actionLabel = stringResource(R.string.settings_add_tile),
                onAction = { requestTile(context, vm::notify) },
            )
        }

        SectionCard(title = stringResource(R.string.home_section_reliability)) {
            Hint(stringResource(R.string.home_reliability_hint))

            CapabilityRow(
                label = stringResource(R.string.home_capability_notifications),
                ready = capabilities.notifications,
                actionLabel = stringResource(R.string.settings_enable_notifications),
                onAction = {
                    // The system dialog can only be raised once; after that the app settings page is
                    // the only place the switch still exists.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications) {
                        askedNotifications = true
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        SystemAccess.openNotificationSettings(context)
                    }
                },
            )

            CapabilityRow(
                label = stringResource(R.string.home_capability_battery),
                ready = capabilities.batteryUnrestricted,
                actionLabel = stringResource(R.string.settings_battery_optimization),
                onAction = { SystemAccess.openBatterySettings(context) },
            )
        }
    }
}
