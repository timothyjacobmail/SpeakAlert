package com.ghostgramlabs.speakalert.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ghostgramlabs.speakalert.R

class RecordReminderWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            // Launch the visible recording activity directly from the user's widget tap.
            val record = PendingIntent.getActivity(
                context, id,
                Intent(context, QuickRecordReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_record_reminder).apply {
                setOnClickPendingIntent(R.id.record_widget_root, record)
                setOnClickPendingIntent(R.id.record_widget_button, record)
            })
        }
    }
}
