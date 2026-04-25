package io.github.umbraproxy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.flow.first

object ProxyController {

    suspend fun startFromSavedSettings(context: Context, showInvalidPortToast: Boolean = false) {
        val settingsStore = SettingsStore(context)
        val port = settingsStore.port.first()
        val cfEnabled = settingsStore.cfproxyEnabled.first()

        if (port.isBlank() || port.toIntOrNull() == null || port.toInt() !in 1..65535) {
            if (showInvalidPortToast) {
                Toast.makeText(context, "Invalid port specified", Toast.LENGTH_SHORT).show()
            }
            return
        }

        start(context, port, cfEnabled)
    }

    private fun start(context: Context, port: String, cfEnabled: Boolean) {
        if (ProxyService.isRunning.value) return

        val intent = Intent(context, ProxyService::class.java).apply {
            putExtra("PORT", port)
            putExtra("CF_ENABLED", cfEnabled)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context, ProxyService::class.java)
        context.stopService(intent)
        ProxyService.updateState(false)
    }
}