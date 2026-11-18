package com.privacyflip.app

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: tap to pause or resume Privacy Flip without opening the app. */
class ProtectionTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("privacy_flip_local", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("masterPaused", !prefs.getBoolean("masterPaused", false)).apply()
        AutomationScheduler.scheduleNext(applicationContext)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val paused = getSharedPreferences("privacy_flip_local", Context.MODE_PRIVATE).getBoolean("masterPaused", false)
        tile.label = "Privacy Flip"
        tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
