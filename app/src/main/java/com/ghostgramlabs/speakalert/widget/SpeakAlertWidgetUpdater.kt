package com.ghostgramlabs.speakalert.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

object SpeakAlertWidgetUpdater {
    private val updates = WidgetUpdateQueue(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        update = { context ->
            // Keep both widget-service lookups and broadcasts off the caller's thread.
            for (provider in listOf(QuickReminderWidgetProvider::class.java, RecordReminderWidgetProvider::class.java, UpcomingRemindersWidgetProvider::class.java)) {
                try {
                    requestProviderUpdate(context, provider)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("WidgetUpdater", "Unable to refresh ${provider.simpleName}", error)
                }
            }
        },
        onError = { Log.w("WidgetUpdater", "Unable to refresh widgets", it) }
    )

    fun requestUpdate(context: Context) {
        updates.request(context.applicationContext)
    }

    private fun requestProviderUpdate(context: Context, providerClass: Class<*>) {
        val manager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, providerClass)
        val widgetIds = manager.getAppWidgetIds(componentName)
        if (widgetIds.isEmpty()) return
        context.sendBroadcast(
            Intent(context, providerClass).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            }
        )
    }
}

/** One active refresh and at most one pending refresh, even during a bulk reminder edit. */
internal class WidgetUpdateQueue(
    scope: CoroutineScope,
    private val update: (Context) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val requests = Channel<Context>(Channel.CONFLATED)

    init {
        scope.launch {
            for (context in requests) {
                try {
                    update(context)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A system-service failure must not kill subsequent refresh requests.
                    onError(error)
                }
            }
        }
    }

    fun request(context: Context) {
        // The UI/repository caller never waits for the widget service.
        requests.trySend(context)
    }
}
