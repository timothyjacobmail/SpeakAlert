package com.ghostgramlabs.speakalert.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.data.model.MissedReminderEntity
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.util.isDefaultAppDisplayName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class UpcomingRemindersRemoteViewsService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return UpcomingRemindersRemoteViewsFactory(
            context = applicationContext,
            appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        )
    }
}

private class UpcomingRemindersRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private val items = mutableListOf<UpcomingWidgetItem>()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            items.clear()
            return
        }

        val app = context.applicationContext as VoiceReminderApp
        val refreshedItems = runBlocking {
            loadWidgetItems(app)
        }
        items.clear()
        items.addAll(refreshedItems)
    }

    override fun onDestroy() {
        items.clear()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews? {
        val item = items.getOrNull(position) ?: return null
        return RemoteViews(context.packageName, R.layout.widget_upcoming_reminder_item).apply {
            setTextViewText(R.id.upcoming_item_title, item.title)
            setTextViewText(R.id.upcoming_item_time, item.whenLabel)
            setOnClickFillInIntent(
                R.id.upcoming_item_root,
                Intent().apply {
                    item.reminderId?.let { putExtra("reminderId", it) }
                }
            )
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.stableId ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private suspend fun loadWidgetItems(app: VoiceReminderApp): List<UpcomingWidgetItem> {
        val upcomingItems = loadUpcomingReminders(app)
        val missedItems = loadMissedReminders(app)
        if (upcomingItems.isEmpty()) return missedItems
        if (missedItems.isEmpty()) return upcomingItems

        return buildList {
            // Keep one missed reminder near the top so it stays visible,
            // then let the rest of the list scroll naturally.
            add(missedItems.first())
            addAll(upcomingItems)
            addAll(missedItems.drop(1))
        }
    }

    private suspend fun loadUpcomingReminders(app: VoiceReminderApp): List<UpcomingWidgetItem> {
        val now = System.currentTimeMillis()
        return app.container.reminderRepository.getAllActiveReminders()
            .filter { (it.snoozeUntil ?: it.nextTriggerAt) >= now }
            .sortedBy { it.snoozeUntil ?: it.nextTriggerAt }
            .map { reminder ->
                val triggerAt = reminder.snoozeUntil ?: reminder.nextTriggerAt
                UpcomingWidgetItem(
                    stableId = reminder.id,
                    reminderId = reminder.id,
                    title = buildDisplayTitle(reminder),
                    whenLabel = formatWhen(triggerAt)
                )
            }
    }

    private suspend fun loadMissedReminders(app: VoiceReminderApp): List<UpcomingWidgetItem> {
        return app.container.missedReminderRepository.allMissedReminders
            .first()
            .sortedByDescending(MissedReminderEntity::detectedTime)
            .map { missed ->
                UpcomingWidgetItem(
                    stableId = -1_000_000L - missed.id,
                    reminderId = missed.reminderId.takeIf { it > 0L },
                    title = buildDisplayTitle(missed),
                    whenLabel = "Missed • ${formatWhen(missed.scheduledTime)}"
                )
            }
    }

    private fun buildDisplayTitle(reminder: ReminderEntity): String {
        val userTitle = reminder.title
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.isDefaultAppDisplayName() }
        if (userTitle != null) return userTitle

        val textFallback = com.ghostgramlabs.speakalert.transit.BusAlert.display(reminder.reminderText)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::truncateTextTitle)
        return textFallback ?: context.getString(com.ghostgramlabs.speakalert.R.string.fallback_reminder_title)
    }

    private fun buildDisplayTitle(missed: MissedReminderEntity): String {
        val userTitle = missed.title
            .trim()
            .takeIf { it.isNotEmpty() && !it.isDefaultAppDisplayName() }
        if (userTitle != null) return userTitle

        val textFallback = com.ghostgramlabs.speakalert.transit.BusAlert.display(missed.reminderText)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::truncateTextTitle)
        return textFallback ?: context.getString(com.ghostgramlabs.speakalert.R.string.fallback_reminder_title)
    }

    private fun truncateTextTitle(text: String): String {
        val words = text.split(Regex("\\s+"))
        return if (words.size > 8) words.take(8).joinToString(" ") else text
    }

    private fun formatWhen(triggerAt: Long): String {
        val now = Calendar.getInstance()
        val todayYear = now.get(Calendar.YEAR)
        val today = now.get(Calendar.DAY_OF_YEAR)
        val target = Calendar.getInstance().apply { timeInMillis = triggerAt }
        val targetYear = target.get(Calendar.YEAR)
        val targetDay = target.get(Calendar.DAY_OF_YEAR)
        val time = SimpleDateFormat(com.ghostgramlabs.speakalert.util.TimeFormat.timePattern, Locale.getDefault()).format(Date(triggerAt))
        return when {
            targetYear == todayYear && targetDay == today -> "Today • $time"
            targetYear == todayYear && targetDay == today + 1 -> "Tomorrow • $time"
            else -> "${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(triggerAt))} • $time"
        }
    }
}

private data class UpcomingWidgetItem(
    val stableId: Long,
    val reminderId: Long?,
    val title: String,
    val whenLabel: String
)
