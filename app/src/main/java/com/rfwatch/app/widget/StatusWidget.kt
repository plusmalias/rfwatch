package com.rfwatch.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.rfwatch.app.MainActivity
import com.rfwatch.app.R
import com.rfwatch.app.discreet.Discreet

/** What the widget and quick-settings tile show; built by the repository from live state. */
data class StatusSummary(
    val monitoring: Boolean,
    val headline: String,
    val lines: List<String>,
    val alarming: Boolean,
)

/** Home-screen status widget. Content is pushed via [StatusWidget.push]; tap opens the app. */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, last ?: StatusSummary(false, "Not monitoring", listOf("Tap to open"), false), manager, ids)
    }

    companion object {
        @Volatile private var last: StatusSummary? = null

        /** Update every widget instance and ask the tile to refresh. Cheap no-op if nothing changed. */
        fun push(context: Context, summary: StatusSummary) {
            if (summary == last) return
            last = summary
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StatusWidget::class.java))
            if (ids.isNotEmpty()) render(context, summary, manager, ids)
            runCatching { TileService.requestListeningState(context, ComponentName(context, ScanTileService::class.java)) }
        }

        fun current(): StatusSummary? = last

        private fun render(context: Context, s: StatusSummary, manager: AppWidgetManager, ids: IntArray) {
            val discreet = Discreet.neutralNotifications(context)
            val views = RemoteViews(context.packageName, R.layout.widget_status).apply {
                setTextViewText(R.id.widget_title, if (discreet) "Calculator" else "RF//WATCH")
                setTextViewText(R.id.widget_headline, if (discreet) "Tap to open" else s.headline)
                setTextViewText(R.id.widget_lines, if (discreet) "" else s.lines.joinToString("\n"))
                setTextColor(R.id.widget_headline, if (s.alarming && !discreet) 0xFFFF6577.toInt() else 0xFFE4EDF2.toInt())
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
