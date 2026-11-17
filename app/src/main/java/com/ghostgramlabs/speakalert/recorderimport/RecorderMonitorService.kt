package com.ghostgramlabs.speakalert.recorderimport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.ghostgramlabs.speakalert.MainActivity
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.data.database.AppDatabase
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** No microphone access: observes completed shared audio while a user-enabled notification is visible. */
class RecorderMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scans = Channel<PowerManager.WakeLock>(Channel.CONFLATED, onUndeliveredElement = { lock ->
        runCatching { if (lock.isHeld) lock.release() }
    })
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { requestScan() }
    }
    private var observing = false
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MONITOR_CHANNEL, getString(R.string.recorder_monitor_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(CONFIRM_CHANNEL, getString(R.string.recorder_confirm_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            RecorderImportSettings.disable(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = monitorNotification(false)
        if (Build.VERSION.SDK_INT >= 34) startForeground(MONITOR_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(MONITOR_ID, notification)
        if (!RecorderImportSettings.enabled(this) || !RecorderImportSettings.hasAudioAccess(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started && Build.VERSION.SDK_INT >= 29) {
            started = true
            // OEM scanners can notify the Files collection or a concrete volume instead of
            // the merged Audio URI. Observe both paths so a screen-off save is not missed.
            for (uri in setOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))) {
                contentResolver.registerContentObserver(uri, true, observer)
            }
            observing = true
            val app = application as VoiceReminderApp
            val importer = RecorderImportEngine(
                RecorderMediaSource(this), RoomRecorderImportStore(this, AppDatabase.getDatabase(this)),
                java.io.File(filesDir, "reminders"), { RecorderImportSettings.watch(this) },
                schedule = { app.container.alarmScheduler.schedule(it) },
                confirm = ::confirm,
                onError = { android.util.Log.w("RecorderImport", "Recording import will be retried", it) }
            )
            scope.launch {
                var retries = 0
                for (lock in scans) {
                    wakeLock = lock
                    val retry: Boolean
                    try {
                        // Recorder/MediaStore may publish several updates while finalizing one take.
                        delay(2_000L)
                        while (true) {
                            val extra = scans.tryReceive().getOrNull() ?: break
                            runCatching { if (extra.isHeld) extra.release() }
                        }
                        if (!RecorderImportSettings.enabled(this@RecorderMonitorService)) break
                        retry = try {
                            importer.scan()
                        } catch (failure: kotlinx.coroutines.CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            android.util.Log.w("RecorderImport", "Could not read saved recordings", failure)
                            true
                        }
                    } finally {
                        runCatching { if (lock.isHeld) lock.release() }
                        wakeLock = null
                    }
                    manager().notify(MONITOR_ID, monitorNotification(retry))
                    if (retry && retries++ < 6) {
                        delay(10_000L)
                        requestScan()
                    } else retries = 0
                }
            }
        }
        requestScan()
        return START_STICKY
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun requestScan() {
        // Take the wake lock when the save event arrives, before the debounce delay. Otherwise
        // the phone could sleep as Recorder closes and postpone import until the next unlock.
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:RecorderImport")
        lock.acquire(120_000L)
        if (scans.trySend(lock).isFailure) runCatching { if (lock.isHeld) lock.release() }
    }

    private fun monitorNotification(error: Boolean): android.app.Notification = NotificationCompat.Builder(this, MONITOR_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.recorder_import_title))
        .setContentText(getString(if (error) R.string.recorder_monitor_error else R.string.recorder_monitor_waiting))
        .setContentIntent(PendingIntent.getActivity(this, 7400, Intent(this, RecorderImportSettingsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, getString(R.string.recorder_import_turn_off), PendingIntent.getService(this, 7401,
            Intent(this, RecorderMonitorService::class.java).setAction(ACTION_DISABLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun confirm(reminder: ReminderEntity) {
        check(manager().areNotificationsEnabled()) { "Notifications are disabled" }
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(reminder.nextTriggerAt))
        val open = PendingIntent.getActivity(this, reminder.id.toInt(), Intent(this, MainActivity::class.java).putExtra("reminderId", reminder.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (reminder.nextTriggerAt <= System.currentTimeMillis()) getString(R.string.recorder_import_late)
            else getString(R.string.recorder_import_confirm, time)
        manager().notify("recorder-import-${reminder.id}", CONFIRM_ID, NotificationCompat.Builder(this, CONFIRM_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.recorder_import_confirm_title))
            .setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build())
        android.util.Log.i("RecorderImport", "Recorder reminder ${reminder.id} scheduled at ${reminder.nextTriggerAt}")
    }

    override fun onDestroy() {
        if (observing) contentResolver.unregisterContentObserver(observer)
        scans.cancel()
        scope.cancel()
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val MONITOR_CHANNEL = "recorder_monitor"
        private const val CONFIRM_CHANNEL = "recorder_import_confirm"
        private const val MONITOR_ID = 7400
        private const val CONFIRM_ID = 7402
        private const val ACTION_DISABLE = "com.ghostgramlabs.speakalert.recorderimport.DISABLE"
    }
}

class RecorderImportRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            RecorderImportSettings.restore(context)
        }
    }
}
