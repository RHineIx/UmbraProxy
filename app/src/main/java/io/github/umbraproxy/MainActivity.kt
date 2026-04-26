package io.github.umbraproxy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.umbraproxy.ui.UmbraColors
import io.github.umbraproxy.ui.UmbraProxyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }
            UmbraProxyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    UmbraApp(settingsStore)
                }
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
    val scaffoldState = rememberBottomSheetScaffoldState()

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 80.dp,
        sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        sheetContent = { BottomSheetContent(settingsStore) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("UMBRA", style = MaterialTheme.typography.headlineLarge, letterSpacing = 8.sp)
                Spacer(modifier = Modifier.height(64.dp))
                PowerButton(isRunning) {
                    scope.launch {
                        if (isRunning) ProxyController.stop(context)
                        else ProxyController.startFromSavedSettings(context, true)
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = if (isRunning) "CONNECTION ACTIVE" else "READY TO SHIELD",
                    color = if (isRunning) UmbraColors.ActiveGlow else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isRunning) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            val secret = NativeProxy.getSecretWithPrefix() ?: ""
                            val link = "tg://proxy?server=127.0.0.1&port=$savedPort&secret=$secret"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = UmbraColors.ActiveGlow.copy(alpha = 0.1f)),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, UmbraColors.ActiveGlow)
                    ) {
                        Icon(Icons.Default.Send, null, modifier = Modifier.size(18.dp), tint = UmbraColors.ActiveGlow)
                        Spacer(Modifier.width(8.dp))
                        Text("TELEGRAM CONNECT", color = UmbraColors.ActiveGlow)
                    }
                }
            }
        }
    }
}

@Composable
fun PowerButton(isRunning: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (isRunning) 1.1f else 1f, label = "")
    val color by animateColorAsState(if (isRunning) UmbraColors.ActiveGlow else UmbraColors.InactiveGlow, label = "")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Box(modifier = Modifier.fillMaxSize().scale(scale * 1.2f).clip(CircleShape).background(color.copy(alpha = if(isRunning) 0.2f else 0f)))
        Surface(
            modifier = Modifier.size(140.dp).scale(scale).clip(CircleShape).clickable { onClick() },
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(2.dp, color),
            shape = CircleShape
        ) {
            Icon(Icons.Default.PowerSettingsNew, null, tint = color, modifier = Modifier.padding(32.dp).fillMaxSize())
        }
    }
}

@Composable
fun BottomSheetContent(settingsStore: SettingsStore) {
    val port by settingsStore.port.collectAsStateWithLifecycle("1443")
    val cfEnabled by settingsStore.cfproxyEnabled.collectAsStateWithLifecycle(true)
    val isRunning by ProxyService.isRunning.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), Arrangement.Center) { Icon(Icons.Default.KeyboardArrowUp, null) }
        Text("SETTINGS", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp))
        OutlinedTextField(
            value = port,
            onValueChange = { scope.launch { settingsStore.saveAll(true, "", "", "", "", "", "", "", "", "", "", "", "", false, it, 4, cfEnabled, false, "", "") } },
            label = { Text("Local Port") },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Cloudflare Optimization")
            Switch(checked = cfEnabled, onCheckedChange = { scope.launch { settingsStore.saveAll(true, "", "", "", "", "", "", "", "", "", "", "", "", false, port, 4, it, false, "", "") } }, enabled = !isRunning)
        }
    }
}