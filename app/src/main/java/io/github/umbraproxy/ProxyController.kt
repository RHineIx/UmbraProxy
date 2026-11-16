package io.github.umbraproxy

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

object ProxyController {

    suspend fun startFromSavedSettings(context: Context, showInvalidPortToast: Boolean = false): Boolean {
        val intent = createStartIntent(context, showInvalidPortToast) ?: return false
        return try {
            context.startForegroundService(intent)
            true
        } catch (e: IllegalStateException) {
            Log.e("UmbraProxy", "Android refused to start the foreground service", e)
            false
        } catch (e: SecurityException) {
            Log.e("UmbraProxy", "Missing permission to start the foreground service", e)
            false
        }
    }

    suspend fun createStartIntent(context: Context, showInvalidPortToast: Boolean = false): Intent? {
        val settingsStore = SettingsStore(context)
        val portText = settingsStore.port.first()
        val port = portText.toIntOrNull()
        val cfEnabled = settingsStore.cfproxyEnabled.first()
        
        val customCfEnabled = settingsStore.customCfDomainEnabled.first()
        val customCfDomainStr = settingsStore.customCfDomain.first()
        val finalCfDomain = if (customCfEnabled) customCfDomainStr.trim() else ""

        val fakeTlsEnabled = settingsStore.fakeTlsEnabled.first()
        val fakeTlsDomain = settingsStore.fakeTlsDomain.first().trim()

        if ((customCfEnabled && !ProxyConfiguration.isValidDomain(finalCfDomain)) ||
            (fakeTlsEnabled && !ProxyConfiguration.isValidDomain(fakeTlsDomain))) {
            if (showInvalidPortToast) withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.msg_invalid_domain, Toast.LENGTH_SHORT).show()
            }
            return null
        }

        val isDcAuto = settingsStore.isDcAuto.first()
        val dcIps = if (isDcAuto) {
            "" 
        } else {
            val dc2 = settingsStore.dc2.first().takeIf { it.isNotBlank() } ?: SettingsStore.DEFAULT_DIRECT_DC2_IP
            val dc4 = settingsStore.dc4.first().takeIf { it.isNotBlank() } ?: SettingsStore.DEFAULT_DIRECT_DC4_IP
            "2:$dc2,-2:$dc2,4:$dc4,-4:$dc4"
        }

        val secretKey = settingsStore.ensureSecretKey()

        if (port == null || port !in 1..65535) {
            if (showInvalidPortToast) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.msg_invalid_port, portText), Toast.LENGTH_SHORT).show()
                }
            }
            return null
        }

        val intent = Intent(context, ProxyService::class.java).apply {
            putExtra("PORT", port)
            putExtra("CF_ENABLED", cfEnabled)
            putExtra("CF_DOMAIN", finalCfDomain)
            putExtra("FAKE_TLS_ENABLED", fakeTlsEnabled)
            putExtra("FAKE_TLS_DOMAIN", fakeTlsDomain)
            putExtra("SECRET", secretKey)
            putExtra("DC_IPS", dcIps)
            putExtra("POOL_SIZE", settingsStore.poolSize.first())
        }
        return intent
    }

    fun stop(context: Context) {
        val intent = Intent(context, ProxyService::class.java).apply {
            action = "STOP_SERVICE"
        }
        context.startService(intent)
    }
}
