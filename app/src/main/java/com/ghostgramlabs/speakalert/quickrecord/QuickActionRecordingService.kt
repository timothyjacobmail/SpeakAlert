package com.ghostgramlabs.speakalert.quickrecord

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ghostgramlabs.speakalert.MainActivity
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.audio.AndroidAudioRecorder
import com.ghostgramlabs.speakalert.shake.ShakeDraftStore
import com.ghostgramlabs.speakalert.shake.ShakeReminderCreator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Microphone access is promoted while the recording activity is visible over the keyguard. */
class QuickActionRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: QuickActionRecordingSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var experimental = false
    private var foreground = false
    private var destroyed = false
    private var latestStartId = 0
    private var recordingStartElapsed: Long? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as VoiceReminderApp
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.quick_action_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.quick_action_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        session = QuickActionRecordingSession(
            AndroidAudioRecorder(this), ShakeDraftStore(filesDir),
            save = { file, stoppedAt, triggerAt ->
                val container = app.container
                ShakeReminderCreator(container.reminderRepository, container.alarmScheduler, filesDir).create(
                    file, stoppedAt, container.settingsRepository.defaultFollowUpMinutes.first(), triggerAt
                )
            }, elapsed = SystemClock::elapsedRealtime, experimental = { experimental }
        )
        scope.launch { app.container.settingsRepository.experimentalVoiceEnhancementEnabled.collect { experimental = it } }
        scope.launch {
            session.state.collect { current ->
                android.util.Log.i("QuickRecording", "Recording state: ${current.phase}")
                mutableState.value = current
                if (destroyed) return@collect
                when (current.phase) {
                    QuickRecordingPhase.RECORDING, QuickRecordingPhase.SAVING -> {
                        if (current.phase == QuickRecordingPhase.RECORDING && recordingStartElapsed == null) {
                            recordingStartElapsed = SystemClock.elapsedRealtime()
                        }
                        manager.notify(NOTIFICATION_ID, notification(current))
                    }
                    QuickRecordingPhase.SAVED, QuickRecordingPhase.FAILED -> {
                        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
                        foreground = false
                        manager.notify(RESULT_ID, notification(current))
                        stopSelf(latestStartId)
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            while (true) {
                delay(200L)
                session.sampleAmplitude()
                val current = session.state.value
                if (current.phase == QuickRecordingPhase.RECORDING &&
                    SystemClock.elapsedRealtime() - (recordingStartElapsed ?: SystemClock.elapsedRealtime()) >= QuickActionRecordingSession.MAX_RECORDING_MS) {
                    session.stopAndSave()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action != ACTION_TOGGLE && intent?.action != ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP && session.state.value.phase != QuickRecordingPhase.RECORDING) {
            if (!foreground) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            if (!foreground) {
                val starting = notification(QuickRecordingState())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(NOTIFICATION_ID, starting, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else startForeground(NOTIFICATION_ID, starting)
                foreground = true
                getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:QuickRecording")
                    .apply { acquire(QuickActionRecordingSession.MAX_RECORDING_MS + 30_000L) }
            }
            scope.launch {
                if (intent.action == ACTION_STOP) session.stopAndSave() else session.toggle()
            }
        } catch (failure: Exception) {
            android.util.Log.w("QuickRecording", "Microphone service could not start", failure)
            mutableState.value = QuickRecordingState(QuickRecordingPhase.FAILED)
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification(mutableState.value))
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun notification(current: QuickRecordingState): Notification {
        val active = current.phase in listOf(QuickRecordingPhase.IDLE, QuickRecordingPhase.RECORDING, QuickRecordingPhase.SAVING)
        val message = when (current.phase) {
            QuickRecordingPhase.IDLE -> R.string.quick_action_starting
            QuickRecordingPhase.RECORDING -> R.string.quick_action_recording
            QuickRecordingPhase.SAVING -> R.string.quick_action_saving
            QuickRecordingPhase.SAVED -> if (current.scheduled) R.string.quick_action_saved else R.string.quick_action_schedule_failed
            QuickRecordingPhase.FAILED -> R.string.quick_action_failed
        }
        val target = if (active) Intent(this, QuickActionRecordingActivity::class.java).putExtra(EXTRA_CONTROLS_ONLY, true)
            else Intent(this, MainActivity::class.java).apply { current.reminderId?.let { putExtra("reminderId", it) } }
        val open = PendingIntent.getActivity(this, 501, target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(message))
            .setContentText(if (current.phase == QuickRecordingPhase.RECORDING) getString(R.string.quick_action_recording_detail) else getString(message))
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(if (active) NotificationCompat.CATEGORY_SERVICE else NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true).setSilent(true).setOngoing(active).setAutoCancel(!active)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (current.phase == QuickRecordingPhase.RECORDING) {
                    setWhen(current.startedAt).setUsesChronometer(true)
                    addAction(0, getString(R.string.quick_action_stop), PendingIntent.getService(
                        this@QuickActionRecordingService, 502,
                        Intent(this@QuickActionRecordingService, QuickActionRecordingService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    ))
                }
            }.build()
    }

    override fun onDestroy() {
        destroyed = true
        // A service teardown during capture still finalizes the take and persists it once.
        (application as VoiceReminderApp).applicationScope.launch(Dispatchers.Main.immediate) {
            try {
                session.stopAndSave()
                val final = session.state.first { it.phase != QuickRecordingPhase.SAVING }
                if (final.phase != QuickRecordingPhase.IDLE) mutableState.value = final
                if (final.phase == QuickRecordingPhase.SAVED || final.phase == QuickRecordingPhase.FAILED) {
                    getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification(final))
                }
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "quick_recording"
        private const val NOTIFICATION_ID = 7301
        private const val RESULT_ID = 7302
        private const val ACTION_TOGGLE = "com.ghostgramlabs.speakalert.quickrecord.TOGGLE"
        private const val ACTION_STOP = "com.ghostgramlabs.speakalert.quickrecord.STOP"
        const val EXTRA_CONTROLS_ONLY = "controlsOnly"
        private val mutableState = MutableStateFlow(QuickRecordingState())
        val state = mutableState.asStateFlow()

        fun toggle(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, QuickActionRecordingService::class.java).setAction(ACTION_TOGGLE))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, QuickActionRecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
