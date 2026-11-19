package com.ghostgramlabs.speakalert.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.ghostgramlabs.speakalert.MainActivity
import com.ghostgramlabs.speakalert.R

class QuickReminderWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, buildRemoteViews(context, appWidgetManager, widgetId))
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(widgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_OPEN_ADD_REMINDER) {
            openAddReminderScreen(context)
        }
    }

    private fun openAddReminderScreen(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("openAddEdit", true)
            }
        )
    }

    private fun buildRemoteViews(context: Context, manager: AppWidgetManager, widgetId: Int): RemoteViews {
        return ActionWidgetLayouts.create(context, manager, widgetId,
            R.layout.widget_quick_reminder_compact, R.layout.widget_quick_reminder) {
            val pendingIntent = actionPendingIntent(context, widgetId)
            setOnClickPendingIntent(R.id.quick_widget_root, pendingIntent)
            setOnClickPendingIntent(R.id.quick_add_button, pendingIntent)
        }
    }

    private fun actionPendingIntent(context: Context, widgetId: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            widgetId * 100 + ACTION_OPEN_ADD_REMINDER.hashCode(),
            Intent(context, QuickReminderWidgetProvider::class.java).apply {
                action = ACTION_OPEN_ADD_REMINDER
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val ACTION_OPEN_ADD_REMINDER = "com.ghostgramlabs.speakalert.widget.OPEN_ADD_REMINDER"
    }
}
