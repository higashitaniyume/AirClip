package com.airclip.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.airclip.R
import com.airclip.core.protocol.ProtocolConstants
import com.airclip.platform.shizuku.ShizukuAvailability
import com.airclip.ui.MainViewModel
import com.airclip.ui.SystemAccess
import com.airclip.ui.components.Hint
import com.airclip.ui.components.SectionCard
import com.airclip.ui.components.SettingField
import com.airclip.ui.components.SettingSwitch
import com.airclip.ui.components.requestTile

/**
 * Every setting, grouped the way the user has to think about them. Values go straight into the
 * store, which normalises and clamps them — so a typed port of `1` comes back as the default rather
 * than being rejected with an error the field would have to explain.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onPair: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val shizuku by vm.shizuku.collectAsState()
    val plaintextVault by vm.usesPlaintextVault.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = stringResource(R.string.settings_section_identity)) {
            SettingField(
                label = stringResource(R.string.settings_device_name),
                value = settings.deviceName,
                onCommit = { name -> vm.update { it.copy(deviceName = name) } },
            )
            IntField(
                label = stringResource(R.string.settings_port),
                value = settings.listenPort,
                onCommit = { port -> vm.update { it.copy(listenPort = port) } },
            )
            SettingField(
                label = stringResource(R.string.settings_service_type),
                value = settings.serviceName,
                hint = stringResource(R.string.settings_service_type_hint),
                onCommit = { type -> vm.update { it.copy(serviceName = type) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_start_on_boot),
                checked = settings.startOnBoot,
                onCheckedChange = { on -> vm.update { it.copy(startOnBoot = on) } },
            )
        }

        SectionCard(title = stringResource(R.string.settings_section_content)) {
            SettingSwitch(
                label = stringResource(R.string.settings_sync_images),
                checked = settings.syncImages,
                onCheckedChange = { on -> vm.update { it.copy(syncImages = on) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_honor_sensitive),
                hint = stringResource(R.string.settings_honor_sensitive_hint),
                checked = settings.honorSensitiveMarkers,
                onCheckedChange = { on -> vm.update { it.copy(honorSensitiveMarkers = on) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_auto_apply),
                hint = stringResource(R.string.settings_auto_apply_hint),
                checked = settings.autoApplyRemote,
                onCheckedChange = { on -> vm.update { it.copy(autoApplyRemote = on) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_keep_history),
                checked = settings.keepHistory,
                onCheckedChange = { on -> vm.update { it.copy(keepHistory = on) } },
            )
            IntField(
                label = stringResource(R.string.settings_history_limit),
                value = settings.historyLimit,
                onCommit = { limit -> vm.update { it.copy(historyLimit = limit) } },
            )
            IntField(
                label = stringResource(R.string.settings_max_text_kb),
                value = settings.maxTextKb,
                onCommit = { kb -> vm.update { it.copy(maxTextKb = kb) } },
            )
            IntField(
                label = stringResource(R.string.settings_max_image_kb),
                value = settings.maxImageKb,
                onCommit = { kb -> vm.update { it.copy(maxImageKb = kb) } },
            )
            IntField(
                label = stringResource(R.string.settings_debounce_ms),
                value = settings.debounceMs,
                onCommit = { ms -> vm.update { it.copy(debounceMs = ms) } },
            )
        }

        SectionCard(title = stringResource(R.string.settings_section_notifications)) {
            SettingSwitch(
                label = stringResource(R.string.settings_notify_on_receive),
                checked = settings.notifyOnReceive,
                onCheckedChange = { on -> vm.update { it.copy(notifyOnReceive = on) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_toast_on_receive),
                checked = settings.toastOnReceive,
                onCheckedChange = { on -> vm.update { it.copy(toastOnReceive = on) } },
            )
            ActionRow(
                label = stringResource(R.string.home_capability_tile),
                hint = stringResource(R.string.settings_add_tile_hint),
                action = stringResource(R.string.settings_add_tile),
                onClick = { requestTile(context, vm::notify) },
            )
        }

        SectionCard(title = stringResource(R.string.settings_section_security)) {
            SettingSwitch(
                label = stringResource(R.string.settings_encryption_required),
                hint = stringResource(R.string.settings_encryption_hint),
                checked = settings.requireEncryption,
                onCheckedChange = { on -> vm.update { it.copy(requireEncryption = on) } },
            )
            ActionRow(
                label = stringResource(R.string.settings_pair_entry),
                hint = stringResource(R.string.pair_qr_hint),
                action = stringResource(R.string.pair_title),
                onClick = onPair,
            )
            // Not a setting: a hardware fact the user is entitled to know before trusting the vault.
            if (plaintextVault) {
                Text(
                    text = stringResource(R.string.settings_keystore_fallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SectionCard(title = stringResource(R.string.settings_section_backends)) {
            SettingSwitch(
                label = stringResource(R.string.settings_overlay_assist),
                hint = stringResource(R.string.settings_overlay_assist_hint),
                checked = settings.overlayAssist,
                onCheckedChange = { on -> vm.update { it.copy(overlayAssist = on) } },
            )
            SettingSwitch(
                label = stringResource(R.string.settings_shizuku_polling),
                hint = stringResource(R.string.settings_shizuku_polling_hint),
                checked = settings.shizukuPolling,
                onCheckedChange = { on -> vm.update { it.copy(shizukuPolling = on) } },
            )
            SettingField(
                label = stringResource(R.string.settings_shizuku_poll_ms),
                value = settings.shizukuPollMillis.toString(),
                keyboardType = KeyboardType.Number,
                onCommit = { text ->
                    text.toLongOrNull()?.let { ms -> vm.update { it.copy(shizukuPollMillis = ms) } }
                },
            )
            ActionRow(
                label = stringResource(R.string.home_capability_shizuku),
                hint = when (shizuku) {
                    ShizukuAvailability.READY -> stringResource(R.string.settings_shizuku_granted)
                    ShizukuAvailability.PERMISSION_REQUIRED -> stringResource(R.string.settings_shizuku_pending)
                    else -> stringResource(R.string.settings_shizuku_missing)
                },
                action = when (shizuku) {
                    ShizukuAvailability.READY -> null
                    ShizukuAvailability.PERMISSION_REQUIRED -> stringResource(R.string.settings_shizuku_request)
                    else -> stringResource(R.string.home_open_settings)
                },
                onClick = {
                    if (shizuku == ShizukuAvailability.PERMISSION_REQUIRED) {
                        vm.requestShizuku()
                    } else if (!SystemAccess.openShizuku(context)) {
                        vm.notify(context.getString(R.string.settings_shizuku_missing))
                    }
                },
            )
        }

        SectionCard(title = stringResource(R.string.settings_section_about)) {
            Hint(
                stringResource(
                    R.string.settings_about_protocol,
                    ProtocolConstants.VERSION,
                    settings.listenPort,
                    settings.deviceId,
                ),
            )
        }
    }
}

/** A numeric setting. Anything unparseable is simply not committed, so the field snaps back. */
@Composable
private fun IntField(label: String, value: Int, onCommit: (Int) -> Unit) {
    SettingField(
        label = label,
        value = value.toString(),
        keyboardType = KeyboardType.Number,
        onCommit = { text -> text.toIntOrNull()?.let(onCommit) },
    )
}

/** A row that leads somewhere else — a system screen, or the pair screen. */
@Composable
private fun ActionRow(label: String, hint: String, action: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Hint(hint)
        }
        if (action != null) {
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}
