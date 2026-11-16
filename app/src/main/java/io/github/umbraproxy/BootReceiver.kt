package io.github.umbraproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settingsStore = SettingsStore(appContext)
                if (!settingsStore.autoStartOnBoot.first()) {
                    AppLog.i("Boot", "Boot completed, autostart disabled")
                    return@launch
                }

                val started = ProxyController.startFromSavedSettings(
                    context = appContext,
                    showInvalidPortToast = false
                )
                AppLog.i("Boot", "Boot completed, proxy autostart requested: $started")
            } catch (e: Exception) {
                AppLog.w("Boot", "Failed to autostart proxy after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}