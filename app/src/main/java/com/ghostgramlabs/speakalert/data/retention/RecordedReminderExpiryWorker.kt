package com.ghostgramlabs.speakalert.data.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.alarm.NotificationHelper
import com.ghostgramlabs.speakalert.data.database.AppDatabase
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.widget.SpeakAlertWidgetUpdater
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecordedReminderExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val context = applicationContext
            val app = context as VoiceReminderApp
            val directory = File(context.filesDir, "reminders")
            val cleaner = RecordedReminderExpiryCleaner(
                RoomRecordedReminderExpiryStore(AppDatabase.getDatabase(context), directory), directory,
                onExpired = { reminder ->
                    // Attempt each side effect independently; deleted rows cannot alert again.
                    runCatching { app.container.alarmScheduler.cancel(reminder) }
                        .onFailure { android.util.Log.w("RecordingExpiry", "Could not cancel old alarm", it) }
                    runCatching { NotificationHelper.cancelAlertNotification(context, reminder.id) }
                        .onFailure { android.util.Log.w("RecordingExpiry", "Could not clear old alert", it) }
                    com.ghostgramlabs.speakalert.service.ReminderPlaybackService.stopIfPlaying(context, reminder.id)
                },
                onError = { android.util.Log.w("RecordingExpiry", "Recording cleanup incomplete", it) }
            )
            if (cleaner.clean() > 0) SpeakAlertWidgetUpdater.requestUpdate(context)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w("RecordingExpiry", "Could not expire old recordings", failure)
            Result.retry()
        }
    }

    companion object {
        fun scheduleMaintenance(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork("recorded-reminder-expiry-maintenance", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecordedReminderExpiryWorker>(1, TimeUnit.HOURS).build())
            enqueueSweep(context)
        }

        fun enqueueSweep(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("recorded-reminder-expiry-sweep", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RecordedReminderExpiryWorker>().build())
        }

        fun schedule(context: Context, reminder: ReminderEntity) {
            val expiresAt = RecordedReminderExpiry(File(context.filesDir, "reminders")).expiresAt(reminder) ?: return
            val now = System.currentTimeMillis()
            val delay = if (expiresAt <= now) 0L else expiresAt - now
            WorkManager.getInstance(context).enqueueUniqueWork("recorded-reminder-expiry-${reminder.id}", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RecordedReminderExpiryWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
        }
    }
}
