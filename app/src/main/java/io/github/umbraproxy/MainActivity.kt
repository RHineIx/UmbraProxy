package io.github.umbraproxy

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as AndroidIcon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.umbraproxy.ui.LogsScreen
import io.github.umbraproxy.ui.AboutScreen
import io.github.umbraproxy.ui.FloatingToolbar
import io.github.umbraproxy.ui.UmbraProxyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }
            val scope = rememberCoroutineScope()

            val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val isDynamicColor by settingsStore.isDynamicColor.collectAsStateWithLifecycle(initialValue = false)

            var showLogsScreen by rememberSaveable { mutableStateOf(false) }
            var showAboutScreen by rememberSaveable { mutableStateOf(false) }

            UmbraProxyTheme(themeMode = themeMode, dynamicColor = isDynamicColor) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    UmbraMainScreen(
                        settingsStore = settingsStore,
                        onOpenLogs = { showLogsScreen = true },
                        onOpenAbout = { showAboutScreen = true }
                    )

                    FloatingToolbar(
                        currentTheme = themeMode,
                        onThemeChange = { mode -> scope.launch { settingsStore.saveThemeMode(mode) } },
                        isDynamicColor = isDynamicColor,
                        onDynamicColorChange = { dc -> scope.launch { settingsStore.saveDynamicColor(dc) } }
                    )

                    AnimatedVisibility(
                        visible = showLogsScreen,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        LogsScreen(
                            settingsStore = settingsStore,
                            onBack = { showLogsScreen = false }
                        )
                    }

                    AnimatedVisibility(
                        visible = showAboutScreen,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        AboutScreen(onBack = { showAboutScreen = false })
                    }
                }
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun UmbraMainScreen(settingsStore: SettingsStore, onOpenLogs: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    val isStarting by ProxyService.isStarting.collectAsStateWithLifecycle() // 🚀 FIXED: True sync from service
    val liveStats by ProxyService.liveStats.collectAsStateWithLifecycle()
    val health by ProxyService.health.collectAsStateWithLifecycle()
    val isReady by settingsStore.isReady.collectAsStateWithLifecycle(initialValue = false)

    val savedPort by settingsStore.port.collectAsStateWithLifecycle(initialValue = "1443")
    val savedCfEnabled by settingsStore.cfproxyEnabled.collectAsStateWithLifecycle(initialValue = true)
    val autoStartOnBoot by settingsStore.autoStartOnBoot.collectAsStateWithLifecycle(initialValue = false)
    val savedPoolSize by settingsStore.poolSize.collectAsStateWithLifecycle(initialValue = 4)
    val savedSecretKey by settingsStore.secretKey.collectAsStateWithLifecycle(initialValue = "")

    val savedIsDcAuto by settingsStore.isDcAuto.collectAsStateWithLifecycle(initialValue = true)
    val dc1 by settingsStore.dc1.collectAsStateWithLifecycle(initialValue = "")
    val savedDc2 by settingsStore.dc2.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_DIRECT_DC2_IP)
    val savedDc4 by settingsStore.dc4.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_DIRECT_DC4_IP)
    val isExperimental by settingsStore.isExperimentalMode.collectAsStateWithLifecycle(initialValue = false)

    val savedCfCustomEnabled by settingsStore.customCfDomainEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedCfCustomDomain by settingsStore.customCfDomain.collectAsStateWithLifecycle(initialValue = "")
    val savedFakeTlsEnabled by settingsStore.fakeTlsEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedFakeTlsDomain by settingsStore.fakeTlsDomain.collectAsStateWithLifecycle(initialValue = "")

    val appLaunchCount by settingsStore.appLaunchCount.collectAsStateWithLifecycle(initialValue = 0)
    val qsPromptShown by settingsStore.qsTilePromptShown.collectAsStateWithLifecycle(initialValue = false)
    var showQsDialog by rememberSaveable { mutableStateOf(false) }

    var portText by rememberSaveable(savedPort) { mutableStateOf(savedPort) }
    var cfDomainText by rememberSaveable(savedCfCustomDomain) { mutableStateOf(savedCfCustomDomain) }
    var fakeTlsDomainText by rememberSaveable(savedFakeTlsDomain) { mutableStateOf(savedFakeTlsDomain) }

    var isDcAuto by rememberSaveable(savedIsDcAuto) { mutableStateOf(savedIsDcAuto) }
    var dc2Text by rememberSaveable(savedDc2) { mutableStateOf(savedDc2) }
    var dc4Text by rememberSaveable(savedDc4) { mutableStateOf(savedDc4) }
    var isAdvancedExpanded by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        settingsStore.incrementLaunchCount()
    }

    // The UI and foreground service own independent native-log capture leases.
    DisposableEffect(lifecycleOwner, isReady) {
        val observer = LifecycleEventObserver { _, event ->
            if (isReady) {
                when (event) {
                    Lifecycle.Event.ON_START -> LogManager.startLogging()
                    Lifecycle.Event.ON_STOP -> LogManager.stopLogging()
                    else -> {}
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (isReady && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            LogManager.startLogging()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            LogManager.stopLogging() // Safely clean up on destruction
        }
    }

    LaunchedEffect(isReady) {
        if (isReady) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                try { context.startActivity(intent) } catch (e: Exception) {}
            }
        }
    }

    LaunchedEffect(appLaunchCount, qsPromptShown) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appLaunchCount >= 3 && !qsPromptShown) {
                showQsDialog = true
            }
        }
    }

    if (!isReady) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LaunchedEffect(isReady) {
        if (isReady) settingsStore.ensureSecretKey()
    }

    val fullSecretPrefix = remember(savedSecretKey, savedFakeTlsEnabled, savedFakeTlsDomain) {
        runCatching {
            ProxyConfiguration.secretWithPrefix(savedSecretKey, savedFakeTlsEnabled, savedFakeTlsDomain.trim())
        }.getOrDefault("")
    }

    val proxyUrl = "https://t.me/proxy?server=127.0.0.1&port=$savedPort&secret=$fullSecretPrefix"

    val saveSettings = { newPort: String, newCf: Boolean, newCfCustom: Boolean, newCfDomain: String, newFakeTls: Boolean, newFakeTlsDomain: String, newPool: Int, newSecret: String, newDcAuto: Boolean, newDc2: String, newDc4: String ->
        scope.launch {
            settingsStore.saveAll(
                newDcAuto, dc1, newDc2, "", newDc4, "", "",
                "", "", "", "", "", "",
                isExperimental, newPort, newPool,
                newCf, newCfCustom, newCfDomain,
                newFakeTls, newFakeTlsDomain, newSecret
            )
        }
    }

    if (showQsDialog) {
        AlertDialog(
            onDismissRequest = {
                showQsDialog = false
                scope.launch { settingsStore.markQsTilePromptShown() }
            },
            title = { Text(stringResource(R.string.qs_prompt_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.qs_prompt_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showQsDialog = false
                    scope.launch { settingsStore.markQsTilePromptShown() }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                        val componentName = ComponentName(context, ProxyTileService::class.java)
                        statusBarManager.requestAddTileService(
                            componentName,
                            context.getString(R.string.app_name),
                            AndroidIcon.createWithResource(context, R.drawable.ic_tabler_shield_lock),
                            context.mainExecutor
                        ) {}
                    }
                }) {
                    Text(stringResource(R.string.btn_add), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showQsDialog = false
                    scope.launch { settingsStore.markQsTilePromptShown() }
                }) {
                    Text(stringResource(R.string.btn_later), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onOpenAbout) {
                    Icon(painterResource(R.drawable.ic_tabler_info_circle), contentDescription = stringResource(R.string.about_title), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onOpenLogs) {
                    Icon(painterResource(R.drawable.ic_tabler_terminal_2), contentDescription = stringResource(R.string.tooltip_view_logs), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            MainConnectionToggle(isRunning = isRunning, isStarting = isStarting, onClick = {
                focusManager.clearFocus()
                if (isRunning) ProxyController.stop(context)
                else if (!isStarting) {
                    scope.launch { ProxyController.startFromSavedSettings(context, true) }
                }
            })

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(health.labelResource(isRunning, isStarting)),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = when {
                    isRunning && health.status in setOf(ConnectionStatus.UNAVAILABLE, ConnectionStatus.OFFLINE, ConnectionStatus.STOPPED) -> MaterialTheme.colorScheme.error
                    isRunning || isStarting -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatItem(stringResource(R.string.stat_sessions), liveStats.sessions)
                    StatDivider()
                    StatItem(stringResource(R.string.stat_down), liveStats.down)
                    StatDivider()
                    StatItem(stringResource(R.string.stat_up), liveStats.up)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.label_local_port), style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(value = portText, onValueChange = { portText = it; saveSettings(it, savedCfEnabled, savedCfCustomEnabled, cfDomainText, savedFakeTlsEnabled, fakeTlsDomainText, savedPoolSize, savedSecretKey, isDcAuto, dc2Text, dc4Text) },
                            enabled = !isRunning && !isStarting, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), modifier = Modifier.width(100.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center), singleLine = true, shape = RoundedCornerShape(12.dp))
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.label_cf_routing), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = savedCfEnabled, onCheckedChange = { saveSettings(portText, it, savedCfCustomEnabled, cfDomainText, savedFakeTlsEnabled, fakeTlsDomainText, savedPoolSize, savedSecretKey, isDcAuto, dc2Text, dc4Text) }, enabled = !isRunning && !isStarting)
                    }

                    AnimatedVisibility(visible = savedCfEnabled) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.label_custom_domain), style = MaterialTheme.typography.bodyLarge)
                                Switch(checked = savedCfCustomEnabled, onCheckedChange = { saveSettings(portText, savedCfEnabled, it, cfDomainText, savedFakeTlsEnabled, fakeTlsDomainText, savedPoolSize, savedSecretKey, isDcAuto, dc2Text, dc4Text) }, enabled = !isRunning && !isStarting)
                            }
                            AnimatedVisibility(visible = savedCfCustomEnabled) {
                                OutlinedTextField(
                                    value = cfDomainText,
                                    onValueChange = { cfDomainText = it; saveSettings(portText, savedCfEnabled, savedCfCustomEnabled, it, savedFakeTlsEnabled, fakeTlsDomainText, savedPoolSize, savedSecretKey, isDcAuto, dc2Text, dc4Text) },
                                    enabled = !isRunning && !isStarting,
                                    label = { Text(stringResource(R.string.label_domain_hint), style = MaterialTheme.typography.labelMedium) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.label_fake_tls), style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = savedFakeTlsEnabled, onCheckedChange = { saveSettings(portText, savedCfEnabled, savedCfCustomEnabled, cfDomainText, it, fakeTlsDomainText, savedPoolSize, savedSecretKey, isDcAuto, dc2Text, dc4Text) }, enabled = !isRunning && !isStarting)
                        }
                        AnimatedVisibility(visible = savedFakeTlsEnabled) {
                            OutlinedTextField(
                                value = fakeTlsDomainText,
                                onValueChange = { fakeTlsDomainText = it; saveSettings(portText, savedCfEnabled, savedCfCustomEnabled, cfDomainText, savedFakeTlsEnabled, it, savedPoolSize, savedSecretKey, isDcAuto, dc2Text, dc4Text) },
                                enabled = !isRunning && !isStarting,
                                label = { Text(stringResource(R.string.label_tls_hint), style = MaterialTheme.typography.labelMedium) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !isRunning && !isStarting) { isAdvancedExpanded = !isAdvancedExpanded }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.label_advanced_dc), style = MaterialTheme.typography.bodyLarge)
                            Icon(painter = painterResource(if (isAdvancedExpanded) R.drawable.ic_tabler_chevron_up else R.drawable.ic_tabler_chevron_down), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        AnimatedVisibility(visible = isAdvancedExpanded) {
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.label_auto_routing), style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = isDcAuto, onCheckedChange = { isDcAuto = it; saveSettings(portText, savedCfEnabled, savedCfCustomEnabled, cfDomainText, savedFakeTlsEnabled, fakeTlsDomainText, savedPoolSize, savedSecretKey, it, dc2Text, dc4Text) }, enabled = !isRunning && !isStarting, modifier = Modifier.scale(0.85f))
                                }

                                AnimatedVisibility(visible = !isDcAuto) {
                                    Column {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedTextField(value = dc2Text, onValueChange = { dc2Text = it; saveSettings(portText, savedCfEnabled, savedCfCustomEnabled, cfDomainText, savedFakeTlsEnabled, fakeTlsDomainText, savedPoolSize, savedSecretKey, isDcAuto, it, dc4Text) },
                                            enabled = !isRunning && !isStarting, label = { Text(stringResource(R.string.label_dc2_hint), style = MaterialTheme.typography.labelMedium) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedTextField(value = dc4Text, onValueChange = { dc4Text = it; saveSettings(portText, savedCfEnabled, savedCfCustomEnabled, cfDomainText, savedFakeTlsEnabled, fakeTlsDomainText, savedPoolSize, savedSecretKey, isDcAuto, dc2Text, it) },
                                            enabled = !isRunning && !isStarting, label = { Text(stringResource(R.string.label_dc4_hint), style = MaterialTheme.typography.labelMedium) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp))
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.label_autostart), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = autoStartOnBoot, onCheckedChange = { enabled -> scope.launch { settingsStore.saveAutoStartOnBoot(enabled) } })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.label_proxy_secret), style = MaterialTheme.typography.bodyLarge)
                            Text(text = stringResource(R.string.btn_generate), style = MaterialTheme.typography.labelMedium, color = if (isRunning) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !isRunning && !isStarting) {
                                    focusManager.clearFocus()
                                    val newSecret = ProxyConfiguration.generateSecret()
                                    saveSettings(portText, savedCfEnabled, savedCfCustomEnabled, cfDomainText, savedFakeTlsEnabled, fakeTlsDomainText, savedPoolSize, newSecret, isDcAuto, dc2Text, dc4Text)
                                }.padding(8.dp))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(16.dp)) {
                            Text(text = fullSecretPrefix, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Proxy", proxyUrl))
                    Toast.makeText(context, context.getString(R.string.msg_copied_clipboard), Toast.LENGTH_SHORT).show()
                }, enabled = fullSecretPrefix.isNotEmpty(), modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(painterResource(R.drawable.ic_tabler_copy), contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(proxyUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(intent) } catch (e: Exception) { Toast.makeText(context, context.getString(R.string.msg_tg_not_installed), Toast.LENGTH_SHORT).show() }
                }, enabled = isRunning, modifier = Modifier.weight(3f).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(painter = painterResource(id = R.drawable.ic_telegram), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.btn_launch_telegram), style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "@RHineix", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/RHineix"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(intent) } catch (e: Exception) {}
                }.padding(vertical = 18.dp, horizontal = 8.dp))
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatDivider() {
    Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)))
}

@Composable
fun MainConnectionToggle(isRunning: Boolean, isStarting: Boolean, onClick: () -> Unit) {
    val isActiveVisual = isRunning || isStarting
    val buttonColor by animateColorAsState(targetValue = if (isActiveVisual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, animationSpec = tween(400), label = "")
    val iconColor by animateColorAsState(targetValue = if (isActiveVisual) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, animationSpec = tween(400), label = "")
    val scale by animateFloatAsState(targetValue = if (isActiveVisual) 1.05f else 1f, animationSpec = tween(300), label = "")
    Surface(modifier = Modifier.size(140.dp).scale(scale).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        shape = CircleShape, color = buttonColor, shadowElevation = if (isActiveVisual) 12.dp else 2.dp) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painter = painterResource(id = R.drawable.ic_telegram), contentDescription = null, tint = iconColor, modifier = Modifier.size(68.dp).offset(x = (-4).dp, y = 2.dp))
        }
    }
}
