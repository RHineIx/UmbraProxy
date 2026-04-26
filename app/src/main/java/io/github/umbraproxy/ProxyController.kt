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
        var secretKey = settingsStore.secretKey.first()

        // Generate a valid 32-character secret if it doesn't exist
        if (secretKey.isBlank() || secretKey.length != 32) {
            secretKey = generateRandomSecret()
            settingsStore.saveSecretKey(secretKey)
        }

        if (port == null || port !in 1..65535) {
            if (showInvalidPortToast) {
                Toast.makeText(context, "Invalid port specified", Toast.LENGTH_SHORT).show()
            }
            return
        }

        start(context, port, cfEnabled, secretKey)
    }

    private fun start(context: Context, port: Int, cfEnabled: Boolean, secretKey: String) {
        if (ProxyService.isRunning.value) return

        val intent = Intent(context, ProxyService::class.java).apply {
            putExtra("PORT", port)
            putExtra("CF_ENABLED", cfEnabled)
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
        context.startService(intent) // Safely trigger service teardown
        ProxyService.updateState(false)
    }

    private fun generateRandomSecret(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
