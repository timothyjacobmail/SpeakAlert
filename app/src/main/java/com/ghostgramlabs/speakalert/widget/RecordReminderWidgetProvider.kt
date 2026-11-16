package com.ghostgramlabs.speakalert.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
            manager.updateAppWidget(id, ActionWidgetLayouts.create(context, manager, id,
                R.layout.widget_record_reminder_compact, R.layout.widget_record_reminder) {
                setOnClickPendingIntent(R.id.record_widget_root, record)
                setOnClickPendingIntent(R.id.record_widget_button, record)
            })
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(widgetId))
    }
}
