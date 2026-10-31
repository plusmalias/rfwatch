package com.rfwatch.app.monitor

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rfwatch.app.RfWatchApp
import com.rfwatch.app.alerts.AlertManager

/**
 * Foreground service that keeps BLE/Wi-Fi/location monitoring alive while the app is
 * backgrounded (so follower detection works on a long drive). It simply starts the
 * repository's collectors on its lifecycle scope.
 */
class MonitorService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat()
        val repo = (application as RfWatchApp).container.repository
        repo.startMonitoring(lifecycleScope)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val n: Notification = NotificationCompat.Builder(this, AlertManager.MONITOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("RFWatch monitoring")
            .setContentText("Scanning for trackers and priority devices")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val NOTIF_ID = 42
        fun start(context: Context) {
            val i = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
