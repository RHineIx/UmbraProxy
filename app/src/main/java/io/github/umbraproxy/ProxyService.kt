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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var healthProbeJob: Job? = null
    private var networkAvailable = false
    private var notificationStartedAtMillis = 0L
    private var lastNotificationContent: String? = null

    @Volatile private var stopping = false
    private var lastPoolSize = 4
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

        private val _health = MutableStateFlow(ProxyHealth())
        val health: StateFlow<ProxyHealth> = _health

        fun updateState(running: Boolean) {
            _isRunning.value = running
            _isStarting.value = false
            if (!running) {
                _liveStats.value = ProxyStats()
                _health.value = ProxyHealth()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogManager.startLogging("service")
        AppLog.i("Service", "Foreground service created")
        createNotificationChannel()
        setupNetworkMonitor()
    }

    private fun setupNetworkMonitor() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkAvailable = connectivityManager?.activeNetwork != null
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                private var previousNetwork: Network? = null
                private var networkLost = false

                override fun onAvailable(network: Network) {
                    // Android invokes callbacks off the main thread. Serialize state and
                    // debounce jobs with the rest of the service on Dispatchers.Main.
                    serviceScope.launch {
                        val changed = previousNetwork != null && (previousNetwork != network || networkLost)
                        previousNetwork = network
                        networkLost = false
                        networkAvailable = true
                        if (_isRunning.value && _health.value.status == ConnectionStatus.OFFLINE) {
                            _health.value = ProxyHealth(ConnectionStatus.CHECKING, listening = true)
                        }
                        if (changed) scheduleSoftRestart("Default network changed")
                    }
                }

                override fun onLost(network: Network) {
                    serviceScope.launch {
                        if (previousNetwork == network) {
                            networkLost = true
                            networkAvailable = false
                            if (_isRunning.value) publishHealth(ProxyHealth(ConnectionStatus.OFFLINE, listening = true))
                        }
                    }
                }
            }
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            AppLog.i("Service", "Network monitor registered successfully")
        } catch (e: Exception) {
            AppLog.e("Service", "Failed to setup network monitor", e)
        }
    }

    private fun scheduleSoftRestart(reason: String) {
        if (stopping || !_isRunning.value) return

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(1500)

            reconnectMutex.withLock {
                if (stopping || !_isRunning.value) return@withLock

                AppLog.i("Service", "Triggering soft restart due to: $reason")
                _isStarting.value = true
                _health.value = ProxyHealth(ConnectionStatus.CHECKING, listening = true)
                updateNotification(localizedString(R.string.status_connecting))

                withContext(Dispatchers.IO) {
                    val success = NativeProxy.restartProxy(
                        lastPort, lastCfEnabled, lastCfDomain,
                        lastFakeTlsEnabled, lastFakeTlsDomain,
                        lastSecret, lastDcIps, lastPoolSize, cacheDir.absolutePath,
                        shouldStart = { !stopping && serviceJob.isActive }
                    )

                    withContext(Dispatchers.Main) {
                        if (success && !stopping) {
                            _isStarting.value = false
                            AppLog.i("Service", "Soft restart completed successfully")
                            startWatchdogAndStats()
                        } else if (!success) {
                            AppLog.e("Service", "Soft restart failed, initiating full shutdown")
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

        if (stopping) return START_NOT_STICKY

        if (_isRunning.value || _isStarting.value) {
            AppLog.i("Service", "Service already running or starting, ignoring duplicate intent.")
            return START_STICKY
        }

        _isStarting.value = true

        val startingMessage = localizedString(R.string.service_starting)
        notificationStartedAtMillis = System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, createNotification(startingMessage))
        lastNotificationContent = startingMessage
        acquireWakeLock()

        serviceScope.launch {
            try {
                reconnectMutex.withLock {
                    // A sticky restart has a null Intent; restore the saved secret and
                    // routing instead of silently starting with defaults.
                    val startIntent = intent ?: ProxyController.createStartIntent(this@ProxyService)
                    if (startIntent == null || stopping) {
                        stopProxyInternal()
                        return@withLock
                    }
                    lastPort = startIntent.getIntExtra("PORT", 1443)
                    lastCfEnabled = startIntent.getBooleanExtra("CF_ENABLED", true)
                    lastCfDomain = startIntent.getStringExtra("CF_DOMAIN") ?: ""
                    lastFakeTlsEnabled = startIntent.getBooleanExtra("FAKE_TLS_ENABLED", false)
                    lastFakeTlsDomain = startIntent.getStringExtra("FAKE_TLS_DOMAIN") ?: ""
                    lastSecret = startIntent.getStringExtra("SECRET") ?: ""
                    lastDcIps = startIntent.getStringExtra("DC_IPS") ?: ""

                    lastPoolSize = startIntent.getIntExtra("POOL_SIZE", 4)
                    val success = withContext(Dispatchers.IO) {
                        NativeProxy.startProxy(lastPort, lastCfEnabled, lastCfDomain, lastFakeTlsEnabled, lastFakeTlsDomain, lastSecret, lastDcIps, lastPoolSize, cacheDir.absolutePath,
                            shouldStart = { !stopping && serviceJob.isActive })
                    }
                    if (success && !stopping) {
                        updateState(true)
                        _health.value = ProxyHealth(ConnectionStatus.WAITING, listening = true)
                        startWatchdogAndStats()
                    } else {
                        _isStarting.value = false
                        stopSelf()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Service", "Unable to restore proxy settings", e)
                stopProxyInternal()
            }
        }

        return START_STICKY
    }

    private fun startWatchdogAndStats() {
        watchdogJob?.cancel()
        statsUpdateJob?.cancel()

        healthProbeJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive && !stopping) {
                val nativeHealth = withContext(Dispatchers.IO) { NativeProxy.getHealth() }
                if (!stopping && _isRunning.value) {
                    publishHealth(when {
                        !networkAvailable -> ProxyHealth(ConnectionStatus.OFFLINE, listening = nativeHealth?.listening == true)
                        nativeHealth != null -> nativeHealth
                        else -> ProxyHealth(ConnectionStatus.UNAVAILABLE, lastError = "Native health unavailable")
                    })
                    // An unavailable upstream does not require restarting a healthy listener.
                    if ((nativeHealth == null || !nativeHealth.listening) && !_isStarting.value) {
                        scheduleSoftRestart("Native listener unavailable")
                    }
                }
                delay(2000)
            }
        }
        healthProbeJob = serviceScope.launch {
            var failures = 0
            while (isActive && !stopping) {
                if (networkAvailable && _isRunning.value && !_isStarting.value) {
                    val result = withContext(Dispatchers.IO) { NativeProxy.probeHealth() }
                    if (!stopping && _isRunning.value && networkAvailable && result != null) {
                        publishHealth(result)
                    }
                    // Go skips external probes when an established relay is already active.
                    val failed = result == null || result.status == ConnectionStatus.UNAVAILABLE
                    val wait = ProxyHealth.probeDelayMillis(failures)
                    failures = if (failed) (failures + 1).coerceAtMost(2) else 0
                    delay(if (failed) wait else ProxyHealth.probeDelayMillis(0))
                } else {
                    delay(2000)
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
                    updateNotification("${localizedString(R.string.stat_sessions)}: $sessions  •  ${localizedString(R.string.stat_down)}: $down  •  ${localizedString(R.string.stat_up)}: $up")
                }
                delay(2000)
            }
        }
    }

    private fun publishHealth(next: ProxyHealth) {
        val previous = _health.value
        _health.value = next
        if (previous.status != next.status) {
            if (next.status == ConnectionStatus.UNAVAILABLE) {
                AppLog.w("Health", next.lastError.ifBlank { "No usable upstream route" })
            } else {
                AppLog.i("Health", "Connection state: ${next.status}" + next.route.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
            }
        }
    }

    private fun extractValue(stats: String, key: String): String {
        return stats.substringAfter(key, "").substringBefore(' ').ifBlank { "0" }
    }

    private fun updateNotification(content: String) {
        if (notificationStartedAtMillis == 0L || content == lastNotificationContent) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification(content))
            lastNotificationContent = content
        } catch (e: Exception) { AppLog.w("Service", "Failed to notify", e) }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, ProxyService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(localizedString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_tabler_shield_lock)
            .setWhen(notificationStartedAtMillis)
            .setShowWhen(true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .addAction(0, localizedString(R.string.action_disconnect), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopProxyInternal() {
        if (stopping) return
        stopping = true
        updateState(false)
        statsUpdateJob?.cancel()
        watchdogJob?.cancel()
        healthProbeJob?.cancel()
        reconnectJob?.cancel()

        serviceScope.launch {
            reconnectMutex.withLock {
                updateNotification(localizedString(R.string.service_stopping))
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
        stopping = true
        serviceJob.cancel()
        // Native start/stop are serialized, so destruction cannot leave a late
        // startup running without its foreground notification.
        NativeProxy.stopProxy()
        updateState(false)
        releaseWakeLock()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            AppLog.w("Service", "Failed to unregister network callback", e)
        }
        AppLog.i("Service", "Foreground service destroyed")
        LogManager.stopLogging("service")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (_isRunning.value) {
            AppLog.i("Service", "App swiped from recents, service kept alive")
        }
    }

    private fun localizedString(@androidx.annotation.StringRes resource: Int): String =
        ContextCompat.getString(this, resource)

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
        } catch (e: Exception) { AppLog.w("Service", "WakeLock fail", e) }
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UmbraProxy::WakeLock").apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) { AppLog.w("Service", "WakeLock refresh fail", e) }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {}
    }
}
