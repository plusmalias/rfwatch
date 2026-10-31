package com.rfwatch.app.widget

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.rfwatch.app.MainActivity
import com.rfwatch.app.R
import com.rfwatch.app.discreet.Discreet
import com.rfwatch.app.monitor.MonitorService

/**
 * Quick-settings tile: shows whether monitoring is running plus a one-line status
 * ("Police nearby", "2 travelling with you"). Tap stops monitoring, or opens the app to start
 * it — Android 14 doesn't allow a location foreground service to start from a background tap.
 */
class ScanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (MonitorService.running.value) {
            MonitorService.stop(this)
            refresh()
        } else {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(intent)
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = MonitorService.running.value
        val discreet = Discreet.neutralNotifications(this)
        val summary = StatusWidget.current()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (discreet) "Calculator" else "RF scan"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when {
                discreet -> null
                !running -> "Off"
                summary != null -> summary.headline
                else -> "Monitoring"
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_radar)
        tile.updateTile()
    }
}
