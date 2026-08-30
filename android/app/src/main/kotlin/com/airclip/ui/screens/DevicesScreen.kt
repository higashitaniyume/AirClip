package com.airclip.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.airclip.R
import com.airclip.core.net.SyncPeer
import com.airclip.ui.MainViewModel
import com.airclip.ui.components.Hint
import com.airclip.ui.components.SectionCard
import com.airclip.ui.components.platformLabel

/**
 * What mDNS found. Discovery and connection are separate states on purpose: a device can be visible
 * and still refuse to talk (mismatched key, blocked port), and the difference is the first thing
 * worth showing when sync "doesn't work".
 */
@Composable
fun DevicesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val peers by vm.peers.collectAsState()
    val settings by vm.settings.collectAsState()
    val address by vm.localAddress.collectAsState()
    val fingerprint by vm.fingerprint.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = stringResource(R.string.devices_self)) {
                Hint(
                    stringResource(
                        R.string.home_local_device,
                        settings.deviceName,
                        address ?: "—",
                        settings.listenPort,
                    ),
                )
                fingerprint?.let { print -> Hint(stringResource(R.string.pair_fingerprint, print)) }
            }
        }

        if (peers.isEmpty()) {
            item {
                SectionCard(title = stringResource(R.string.devices_discovered)) {
                    Hint(stringResource(R.string.devices_empty))
                }
            }
        } else {
            items(peers, key = { it.deviceId }) { peer ->
                PeerCard(
                    peer = peer,
                    localFingerprint = fingerprint,
                    onConnect = { vm.connect(peer) },
                    onDisconnect = { vm.disconnect(peer) },
                )
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: SyncPeer,
    localFingerprint: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    SectionCard(title = peer.deviceName.ifBlank { peer.deviceId }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Hint("${platformLabel(peer.platform)} · ${peer.endpoint}")
                Hint(statusLine(peer))
            }
            if (peer.isConnected) {
                TextButton(onClick = onDisconnect) { Text(stringResource(R.string.devices_disconnect)) }
            } else {
                Button(onClick = onConnect) { Text(stringResource(R.string.devices_connect)) }
            }
        }

        // A TXT fingerprint that differs from ours means the two sides derived different AES keys;
        // the socket will connect and every frame will then fail to open.
        val remote = peer.remoteFingerprint
        if (remote != null && localFingerprint != null && !remote.equals(localFingerprint, ignoreCase = true)) {
            Text(
                text = stringResource(R.string.devices_fingerprint_mismatch),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 已连接 / 已发现, then how the link is protected, then the last ping's round trip. */
@Composable
private fun statusLine(peer: SyncPeer): String = buildList {
    add(stringResource(if (peer.isConnected) R.string.devices_connected else R.string.devices_discovered))
    if (peer.isConnected) {
        add(stringResource(if (peer.isEncrypted) R.string.devices_paired else R.string.devices_plaintext))
    }
    peer.roundTripMillis?.let { rtt -> add(stringResource(R.string.devices_rtt, rtt)) }
}.joinToString(" · ")
