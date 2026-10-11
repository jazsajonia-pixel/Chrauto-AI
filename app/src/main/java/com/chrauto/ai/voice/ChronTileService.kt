package com.chrauto.ai.voice

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.chrauto.ai.MainActivity
import com.chrauto.ai.data.SettingsStore

/** Quick Settings tile to switch "Chron" hands-free listening on/off. */
class ChronTileService : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        val s = SettingsStore(this)
        val on = !s.chronEnabled
        s.chronEnabled = on
        if (on) {
            if (!ChronService.start(this)) {
                // Needs the mic permission or a foreground start: open the app, which finishes the job.
                val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE))
                } else {
                    @Suppress("DEPRECATION") startActivityAndCollapse(intent)
                }
            }
        } else ChronService.stop(this)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = SettingsStore(this).chronEnabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = SettingsStore(this).wakeName
        tile.updateTile()
    }
}
