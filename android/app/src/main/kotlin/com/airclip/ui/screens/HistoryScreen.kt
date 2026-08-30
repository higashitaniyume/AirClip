package com.airclip.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airclip.R
import com.airclip.core.clipboard.ClipKind
import com.airclip.data.HistoryEntry
import com.airclip.ui.MainViewModel
import com.airclip.ui.components.Hint
import com.airclip.ui.components.relativeTime
import com.airclip.ui.components.shortSize

/**
 * The last N clipboard items, newest first. Rows whose payload has been pruned stay visible but say
 * so — [HistoryEntry.isRestorable] is the difference between a row you can re-copy and a receipt.
 */
@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val entries by vm.history.collectAsState()

    if (entries.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Hint(stringResource(R.string.history_empty))
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            HistoryCard(
                entry = entry,
                onCopy = { vm.copyEntry(entry) },
                onResend = { vm.resendEntry(entry) },
                onDelete = { vm.deleteEntry(entry) },
            )
        }
    }
}

@Composable
private fun HistoryCard(
    entry: HistoryEntry,
    onCopy: () -> Unit,
    onResend: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val preview = when (entry.kind) {
        ClipKind.TEXT -> entry.preview()
        ClipKind.IMAGE -> stringResource(R.string.history_image, entry.width ?: 0, entry.height ?: 0)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (entry.kind == ClipKind.IMAGE) Icons.Filled.Image else Icons.Filled.Notes,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Hint(
                stringResource(
                    R.string.history_source,
                    entry.fromDeviceName ?: stringResource(R.string.history_from_local),
                    relativeTime(entry.timestamp),
                    shortSize(context, entry.byteSize),
                ),
            )

            if (!entry.isRestorable) Hint(stringResource(R.string.history_unavailable))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCopy, enabled = entry.isRestorable) {
                    Text(stringResource(R.string.history_copy))
                }
                TextButton(onClick = onResend, enabled = entry.isRestorable) {
                    Text(stringResource(R.string.history_resend))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text(stringResource(R.string.history_delete)) }
            }
        }
    }
}
