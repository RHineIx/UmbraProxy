package io.github.umbraproxy

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProxyTileService : TileService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onStartListening() {
        super.onStartListening()
        updateTile(ProxyService.isRunning.value)
    }

    override fun onClick() {
        super.onClick()
        
        val isCurrentlyRunning = ProxyService.isRunning.value

        if (isCurrentlyRunning) {
            ProxyController.stop(this)
            updateTile(false)
        } else {
            scope.launch {
                ProxyController.startFromSavedSettings(this@ProxyTileService, showInvalidPortToast = true)
                updateTile(true)
            }
        }
    }

    private fun updateTile(isRunning: Boolean) {
        val tile = qsTile ?: return
        
        if (isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Umbra Active"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Umbra Off"
        }
        
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}