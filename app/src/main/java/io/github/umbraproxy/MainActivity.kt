package io.github.umbraproxy

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.umbraproxy.ui.UmbraProxyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }
            UmbraProxyTheme {
                UmbraMainScreen(settingsStore)
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun UmbraMainScreen(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    
    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    val isReady by settingsStore.isReady.collectAsStateWithLifecycle(initialValue = false)

    val savedPort by settingsStore.port.collectAsStateWithLifecycle(initialValue = "1443")
    val savedCfEnabled by settingsStore.cfproxyEnabled.collectAsStateWithLifecycle(initialValue = true)
    val savedPoolSize by settingsStore.poolSize.collectAsStateWithLifecycle(initialValue = 4)
    val savedSecretKey by settingsStore.secretKey.collectAsStateWithLifecycle(initialValue = "")
    val savedProxyTag by settingsStore.proxyTag.collectAsStateWithLifecycle(initialValue = "")

    val isDcAuto by settingsStore.isDcAuto.collectAsStateWithLifecycle(initialValue = true)
    val dc1 by settingsStore.dc1.collectAsStateWithLifecycle(initialValue = "")
    val dc2 by settingsStore.dc2.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_DIRECT_DC2_IP)
    val dc3 by settingsStore.dc3.collectAsStateWithLifecycle(initialValue = "")
    val dc4 by settingsStore.dc4.collectAsStateWithLifecycle(initialValue = SettingsStore.DEFAULT_DIRECT_DC4_IP)
    val dc5 by settingsStore.dc5.collectAsStateWithLifecycle(initialValue = "")
    val dc203 by settingsStore.dc203.collectAsStateWithLifecycle(initialValue = "")
    val dc1m by settingsStore.dc1m.collectAsStateWithLifecycle(initialValue = "")
    val dc2m by settingsStore.dc2m.collectAsStateWithLifecycle(initialValue = "")
    val dc3m by settingsStore.dc3m.collectAsStateWithLifecycle(initialValue = "")
    val dc4m by settingsStore.dc4m.collectAsStateWithLifecycle(initialValue = "")
    val dc5m by settingsStore.dc5m.collectAsStateWithLifecycle(initialValue = "")
    val dc203m by settingsStore.dc203m.collectAsStateWithLifecycle(initialValue = "")
    val isExperimental by settingsStore.isExperimentalMode.collectAsStateWithLifecycle(initialValue = false)
    val customCfDomainEnabled by settingsStore.customCfDomainEnabled.collectAsStateWithLifecycle(initialValue = false)
    val customCfDomain by settingsStore.customCfDomain.collectAsStateWithLifecycle(initialValue = "")

    var portText by rememberSaveable(savedPort) { mutableStateOf(savedPort) }
    val isSponsorEnabled = savedProxyTag == "feeec91f71d1400df6609f820936b1dd"

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

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
                context.startActivity(intent)
            }
        }
    }

    if (!isReady) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LaunchedEffect(savedSecretKey) {
        if (savedSecretKey.isBlank()) {
            val generated = UUID.randomUUID().toString().replace("-", "").substring(0, 32)
            scope.launch { settingsStore.saveSecretKey(generated) }
        }
    }

    val secretForUrl = if (savedSecretKey.isNotBlank()) savedSecretKey else "00000000000000000000000000000000"
    val proxyUrl = "https://t.me/proxy?server=127.0.0.1&port=$savedPort&secret=dd$secretForUrl"

    val saveSettings = { newPort: String, newCf: Boolean, newPool: Int, newSecret: String, newTag: String ->
        scope.launch {
            settingsStore.saveAll(
                isDcAuto, dc1, dc2, dc3, dc4, dc5, dc203,
                dc1m, dc2m, dc3m, dc4m, dc5m, dc203m,
                isExperimental, newPort, newPool, newCf, customCfDomainEnabled, customCfDomain, newSecret, newTag
            )
        }
    }

    var isStarting by remember { mutableStateOf(false) }
    LaunchedEffect(isRunning) { if (isRunning || !isRunning) { delay(600); isStarting = false } }

    Scaffold(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))
            MainConnectionToggle(isRunning = isRunning, isStarting = isStarting, onClick = {
                focusManager.clearFocus()
                if (isRunning) ProxyController.stop(context)
                else if (!isStarting) {
                    isStarting = true
                    scope.launch { ProxyController.startFromSavedSettings(context, true) }
                }
            })
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = when { isStarting -> "Connecting..."; isRunning -> "Connected & Secured"; else -> "System Standby" },
                style = MaterialTheme.typography.titleMedium, color = if (isRunning || isStarting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(56.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Local Port", style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(value = portText, onValueChange = { portText = it; saveSettings(it, savedCfEnabled, savedPoolSize, savedSecretKey, savedProxyTag) },
                            enabled = !isRunning, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }), modifier = Modifier.width(100.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center), singleLine = true, shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Cloudflare Routing", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = savedCfEnabled, onCheckedChange = { saveSettings(portText, it, savedPoolSize, savedSecretKey, savedProxyTag) }, enabled = !isRunning)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Connection Pool Size", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2, 4, 6).forEach { size ->
                                val isSelected = savedPoolSize == size
                                Surface(modifier = Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !isRunning) { 
                                    focusManager.clearFocus(); saveSettings(portText, savedCfEnabled, size, savedSecretKey, savedProxyTag) },
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                                    Box(contentAlignment = Alignment.Center) { Text(text = "$size", color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Support Developer Channel", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = isSponsorEnabled,
                            onCheckedChange = { checked ->
                                val newTag = if (checked) "feeec91f71d1400df6609f820936b1dd" else ""
                                saveSettings(portText, savedCfEnabled, savedPoolSize, savedSecretKey, newTag)
                            },
                            enabled = !isRunning
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Proxy Secret", style = MaterialTheme.typography.bodyLarge)
                            Text(text = "GENERATE", style = MaterialTheme.typography.labelMedium, color = if (isRunning) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !isRunning) {
                                    focusManager.clearFocus()
                                    val newSecret = UUID.randomUUID().toString().replace("-", "").substring(0, 32)
                                    saveSettings(portText, savedCfEnabled, savedPoolSize, newSecret, savedProxyTag)
                                }.padding(8.dp))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(16.dp)) {
                            Text(text = secretForUrl, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Proxy", proxyUrl))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(proxyUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(intent) } catch (e: Exception) { Toast.makeText(context, "Telegram not installed", Toast.LENGTH_SHORT).show() }
                }, enabled = isRunning, modifier = Modifier.weight(3f).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(painter = painterResource(id = R.drawable.ic_telegram), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp)); Text("Launch Telegram", style = MaterialTheme.typography.titleMedium)
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