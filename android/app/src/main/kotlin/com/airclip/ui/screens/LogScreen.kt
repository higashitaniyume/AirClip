package com.airclip.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.airclip.R
import com.airclip.core.diag.LogEntry
import com.airclip.core.diag.LogLevel
import com.airclip.ui.MainViewModel
import com.airclip.ui.components.Hint

/**
 * 诊断日志: the whole of `AirClipLog` plus the filters needed to actually read it.
 *
 * The screen exists because the failure it was built to explain — Shizuku authorised, clipboard never
 * monitored — is invisible from every other screen, and on a phone Logcat is not available either. The
 * self-test lives here too, so its conclusion and the steps it walked end up on screen together.
 */
@Composable
fun LogScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val entries by vm.logEntries.collectAsState()
    val includeContent by vm.logIncludeContent.collectAsState()
    val keepTrace by vm.logKeepTrace.collectAsState()
    val doctor by vm.doctorResult.collectAsState()
    val doctorRunning by vm.doctorRunning.collectAsState()

    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    var problemsOnly by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(true) }

    val tags = remember(entries) { entries.map { it.tag }.distinct() }
    val visible = remember(entries, query, tag, problemsOnly) {
        entries.filter { it.matches(query, tag, problemsOnly) }
    }

    val listState = rememberLazyListState()
    // scrollToItem, not animateScrollToItem: at a line per second an animation never gets to finish.
    LaunchedEffect(visible.size, autoScroll) {
        if (autoScroll && visible.isNotEmpty()) listState.scrollToItem(visible.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.log_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TagRow(tags = tags, selected = tag, onSelect = { tag = it })
            ScrollingRow {
                Toggle(stringResource(R.string.log_auto_scroll), autoScroll) { autoScroll = it }
                Toggle(stringResource(R.string.log_filter_problems), problemsOnly) { problemsOnly = it }
                Toggle(stringResource(R.string.log_keep_trace), keepTrace, vm::setLogKeepTrace)
                Toggle(stringResource(R.string.log_include_content), includeContent, vm::setLogIncludeContent)
            }
            ScrollingRow {
                TextButton(onClick = vm::runShizukuDoctor, enabled = !doctorRunning) {
                    Text(stringResource(R.string.log_doctor))
                }
                TextButton(onClick = { vm.copyLog() }) { Text(stringResource(R.string.log_copy)) }
                TextButton(onClick = { vm.shareLog(context) }) { Text(stringResource(R.string.log_share)) }
                TextButton(onClick = vm::clearLog) { Text(stringResource(R.string.log_clear)) }
            }
            if (doctorRunning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Hint(stringResource(R.string.log_doctor_running))
                }
            }
            doctor?.let { Conclusion(it) }
            Hint(stringResource(R.string.log_count, visible.size, entries.size))
        }

        HorizontalDivider()

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Hint(stringResource(if (entries.isEmpty()) R.string.log_empty else R.string.log_no_match))
            }
        } else {
            // Selectable so a single interesting line can be picked out without exporting everything.
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(visible, key = { it.seq }) { entry -> LogLine(entry) }
                }
            }
        }
    }
}

/** Chips and buttons both overflow a phone's width, and neither is worth a second line. */
@Composable
private fun ScrollingRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Tags are collected from the buffer rather than declared: the helper process invents its own. */
@Composable
private fun TagRow(tags: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    ScrollingRow {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.log_filter_all)) },
        )
        tags.forEach { name ->
            FilterChip(
                selected = selected == name,
                onClick = { onSelect(if (selected == name) null else name) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(selected = checked, onClick = { onChange(!checked) }, label = { Text(label) })
}

/** The self-test's verdict. Deliberately above the log: it names the one step that failed. */
@Composable
private fun Conclusion(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.log_doctor_result), style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    Text(
        text = entry.line(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = when (entry.level) {
            LogLevel.ERROR -> MaterialTheme.colorScheme.error
            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
            LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
            LogLevel.DEBUG, LogLevel.TRACE -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** Search covers the tag as well as the message, so `Shizuku/svc` finds the helper's own lines. */
private fun LogEntry.matches(query: String, onlyTag: String?, problemsOnly: Boolean): Boolean {
    if (onlyTag != null && tag != onlyTag) return false
    if (problemsOnly && level.priority < LogLevel.WARN.priority) return false
    if (query.isBlank()) return true
    return message.contains(query, ignoreCase = true) || tag.contains(query, ignoreCase = true)
}
