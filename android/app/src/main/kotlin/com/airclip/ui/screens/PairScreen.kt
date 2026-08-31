package com.airclip.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.airclip.R
import com.airclip.ui.MainViewModel
import com.airclip.ui.components.Hint
import com.airclip.ui.components.QrCode
import com.airclip.ui.components.QrScanner
import com.airclip.ui.components.SectionCard

/**
 * Getting the same pairing secret onto both machines, three ways: let the PC scan this device's QR
 * code, scan the PC's, or type the code / link / `pass:` phrase by hand. Everything goes through the
 * key vault, so a new key takes effect on the running service without a restart.
 */
@Composable
fun PairScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fingerprint by vm.fingerprint.collectAsState()
    val uri by vm.pairingUri.collectAsState()
    val pairingCode by vm.pairingCode.collectAsState()

    var scanning by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var cameraGranted by remember { mutableStateOf(hasCamera(context)) }

    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        // Opening the viewfinder straight away: the request was a tap on 扫描, not a settings visit.
        if (granted) scanning = true else vm.notify(context.getString(R.string.pair_camera_denied))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(title = stringResource(R.string.pair_show_qr)) {
            Hint(stringResource(R.string.pair_qr_hint))

            // Delegated properties cannot be smart-cast, hence the local copies here and below.
            val link = uri
            val print = fingerprint
            val code = pairingCode
            if (link != null) {
                QrCode(link, modifier = Modifier.align(Alignment.CenterHorizontally))
                Hint(stringResource(R.string.pair_uri_hint))
                // Selectable, so the link can be shared without the copy button touching the
                // clipboard at all.
                SelectionContainer {
                    Text(link, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Hint(stringResource(R.string.pair_none))
            }

            // The code is what makes this work with a desktop that has no camera, which is most of
            // them; a passphrase pairing has one too, because it stretches to the same secret.
            code?.let {
                Hint(stringResource(R.string.pair_code_hint))
                SelectionContainer {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
            }

            print?.let {
                Text(stringResource(R.string.pair_fingerprint, it))
                // Only a warning once there is something to lose.
                Hint(stringResource(R.string.pair_regenerate_hint))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { vm.generateKey() }) {
                    Text(stringResource(if (print == null) R.string.pair_generate else R.string.pair_regenerate))
                }
                code?.let {
                    TextButton(onClick = { vm.copyPairingCode(it) }) {
                        Text(stringResource(R.string.pair_copy_code))
                    }
                }
                if (link != null) {
                    TextButton(onClick = { vm.copyPairingLink(link) }) {
                        Text(stringResource(R.string.pair_copy_uri))
                    }
                }
                if (print != null) {
                    TextButton(onClick = { vm.forgetKey() }) {
                        Text(stringResource(R.string.pair_forget))
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.pair_scan_qr)) {
            // `cameraGranted` is re-checked here because the permission can be revoked while the
            // viewfinder is open, and binding without it throws inside CameraX.
            if (scanning && cameraGranted) {
                QrScanner(
                    onDecoded = { text ->
                        scanning = false
                        vm.applyPairingText(text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
                Hint(stringResource(R.string.pair_scan_prompt))
                TextButton(onClick = { scanning = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            } else {
                Button(
                    onClick = {
                        if (cameraGranted) scanning = true else askCamera.launch(Manifest.permission.CAMERA)
                    },
                ) {
                    Text(
                        stringResource(
                            if (cameraGranted) R.string.pair_scan_qr else R.string.pair_grant_camera,
                        ),
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.pair_manual)) {
            Hint(stringResource(R.string.pair_manual_note))
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text(stringResource(R.string.pair_manual_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    vm.applyPairingText(manual)
                    manual = ""
                },
                // A blank field would be reported as an invalid code, which reads like a bug.
                enabled = manual.isNotBlank(),
            ) {
                Text(stringResource(R.string.pair_apply))
            }
        }
    }
}

private fun hasCamera(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
