package io.github.umbraproxy

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Controller for the Quick Settings Tile.
 * Ensures the toggle button in the notification shade reflects the service state.
 */
class ProxyTileService : TileService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onStartListening() {
        super.onStartListening()
        updateTile(ProxyService.isRunning.value)
    }

    override fun onClick() {
        super.onClick()
        val isRunning = ProxyService.isRunning.value
        if (isRunning) {
            ProxyController.stop(this)
            updateTile(false)
        } else {
            scope.launch {
                ProxyController.startFromSavedSettings(this@ProxyTileService, false)
                updateTile(true)
            }
        }
    }

    private fun updateTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) "Umbra Active" else "Umbra Off"
        tile.updateTile()
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}