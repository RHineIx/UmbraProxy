package io.github.umbraproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ProxyService : Service() {

    // Data class to hold parsed live stats for the UI
    data class ProxyStats(
        val sessions: String = "0",
        val up: String = "0 B",
        val down: String = "0 B"
    )

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var statsUpdateJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "UmbraProxyCore"
        private const val NOTIFICATION_ID = 101

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        // StateFlow to broadcast live stats to the UI (MainActivity)
        private val _liveStats = MutableStateFlow(ProxyStats())
        val liveStats: StateFlow<ProxyStats> = _liveStats

        fun updateState(running: Boolean) {
            _isRunning.value = running
            if (!running) {
                // Reset stats when disconnected
                _liveStats.value = ProxyStats()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopProxyInternal()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra("PORT", 1443) ?: 1443
        val cfEnabled = intent?.getBooleanExtra("CF_ENABLED", true) ?: true
        val secret = intent?.getStringExtra("SECRET") ?: ""

        startForeground(NOTIFICATION_ID, createNotification("Starting UmbraProxy..."))

        CoroutineScope(Dispatchers.IO).launch {
            val success = NativeProxy.startProxy(port, cfEnabled, secret)
            withContext(Dispatchers.Main) {
                if (success) {
                    updateState(true)
                    startStatsMonitor()
                } else {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun startStatsMonitor() {
        statsUpdateJob?.cancel()
        statsUpdateJob = serviceScope.launch {
            while (isActive) {
                val rawStats = NativeProxy.getStats()
                if (rawStats != null) {
                    val sessions = extractValue(rawStats, "active=")
                    val up = extractValue(rawStats, "up=")
                    val down = extractValue(rawStats, "down=")

                    // Update UI StateFlow
                    _liveStats.value = ProxyStats(sessions = sessions, up = up, down = down)

                    // Update Notification
                    val content = "Sessions: $sessions  •  Down: $down  •  Up: $up"
                    updateNotification(content)
                }
                delay(2000) // Fast 2-second update for a snappy UI
            }
        }
    }

    private fun extractValue(stats: String, key: String): String {
        return stats.substringAfter(key, "").substringBefore(" ", "0")
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, ProxyService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UmbraProxy")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Disconnect", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopProxyInternal() {
        statsUpdateJob?.cancel()
        NativeProxy.stopProxy()
        updateState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceJob.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Connection Status", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Umbra::WakeLock").apply {
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
