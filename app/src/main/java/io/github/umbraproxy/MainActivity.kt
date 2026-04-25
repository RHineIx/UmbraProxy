package io.github.umbraproxy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.umbraproxy.ui.UmbraColors
import io.github.umbraproxy.ui.UmbraProxyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        checkBatteryOptimizations()
        
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }
            
            UmbraProxyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UmbraApp(settingsStore)
                }
            }
        }
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UmbraApp(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    val savedPort by settingsStore.port.collectAsStateWithLifecycle(initialValue = "1443")
    
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 100.dp,
        sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = {
            BottomSheetContent(settingsStore)
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "UMBRA",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = 8.sp
                )
                
                Spacer(modifier = Modifier.height(64.dp))
                
                MainConnectButton(
                    isRunning = isRunning,
                    onToggle = {
                        if (isRunning) {
                            ProxyController.stop(context)
                        } else {
                            scope.launch {
                                ProxyController.startFromSavedSettings(context, showInvalidPortToast = true)
                            }
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Text(
                    text = if (isRunning) "SECURE CONNECTION ACTIVE" else "TAP TO CONNECT",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRunning) UmbraColors.ActiveGlow else MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 2.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                if (isRunning) {
                    Button(
                        onClick = {
                            val secret = NativeProxy.getSecretWithPrefix() ?: ""
                            val link = "tg://proxy?server=127.0.0.1&port=$savedPort&secret=$secret"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = UmbraColors.ActiveGlow.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.ActiveGlow)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, tint = UmbraColors.ActiveGlow, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("CONNECT TO TELEGRAM", color = UmbraColors.ActiveGlow, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun MainConnectButton(isRunning: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(if (isRunning) 1.05f else 1.0f, animationSpec = tween(500), label = "")
    val buttonColor by animateColorAsState(if (isRunning) UmbraColors.ActiveGlow else UmbraColors.InactiveGlow, animationSpec = tween(500), label = "")
    val glowAlpha by animateFloatAsState(if (isRunning) 0.3f else 0.0f, animationSpec = tween(1000), label = "")

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Box(modifier = Modifier.fillMaxSize().scale(scale * 1.2f).clip(CircleShape).background(buttonColor.copy(alpha = glowAlpha)))
        Surface(
            modifier = Modifier.size(140.dp).scale(scale).clip(CircleShape).clickable { onToggle() },
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(2.dp, buttonColor),
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = buttonColor, modifier = Modifier.size(64.dp))
            }
        }
    }
}

@Composable
fun BottomSheetContent(settingsStore: SettingsStore) {
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.5f).padding(horizontal = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), Arrangement.Center, Alignment.CenterVertically) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(16.dp))
        SettingsList(settingsStore)
    }
}

@Composable
fun SettingsList(settingsStore: SettingsStore) {
    val portText by settingsStore.port.collectAsStateWithLifecycle(initialValue = "1443")
    val cfEnabled by settingsStore.cfproxyEnabled.collectAsStateWithLifecycle(initialValue = true)
    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            OutlinedTextField(
                value = portText,
                onValueChange = { scope.launch { settingsStore.saveAll(true, "", "", "", "", "", "", "", "", "", "", "", "", false, it, 4, cfEnabled, false, "", "") } },
                label = { Text("Local Port") },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = UmbraColors.ActiveGlow, focusedLabelColor = UmbraColors.ActiveGlow)
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Cloudflare Routing", color = MaterialTheme.colorScheme.onSurface)
                Switch(checked = cfEnabled, onCheckedChange = { scope.launch { settingsStore.saveAll(true, "", "", "", "", "", "", "", "", "", "", "", "", false, portText, 4, it, false, "", "") } }, enabled = !isRunning)
            }
        }
    }
}