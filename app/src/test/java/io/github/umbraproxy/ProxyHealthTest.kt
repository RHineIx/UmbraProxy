package io.github.umbraproxy

import org.junit.Assert.*
import org.junit.Test

class ProxyHealthTest {
    private fun health(status: String, listening: Boolean = true, active: Int = 0) =
        """{"status":"$status","listening":$listening,"active_relays":$active,"route":"CF DC2","last_error":"","checked_at":1000}"""

    @Test fun listenerReadinessDoesNotImplyUpstreamAvailability() {
        val waiting = ProxyHealth.decode(health("waiting"))!!
        assertEquals(ConnectionStatus.WAITING, waiting.status)
        assertEquals(R.string.health_waiting, waiting.labelResource(true, false))
        assertEquals(R.string.health_available, ProxyHealth.decode(health("available", active = 1))!!.labelResource(true, false))
        assertEquals(R.string.health_unavailable, ProxyHealth.decode(health("unavailable"))!!.labelResource(true, false))
    }

    @Test fun startupStopAndOfflineLabelsHavePrecedence() {
        val available = ProxyHealth(ConnectionStatus.AVAILABLE, listening = true)
        assertEquals(R.string.status_standby, available.labelResource(false, false))
        assertEquals(R.string.status_connecting, available.labelResource(true, true))
        assertEquals(R.string.health_offline, available.copy(status = ConnectionStatus.OFFLINE).labelResource(true, false))
        assertEquals(R.string.core_unresponsive, ProxyHealth().labelResource(true, false))
    }

    @Test fun malformedHealthNeverReportsSuccess() {
        assertNull(ProxyHealth.decode("not json"))
        assertNull(ProxyHealth.decode(health("unknown")))
        assertNull(ProxyHealth.decode(health("available", listening = false)))
        assertNull(ProxyHealth.decode(health("available", active = -1)))
        assertNotNull(ProxyHealth.decode(health("stopped", listening = false)))
    }

    @Test fun failedProbeBackoffIsBoundedAndSuccessCanResetIt() {
        assertEquals(30_000L, ProxyHealth.probeDelayMillis(0))
        assertEquals(60_000L, ProxyHealth.probeDelayMillis(1))
        assertEquals(120_000L, ProxyHealth.probeDelayMillis(2))
        assertEquals(120_000L, ProxyHealth.probeDelayMillis(100))
        assertEquals(30_000L, ProxyHealth.probeDelayMillis(0))
    }
}
