package io.github.umbraproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket

class ProxyService : Service() {

    data class ProxyStats(
        val sessions: String = "0",
        val up: String = "0 B",
        val down: String = "0 B"
    )

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var statsUpdateJob: Job? = null
    private var watchdogJob: Job? = null

    private var lastPort: Int = 1443
    private var lastCfEnabled: Boolean = true
    private var lastCfDomain: String = ""
    private var lastFakeTlsEnabled: Boolean = false
    private var lastFakeTlsDomain: String = ""
    private var lastSecret: String = ""
    private var lastDcIps: String = ""

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    private val reconnectMutex = Mutex()

    companion object {
        private const val CHANNEL_ID = "UmbraProxyCore"
        private const val NOTIFICATION_ID = 101
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
        private const val WAKELOCK_REFRESH_MS = 25L * 60 * 1000

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _isStarting = MutableStateFlow(false)
        val isStarting: StateFlow<Boolean> = _isStarting

        private val _liveStats = MutableStateFlow(ProxyStats())
        val liveStats: StateFlow<ProxyStats> = _liveStats

        fun updateState(running: Boolean) {
            _isRunning.value = running
            _isStarting.value = false
            if (!running) {
                _liveStats.value = ProxyStats()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupNetworkMonitor()
    }

    private fun setupNetworkMonitor() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scheduleSoftRestart("Network Available (Switched interface)")
                }
                override fun onLost(network: Network) {
                    scheduleSoftRestart("Network Lost")
                }
            }
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            Log.i("UmbraProxy", "Network monitor registered successfully")
        } catch (e: Exception) {
            Log.e("UmbraProxy", "Failed to setup network monitor", e)
        }
    }

    private fun scheduleSoftRestart(reason: String) {
        if (!_isRunning.value) return

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(1500)

            reconnectMutex.withLock {
                if (!_isRunning.value) return@withLock

                Log.i("UmbraProxy", "Triggering soft restart due to: $reason")
                _isStarting.value = true
                updateNotification(getString(R.string.status_connecting))

                withContext(Dispatchers.IO) {
                    NativeProxy.stopProxy()
                    val success = NativeProxy.startProxy(
                        lastPort, lastCfEnabled, lastCfDomain, 
                        lastFakeTlsEnabled, lastFakeTlsDomain, 
                        lastSecret, lastDcIps
                    )

                    withContext(Dispatchers.Main) {
                        if (success) {
                            _isStarting.value = false
                            Log.i("UmbraProxy", "Soft restart completed successfully")
                            startWatchdogAndStats(lastPort)
                        } else {
                            Log.e("UmbraProxy", "Soft restart failed, initiating full shutdown")
                            stopProxyInternal()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopProxyInternal()
            return START_NOT_STICKY
        }

        if (_isRunning.value || _isStarting.value) {
            Log.i("UmbraProxy", "Service already running or starting, ignoring duplicate intent.")
            return START_STICKY
        }

        lastPort = intent?.getIntExtra("PORT", 1443) ?: 1443
        lastCfEnabled = intent?.getBooleanExtra("CF_ENABLED", true) ?: true
        lastCfDomain = intent?.getStringExtra("CF_DOMAIN") ?: ""
        lastFakeTlsEnabled = intent?.getBooleanExtra("FAKE_TLS_ENABLED", false) ?: false
        lastFakeTlsDomain = intent?.getStringExtra("FAKE_TLS_DOMAIN") ?: ""
        lastSecret = intent?.getStringExtra("SECRET") ?: ""
        lastDcIps = intent?.getStringExtra("DC_IPS") ?: ""

        _isStarting.value = true

        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.service_starting)))
        acquireWakeLock()

        serviceScope.launch {
            reconnectMutex.withLock {
                val success = withContext(Dispatchers.IO) {
                    NativeProxy.startProxy(lastPort, lastCfEnabled, lastCfDomain, lastFakeTlsEnabled, lastFakeTlsDomain, lastSecret, lastDcIps)
                }
                if (success) {
                    updateState(true)
                    startWatchdogAndStats(lastPort)
                } else {
                    _isStarting.value = false
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun startWatchdogAndStats(port: Int) {
        watchdogJob?.cancel()
        statsUpdateJob?.cancel()

        watchdogJob = serviceScope.launch {
            delay(3000)
            if (_isRunning.value) {
                val isListening = withContext(Dispatchers.IO) {
                    try {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
                            true
                        }
                    } catch (e: Exception) { false }
                }
                if (!isListening) {
                    updateNotification(getString(R.string.core_unresponsive))
                }
            }
        }

        statsUpdateJob = serviceScope.launch {
            launch {
                while (isActive) {
                    delay(WAKELOCK_REFRESH_MS)
                    refreshWakeLock()
                }
            }

            while (isActive) {
                val rawStats = NativeProxy.getStats()
                if (rawStats != null) {
                    val sessions = extractValue(rawStats, "active=")
                    val up = extractValue(rawStats, "up=")
                    val down = extractValue(rawStats, "down=")

                    _liveStats.value = ProxyStats(sessions = sessions, up = up, down = down)
                    updateNotification("${getString(R.string.stat_sessions)}: $sessions  •  ${getString(R.string.stat_down)}: $down  •  ${getString(R.string.stat_up)}: $up")
                }
                delay(2000)
            }
        }
    }

    private fun extractValue(stats: String, key: String): String {
        return stats.substringAfter(key, "").substringBefore(" ", "0")
    }

    private fun updateNotification(content: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification(content))
        } catch (e: Exception) { Log.w("UmbraProxy", "Failed to notify", e) }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, ProxyService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.action_disconnect), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopProxyInternal() {
        statsUpdateJob?.cancel()
        watchdogJob?.cancel()
        reconnectJob?.cancel()

        serviceScope.launch {
            reconnectMutex.withLock {
                updateNotification(getString(R.string.service_stopping))
                withContext(Dispatchers.IO) {
                    NativeProxy.stopProxy()
                }
                updateState(false)
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        releaseWakeLock()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.w("UmbraProxy", "Failed to unregister network callback", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (_isRunning.value) {
            Log.i("UmbraProxy", "App swiped from recents, service kept alive")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Connection Status", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UmbraProxy::WakeLock").apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) { Log.w("UmbraProxy", "WakeLock fail", e) }
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UmbraProxy::WakeLock").apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) { Log.w("UmbraProxy", "WakeLock refresh fail", e) }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {}
    }
}