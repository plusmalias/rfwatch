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
        if (!startForegroundCompat()) {
            // Android 14 refuses a location-type foreground service started from the background
            // (e.g. a sticky restart) unless background location is granted. Stop instead of
            // crashing; the activity starts monitoring again when it is next opened.
            stopSelf()
            return START_NOT_STICKY
        }
        // onStartCommand runs again for every start request; the repository ignores repeats
        // while its collectors on this service's scope are still active.
        val repo = (application as RfWatchApp).container.repository
        repo.startMonitoring(lifecycleScope)
        running.value = true
        repo.publishStatus()
        return START_STICKY
    }

    /** Returns false when the system does not allow this foreground start. */
    private fun startForegroundCompat(): Boolean {
        val n: Notification = NotificationCompat.Builder(this, AlertManager.MONITOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("RFWatch monitoring")
            .setContentText("Scanning for trackers and priority devices")
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException (API 31+)
            false
        }
    }

    override fun onDestroy() {
        running.value = false
        (application as RfWatchApp).container.repository.publishStatus()
        super.onDestroy()
    }

    companion object {
        /** Whether monitoring is running (read by the quick-settings tile and widget). */
        val running = kotlinx.coroutines.flow.MutableStateFlow(false)
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
