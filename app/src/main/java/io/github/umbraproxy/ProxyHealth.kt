package io.github.umbraproxy

import org.json.JSONObject

enum class ConnectionStatus { STOPPED, WAITING, CHECKING, AVAILABLE, UNAVAILABLE, OFFLINE }

/** Transport reachability, independent from the local service's on/off state. */
data class ProxyHealth(
    val status: ConnectionStatus = ConnectionStatus.STOPPED,
    val listening: Boolean = false,
    val activeRelays: Int = 0,
    val route: String = "",
    val lastError: String = "",
    val checkedAt: Long = 0
) {
    fun labelResource(running: Boolean, starting: Boolean): Int = when {
        starting -> R.string.status_connecting
        !running -> R.string.status_standby
        else -> when (status) {
            ConnectionStatus.AVAILABLE -> R.string.health_available
            ConnectionStatus.CHECKING -> R.string.health_checking
            ConnectionStatus.UNAVAILABLE -> R.string.health_unavailable
            ConnectionStatus.OFFLINE -> R.string.health_offline
            ConnectionStatus.WAITING -> R.string.health_waiting
            ConnectionStatus.STOPPED -> R.string.core_unresponsive
        }
    }

    companion object {
        fun decode(json: String): ProxyHealth? = runCatching {
            val data = JSONObject(json)
            val state = when (data.getString("status")) {
                "stopped" -> ConnectionStatus.STOPPED
                "waiting" -> ConnectionStatus.WAITING
                "checking" -> ConnectionStatus.CHECKING
                "available" -> ConnectionStatus.AVAILABLE
                "unavailable" -> ConnectionStatus.UNAVAILABLE
                else -> error("Unknown native health state")
            }
            val listening = data.getBoolean("listening")
            val active = data.getInt("active_relays")
            val checked = data.getLong("checked_at")
            require(active >= 0 && checked >= 0)
            require(listening || state == ConnectionStatus.STOPPED)
            ProxyHealth(state, listening, active, data.getString("route"), LogText.redact(data.getString("last_error")), checked)
        }.getOrNull()

        // First failure waits 30s, then 60s, then 120s; success resets the delay.
        fun probeDelayMillis(failures: Int): Long = 30_000L * (1L shl failures.coerceIn(0, 2))
    }
}
