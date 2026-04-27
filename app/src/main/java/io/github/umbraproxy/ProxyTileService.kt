package io.github.umbraproxy

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Controller for the Quick Settings Tile.
 * Ensures the toggle button in the notification shade reflects the service state.
 */
class ProxyTileService : TileService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var stateJob: Job? = null

    /**
     * Called when the tile is added to the Quick Settings or when it becomes visible.
     * We start observing the proxy state to update the UI in real-time.
     */
    override fun onStartListening() {
        super.onStartListening()
        
        // Cancel any existing observation
        stateJob?.cancel()
        
        // Listen to the shared state from ProxyService
        stateJob = scope.launch {
            ProxyService.isRunning.collect { running ->
                updateTile(running)
            }
        }
    }

    /**
     * Called when the user clicks the tile.
     */
    override fun onClick() {
        super.onClick()
        val isRunning = ProxyService.isRunning.value
        if (isRunning) {
            ProxyController.stop(this)
        } else {
            scope.launch {
                // Start from saved settings without showing toast for a seamless background experience
                ProxyController.startFromSavedSettings(this@ProxyTileService, false)
            }
        }
    }

    /**
     * Updates the Tile appearance and state.
     */
    private fun updateTile(active: Boolean) {
        val tile = qsTile ?: return
        
        // Update state: Active (Colored) or Inactive (Greyed out)
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        
        // Explicitly set the custom icon to ensure it's loaded correctly
        tile.icon = Icon.createWithResource(this, R.drawable.tile)
        
        // Update labels based on state
        tile.label = "UmbraProxy"
        tile.subtitle = if (active) "Active" else "Off"
        
        tile.updateTile()
    }

    override fun onStopListening() {
        stateJob?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}
