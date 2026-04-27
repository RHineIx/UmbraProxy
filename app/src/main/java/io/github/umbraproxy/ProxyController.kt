package io.github.umbraproxy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.flow.first

object ProxyController {

    suspend fun startFromSavedSettings(context: Context, showInvalidPortToast: Boolean = false) {
        val settingsStore = SettingsStore(context)
        val portText = settingsStore.port.first()
        val port = portText.toIntOrNull()
        val cfEnabled = settingsStore.cfproxyEnabled.first()
        
        val customCfEnabled = settingsStore.customCfDomainEnabled.first()
        val customCfDomainStr = settingsStore.customCfDomain.first()
        val finalCfDomain = if (customCfEnabled && customCfDomainStr.isNotBlank()) customCfDomainStr else ""

        val fakeTlsEnabled = settingsStore.fakeTlsEnabled.first()
        val fakeTlsDomain = settingsStore.fakeTlsDomain.first()

        var secretKey = settingsStore.secretKey.first()

        if (secretKey.length != 32) {
            secretKey = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32)
            settingsStore.saveSecretKey(secretKey)
        }

        if (port == null || port !in 1..65535) {
            if (showInvalidPortToast) {
                Toast.makeText(context, "Invalid port: $portText", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val intent = Intent(context, ProxyService::class.java).apply {
            putExtra("PORT", port)
            putExtra("CF_ENABLED", cfEnabled)
            putExtra("CF_DOMAIN", finalCfDomain)
            putExtra("FAKE_TLS_ENABLED", fakeTlsEnabled)
            putExtra("FAKE_TLS_DOMAIN", fakeTlsDomain)
            putExtra("SECRET", secretKey)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context, ProxyService::class.java).apply {
            action = "STOP_SERVICE"
        }
        context.startService(intent)
    }
}
