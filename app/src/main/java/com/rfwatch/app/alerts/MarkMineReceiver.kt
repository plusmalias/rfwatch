package com.rfwatch.app.alerts

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rfwatch.app.RfWatchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the "It's mine" notification action: marks the device as the user's own and clears the alert. */
class MarkMineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as RfWatchApp).container.repository.setDeviceFlags(id, own = true)
                if (notificationId >= 0) (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
