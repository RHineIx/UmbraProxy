package io.github.umbraproxy.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.umbraproxy.*
import io.github.umbraproxy.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

private val ConsoleBackground = Color(0xFF0B1018)
private val ConsoleText = Color(0xFFD8E2EF)
private val ConsoleMuted = Color(0xFF9BACBF)

private fun LogLevel.color(): Color = when (this) {
    LogLevel.DEBUG -> Color(0xFF8EBBFF)
    LogLevel.INFO -> Color(0xFF73D7AC)
    LogLevel.WARN -> Color(0xFFFFCE73)
    LogLevel.ERROR -> Color(0xFFFF8F91)
}

private data class PendingExport(val contents: String, val filename: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(settingsStore: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by LogManager.snapshot.collectAsStateWithLifecycle()
    val captureDebug by LogManager.captureDebug.collectAsStateWithLifecycle()
    val showInfo by settingsStore.logShowInfo.collectAsStateWithLifecycle(initialValue = true)
    val showWarn by settingsStore.logShowWarn.collectAsStateWithLifecycle(initialValue = true)
    val showError by settingsStore.logShowError.collectAsStateWithLifecycle(initialValue = true)
    val showDebug by settingsStore.logShowDebug.collectAsStateWithLifecycle(initialValue = true)
    var query by rememberSaveable { mutableStateOf("") }
    // The frozen snapshot is memory-only, like the history; configuration changes resume live display.
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf(LogSnapshot()) }
    var followTail by rememberSaveable { mutableStateOf(true) }
    var exportMenu by remember { mutableStateOf(false) }
    var debugChanging by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    val shownSnapshot = if (paused) frozen else snapshot
    val levels = mapOf(LogLevel.DEBUG to showDebug, LogLevel.INFO to showInfo, LogLevel.WARN to showWarn, LogLevel.ERROR to showError)
    val filtered = remember(shownSnapshot.entries, query, levels) {
        shownSnapshot.entries.filter { levels[it.level] == true && it.matches(query) }
    }
    val counts = remember(shownSnapshot.entries) { shownSnapshot.entries.groupingBy { it.level }.eachCount() }

    fun toast(resource: Int) { Toast.makeText(context, resource, Toast.LENGTH_SHORT).show() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val export = pendingExport
        pendingExport = null
        if (uri != null && export != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open log destination")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(export.contents) }
                }
                toast(R.string.logs_exported)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AppLog.e("Logs", "Log export failed", e)
                toast(R.string.logs_action_failed)
            }
        }
    }

    fun export(format: LogExportFormat) {
        exportMenu = false
        val selected = filtered.toList()
        val selectedSnapshot = shownSnapshot
        scope.launch {
            try {
                val contents = withContext(Dispatchers.IO) { LogExporter.export(selected, selectedSnapshot, BuildConfig.VERSION_NAME, format) }
                val date = Instant.now().toString().replace(':', '-')
                pendingExport = PendingExport(contents, "UmbraProxy-$date.${format.extension}")
                exportLauncher.launch(pendingExport!!.filename)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AppLog.e("Logs", "Preparing log export failed", e); toast(R.string.logs_action_failed) }
        }
    }

    BackHandler { onBack() }
    LaunchedEffect(dragged) { if (dragged) followTail = false }
    LaunchedEffect(filtered.lastOrNull()?.id, filtered.size, followTail, paused) {
        if (followTail && !paused && filtered.isNotEmpty()) listState.scrollToItem(filtered.lastIndex)
    }

    Scaffold(
        containerColor = ConsoleBackground,
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_system_logs), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_tabler_arrow_left), stringResource(R.string.logs_back)) } },
                    actions = {
                        IconButton(enabled = filtered.isNotEmpty(), onClick = {
                            val selected = filtered.toList()
                            val selectedSnapshot = shownSnapshot
                            scope.launch {
                                try {
                                    val text = withContext(Dispatchers.IO) { LogExporter.export(selected, selectedSnapshot, BuildConfig.VERSION_NAME, LogExportFormat.TEXT) }
                                    if (text.toByteArray(Charsets.UTF_8).size > 256 * 1024) {
                                        toast(R.string.logs_clipboard_too_large)
                                    } else {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("UmbraProxy logs", text))
                                        toast(R.string.msg_logs_copied)
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { AppLog.e("Logs", "Copying logs failed", e); toast(R.string.logs_action_failed) }
                            }
                        }) { Icon(painterResource(R.drawable.ic_tabler_copy), stringResource(R.string.logs_copy)) }
                        Box {
                            IconButton(enabled = filtered.isNotEmpty(), onClick = { exportMenu = true }) {
                                Icon(painterResource(R.drawable.ic_tabler_download), stringResource(R.string.logs_export))
                            }
                            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.logs_export_text)) }, onClick = { export(LogExportFormat.TEXT) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.logs_export_json)) }, onClick = { export(LogExportFormat.JSON_LINES) })
                            }
                        }
                        IconButton(enabled = snapshot.entries.isNotEmpty() || shownSnapshot.entries.isNotEmpty(), onClick = {
                            scope.launch {
                                LogManager.clearLogs().onSuccess { frozen = LogManager.snapshot.value }
                                    .onFailure { AppLog.e("Logs", "Clearing log history failed", it); toast(R.string.logs_action_failed) }
                            }
                        }) { Icon(painterResource(R.drawable.ic_tabler_trash), stringResource(R.string.btn_clear_logs)) }
                    }
                )
                // Keep the console visible on short windows and with large fonts.
                Column(modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.4f).dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.logs_search)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_tabler_search), null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_tabler_x), stringResource(R.string.logs_clear_search)) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    )
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(LogLevel.entries, key = { it.name }) { level ->
                            FilterChip(
                                selected = levels[level] == true,
                                onClick = { scope.launch { settingsStore.saveLogLevelEnabled(level, levels[level] != true) } },
                                label = { Text("${level.name} ${counts[level] ?: 0}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                            )
                        }
                    }
                    FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { if (!paused) frozen = snapshot; paused = !paused }) {
                            Icon(painterResource(if (paused) R.drawable.ic_tabler_player_play else R.drawable.ic_tabler_player_pause), null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(if (paused) R.string.logs_resume else R.string.logs_pause))
                        }
                        TextButton(onClick = { followTail = !followTail }, colors = ButtonDefaults.textButtonColors(contentColor = if (followTail) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                            Icon(painterResource(R.drawable.ic_tabler_arrow_bar_to_down), null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(if (followTail) R.string.logs_following else R.string.logs_follow))
                        }
                        Text(stringResource(if (paused) R.string.logs_frozen else R.string.logs_live), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.logs_capture_debug), style = MaterialTheme.typography.labelMedium)
                            Text(stringResource(R.string.logs_clock_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = captureDebug, enabled = !debugChanging, onCheckedChange = { enabled ->
                            debugChanging = true
                            scope.launch {
                                try { LogManager.setDebugCapture(enabled).onFailure { AppLog.e("Logs", "Changing debug capture failed", it); toast(R.string.logs_action_failed) } }
                                finally { debugChanging = false }
                            }
                        })
                    }
                    if (snapshot.captureError != null) Text(
                        stringResource(R.string.logs_capture_error), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.logs_count, filtered.size, shownSnapshot.entries.size), color = ConsoleMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (shownSnapshot.evicted > 0 || shownSnapshot.nativeDropped > 0) Text(
                stringResource(R.string.logs_retention, shownSnapshot.evicted, shownSnapshot.nativeDropped),
                color = LogLevel.WARN.color(), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
            )
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(R.drawable.ic_tabler_terminal_2), null, tint = ConsoleMuted, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(if (shownSnapshot.entries.isEmpty()) R.string.logs_empty else R.string.logs_no_matches), color = ConsoleText)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.logs_memory_hint), color = ConsoleMuted, fontSize = 12.sp)
                    }
                }
            } else {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(filtered, key = { _, entry -> entry.id }) { index, entry -> LogRow(entry, index % 2 == 0) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, alternate: Boolean) {
    var detailExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val color = entry.level.color()
    Row(modifier = Modifier.fillMaxWidth().background(if (alternate) Color(0xFF101824) else ConsoleBackground).padding(start = 10.dp)) {
        Box(Modifier.width(2.dp).height(26.dp).padding(top = 6.dp).background(color.copy(alpha = 0.7f)))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(LogText.time(entry.timestampMillis), color = ConsoleMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text(entry.level.name, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(entry.source, color = ConsoleMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SelectionContainer {
                Text(entry.message, color = ConsoleText, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            Text("pid=${entry.pid}" + (entry.thread?.let { " · $it" } ?: "") + (entry.nativeSequence?.let { " · seq=$it" } ?: ""), color = ConsoleMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
            if (entry.truncated) Text(stringResource(R.string.logs_truncated), color = LogLevel.WARN.color(), fontSize = 11.sp)
            if (entry.detail != null) {
                Text(stringResource(if (detailExpanded) R.string.logs_hide_detail else R.string.logs_show_detail), color = color, fontSize = 11.sp,
                    modifier = Modifier.clickable { detailExpanded = !detailExpanded }.padding(vertical = 8.dp))
                if (detailExpanded) SelectionContainer {
                    Text(entry.detail, color = ConsoleMuted, fontSize = 11.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
