package com.ghostgramlabs.speakalert.shake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.MainActivity
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.audio.AndroidAudioRecorder
import com.ghostgramlabs.speakalert.data.repository.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

data class ShakeRecordingState(
    val listening: Boolean = false,
    val recording: Boolean = false,
    val saving: Boolean = false,
    val preparing: Boolean = false,
    val reminderId: Long? = null,
    val message: Int? = null,
    val mode: ShakeMode = ShakeMode.READY,
    val statusText: String? = null,
    val revision: Long = 0
)

data class ShakeLogEntry(val at: Long, val text: String)

/** Armed explicitly from a visible activity so microphone access survives locking the phone. */
class ShakeRecordingService : Service(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private lateinit var recorder: AndroidAudioRecorder
    private lateinit var drafts: ShakeDraftStore
    private var settings = ShakeSettings()
    private var detector = ShakeDetector()
    private var sequence = ShakeSequence(settings.sequenceGapMs.toLong())
    private var engine = ShakeActionEngine(settings)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var arming = false
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var recordingFile: File? = null
    private data class PendingTake(val file: File, val stoppedAt: Long, val silent: Boolean)
    private var pendingTake: PendingTake? = null
    private var armed = false
    private var saving = false
    private var stopping = false
    private var destroyed = false
    private var startedAt = 0L
    private var gestureStatus: String? = null
    private var lastActionText: String? = null
    private var scheduledTime: Long? = null
    private val autoStop = Runnable { finishRecording() }
    private val dispatchSequence = Runnable {
        if (!armed || saving) sequence.clear()
        else sequence.take(SystemClock.elapsedRealtime())?.let { count ->
            gestureStatus = null
            val mode = engine.mode
            val action = settings.action(mode, count)
            appendLog(if (count == 0) getString(R.string.shake_log_overflow) else
                getString(R.string.shake_log_gesture, count, getString(mode.labelRes()), getString(action.labelRes())))
            if (action == ShakeAction.NONE) {
                lastActionText = getString(if (count == 0) R.string.shake_log_overflow else R.string.shake_no_action)
                publish(state.value.message, state.value.reminderId)
                updateNotification()
            } else handleAction(action)
        }
        releaseIdleWakeLock()
    }
    private val finishWindow = object : Runnable {
        override fun run() {
            // A gesture begun within the window gets time to finish its sequence.
            if (sequence.pending) handler.postDelayed(this, 100L)
            else if (engine.expired(SystemClock.elapsedRealtime())) {
                appendLog(getString(R.string.shake_log_timeout))
                commitPending()
            }
        }
    }
    private val sampleAmplitude = object : Runnable {
        override fun run() {
            if (recordingFile == null) return
            // The first MediaRecorder amplitude read initializes its meter and returns zero.
            // Poll throughout the take so the silence check covers speech before the stop.
            recorder.getMaxAmplitude()
            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SensorManager::class.java)
        recorder = AndroidAudioRecorder(this)
        drafts = ShakeDraftStore(filesDir)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.shake_status_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.shake_status_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> if (!armed && !saving && !arming) arm()
            ACTION_SAVE -> if (armed) {
                if (recordingFile != null) finishRecording() else commitPending()
            } else if (!saving) stopSelf()
            ACTION_EDIT -> if (armed && !saving) handleAction(ShakeAction.ENTER_EDIT)
            ACTION_ADD -> if (armed && !saving) handleAction(ShakeAction.ADD_TIME)
            ACTION_SWITCH -> if (armed && !saving) handleAction(ShakeAction.SWITCH_MODE)
            ACTION_DISABLE -> {
                stopping = true
                armed = false
                sensors.unregisterListener(this)
                finishRecording()
                commitPending()
                publish(state.value.message, state.value.reminderId)
                if (saving) updateNotification()
                releaseWakeLock()
                if (!saving) stopSelf()
            }
            else -> if (!armed && !saving && !arming) stopSelf()
        }
        // Never restart microphone access from the background or at boot. The UI reflects this.
        return START_NOT_STICKY
    }

    private fun arm() {
        arming = true
        lastActionText = null
        gestureStatus = null
        scheduledTime = null
        publish()
        try {
            stopping = false
            val notification = notification(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            serviceScope.launch {
                try {
                    settings = ShakeSettingsStore(dataStore).settings.first()
                    if (stopping || destroyed) return@launch
                    detector = ShakeDetector(settings.thresholdG, settings.cooldownMs.toLong())
                    sequence = ShakeSequence(settings.sequenceGapMs.toLong())
                    engine = ShakeActionEngine(settings)
                    startSensors()
                } catch (failure: Exception) {
                    if (!destroyed) {
                        publish(message = R.string.shake_start_failed)
                        stopSelf()
                    }
                } finally {
                    arming = false
                    if (!destroyed) {
                        publish(state.value.message, state.value.reminderId)
                        if (armed) updateNotification()
                    }
                }
            }
        } catch (failure: Exception) {
            arming = false
            android.util.Log.w("ShakeRecording", "Unable to start listening", failure)
            publish(message = R.string.shake_start_failed)
            stopSelf()
        }
    }

    private fun startSensors() {
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            publish(message = R.string.shake_no_sensor)
            stopSelf()
            return
        }
        // Non-wake-up sensors stop delivering when the CPU sleeps. Release on every exit.
        if (!sensor.isWakeUpSensor) acquireWakeLock()
        check(sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME))
        detector.reset(SystemClock.elapsedRealtime())
        armed = true
        publish()
        updateNotification()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ShakeRecording")
            .apply {
                setReferenceCounted(false)
                // Listening is a user-controlled session with an ongoing Stop action.
                @Suppress("WakelockTimeout")
                acquire()
            }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!armed || saving) return
        if (detector.sample(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000L)) {
            // Flush an older group first if the main-thread timer was delayed.
            dispatchSequence.run()
            if (!armed || saving) return
            // Keep sequence classification alive when a wake-up sensor wakes a sleeping CPU.
            acquireWakeLock()
            sequence.add(SystemClock.elapsedRealtime())
            gestureStatus = getString(R.string.shake_recognizing, sequence.gestureCount)
            publish(state.value.message, state.value.reminderId)
            updateNotification()
            handler.removeCallbacks(dispatchSequence)
            handler.postDelayed(dispatchSequence, settings.sequenceGapMs.toLong())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun beginRecording() {
        if (recordingFile != null || pendingTake != null || saving) return
        var candidate: File? = null
        try {
            candidate = drafts.newRecording()
            // The robust encoder fallback also rejects incomplete files on stop. No enhancement
            // runs here: the original take is preserved, including a quiet recording.
            recorder.start(candidate, useExperimentalBehavior = true)
            recordingFile = candidate
            scheduledTime = null
            engine.recordingStarted()
            detector.reset(SystemClock.elapsedRealtime())
            acquireWakeLock() // Keep the five-minute deadline running even on wake-up sensors.
            startedAt = System.currentTimeMillis()
            handler.post(sampleAmplitude)
            handler.postDelayed(autoStop, MAX_RECORDING_MS)
            publish()
            updateNotification()
            vibrate(100L)
            if (settings.sounds) ShakeRecordingSound.started()
        } catch (failure: Exception) {
            android.util.Log.w("ShakeRecording", "Unable to start recording", failure)
            if (recordingFile != null) runCatching { recorder.stop() }
            recordingFile = null
            engine.finished()
            candidate?.delete()
            if (sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) != null) releaseWakeLock()
            publish(message = R.string.shake_record_failed)
            updateNotification()
        }
    }

    private fun finishRecording() {
        val file = recordingFile ?: return
        lastActionText = getString(R.string.shake_action_stop)
        sequence.clear()
        gestureStatus = null
        handler.removeCallbacks(dispatchSequence)
        val stoppedAt = System.currentTimeMillis()
        recordingFile = null
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(sampleAmplitude)
        detector.reset(SystemClock.elapsedRealtime())
        var message = R.string.shake_record_failed
        var completed: File? = null
        var silent = false
        try {
            val outcome = recorder.stop()
            if (outcome.file == null) {
                file.delete()
                message = R.string.shake_record_failed
            } else {
                completed = drafts.finish(file)
                silent = outcome.isSilent
                message = R.string.shake_saving
            }
        } catch (failure: Exception) {
            android.util.Log.w("ShakeRecording", "Unable to finalize recording", failure)
            // Preserve the source if finalizing fails (e.g. storage full).
            message = R.string.shake_record_failed
        }
        completed?.let {
            pendingTake = PendingTake(it, stoppedAt, silent)
            engine.recordingStopped(stoppedAt, SystemClock.elapsedRealtime())
            message = if (silent) R.string.shake_pending_quiet else R.string.shake_pending
            // Keep the entry/edit timers alive on screen-off devices with wake-up sensors.
            acquireWakeLock()
            scheduleWindow()
        }
        if (completed == null) engine.finished()
        publish(message)
        if (armed) updateNotification()
        vibrate(250L)
        if (settings.sounds) ShakeRecordingSound.stopped()
        if (!armed || destroyed) commitPending()
        releaseIdleWakeLock()
    }

    private fun handleAction(action: ShakeAction) {
        if (!engine.perform(action, System.currentTimeMillis(), SystemClock.elapsedRealtime())) return
        lastActionText = getString(action.labelRes())
        gestureStatus = null
        sequence.clear()
        handler.removeCallbacks(dispatchSequence)
        when (action) {
            ShakeAction.START_RECORDING -> beginRecording()
            ShakeAction.STOP_RECORDING -> finishRecording()
            ShakeAction.FINISH -> commitPending()
            else -> {
                scheduleWindow()
                publish(if (pendingTake?.silent == true) R.string.shake_pending_quiet else null)
                updateNotification()
                if (settings.sounds) ShakeRecordingSound.edited(engine.mode == ShakeMode.HOURS)
                vibrate(if (engine.mode == ShakeMode.HOURS) 220L else 80L)
            }
        }
    }

    private fun scheduleWindow() {
        handler.removeCallbacks(finishWindow)
        engine.deadline?.let { handler.postDelayed(finishWindow, (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L)) }
    }

    private fun commitPending() {
        val take = pendingTake ?: return
        lastActionText = getString(R.string.shake_finish_time)
        pendingTake = null
        gestureStatus = null
        val triggerAt = (engine.triggerAt ?: (take.stoppedAt + settings.defaultMinutes * 60_000L))
            .coerceAtLeast(System.currentTimeMillis() + 5_000L)
        engine.finished()
        sequence.clear()
        handler.removeCallbacks(dispatchSequence)
        handler.removeCallbacks(finishWindow)
        saving = true
        publish(R.string.shake_saving)
        if (armed) updateNotification()
        createReminder(take.file, take.stoppedAt, take.silent, triggerAt)
        releaseIdleWakeLock()
    }

    private fun createReminder(recording: File, stoppedAt: Long, silent: Boolean, triggerAt: Long) {
        val app = application as VoiceReminderApp
        // Keep the CPU awake for the short database/alarm handoff even after sensors stop.
        val saveWakeLock = runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ShakeReminderSave")
                .apply { acquire(30_000L) }
        }.getOrNull()
        app.applicationScope.launch(Dispatchers.Main.immediate) {
            var reminderId: Long? = null
            var message = R.string.shake_auto_save_failed
            try {
                val container = app.container
                val result = ShakeReminderCreator(container.reminderRepository, container.alarmScheduler, filesDir)
                    .create(recording, stoppedAt, container.settingsRepository.defaultFollowUpMinutes.first(), triggerAt)
                reminderId = result.reminder.id
                scheduledTime = result.reminder.nextTriggerAt
                appendLog(getString(R.string.shake_log_saved, formatTime(result.reminder.nextTriggerAt)))
                message = when {
                    !result.scheduled -> R.string.shake_schedule_failed
                    silent -> R.string.shake_scheduled_quiet
                    else -> R.string.shake_scheduled
                }
            } catch (failure: Exception) {
                android.util.Log.w("ShakeRecording", "Could not create shake reminder; keeping inbox take", failure)
            } finally {
                runCatching { if (saveWakeLock?.isHeld == true) saveWakeLock.release() }
                saving = false
                if (!destroyed) {
                    publish(message, reminderId)
                    if (stopping) stopSelf() else if (armed) updateNotification()
                }
            }
        }
    }

    private fun publish(message: Int? = null, reminderId: Long? = null) {
        mutableState.value = ShakeRecordingState(
            listening = armed, recording = recordingFile != null, saving = saving, preparing = arming,
            reminderId = reminderId, message = message, revision = mutableState.value.revision + 1,
            mode = engine.mode, statusText = pendingStatus()
        )
    }

    private fun formatTime(at: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

    private fun pendingStatus(): String? {
        val time = engine.triggerAt ?: return null
        return when (engine.mode) {
            ShakeMode.AFTER_RECORDING -> getString(R.string.shake_after_status, formatTime(time), settings.entryWindowSeconds)
            ShakeMode.MINUTES, ShakeMode.HOURS -> getString(R.string.shake_edit_status,
                getString(engine.mode.labelRes()), engine.stepMinutes, formatTime(time), settings.editTimeoutSeconds)
            else -> null
        }
    }

    private fun appendLog(text: String) {
        mutableLog.value = (listOf(ShakeLogEntry(System.currentTimeMillis(), text)) + mutableLog.value).take(30)
    }

    private fun vibrate(duration: Long) {
        if (!settings.vibration) return
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(recordingFile != null))
        }
    }

    private fun notification(recording: Boolean): Notification {
        val reminderId = state.value.reminderId
        val target = if (reminderId != null) {
            Intent(this, MainActivity::class.java).putExtra("reminderId", reminderId)
        } else Intent(this, ShakeRecordingsActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val saved = state.value.message == R.string.shake_scheduled || state.value.message == R.string.shake_scheduled_quiet
        val detail = when {
            arming -> getString(R.string.shake_preparing)
            saving -> getString(R.string.shake_saving)
            recording -> getString(R.string.shake_recording_notification)
            state.value.statusText != null -> state.value.statusText!!
            saved && scheduledTime != null -> getString(R.string.shake_scheduled_status, formatTime(scheduledTime!!)) +
                if (state.value.message == R.string.shake_scheduled_quiet) " " + getString(R.string.shake_quiet_notice) else ""
            else -> getString(state.value.message ?: R.string.shake_ready_notification)
        }
        val text = listOfNotNull(gestureStatus, detail).joinToString("\n")
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(when {
                arming -> R.string.shake_preparing
                recording -> R.string.shake_recording
                saving -> R.string.shake_saving
                pendingTake != null -> engine.mode.labelRes()
                saved -> R.string.shake_reminder_scheduled
                else -> R.string.shake_ready
            }))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(lastActionText)
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(recording)
            .setOnlyAlertOnce(true)
            // Explicitly suppress notification alerts, including on existing installations.
            // Optional recording/edit cues are controlled separately by the shake settings.
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (recording) {
            builder.setWhen(startedAt).setUsesChronometer(true)
                .addAction(0, getString(R.string.shake_save), command(ACTION_SAVE, 1))
        }
        if (pendingTake != null) {
            builder.addAction(0, getString(R.string.shake_finish_time), command(ACTION_SAVE, 1))
            if (engine.mode == ShakeMode.AFTER_RECORDING) {
                builder.addAction(0, getString(R.string.shake_action_enter), command(ACTION_EDIT, 3))
            }
        }
        builder.addAction(0, getString(R.string.shake_disable), command(ACTION_DISABLE, 2))
        return builder.build()
    }

    private fun command(action: String, request: Int): PendingIntent = PendingIntent.getService(
        this, request, Intent(this, ShakeRecordingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun releaseIdleWakeLock() {
        if (recordingFile == null && pendingTake == null && !sequence.pending &&
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) != null) releaseWakeLock()
    }

    override fun onDestroy() {
        destroyed = true
        armed = false
        sensors.unregisterListener(this)
        finishRecording()
        commitPending()
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        mutableState.value = mutableState.value.copy(
            listening = false, recording = false, saving = false, preparing = false,
            message = if (saving) null else mutableState.value.message,
            mode = ShakeMode.READY, statusText = null
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "shake_recording"
        private const val NOTIFICATION_ID = 941_071
        private const val ACTION_ARM = "shake.ARM"
        private const val ACTION_SAVE = "shake.SAVE"
        private const val ACTION_DISABLE = "shake.DISABLE"
        private const val ACTION_EDIT = "shake.EDIT"
        private const val ACTION_ADD = "shake.ADD"
        private const val ACTION_SWITCH = "shake.SWITCH"
        private const val MAX_RECORDING_MS = 5 * 60 * 1_000L
        private val mutableState = MutableStateFlow(ShakeRecordingState())
        val state = mutableState.asStateFlow()
        private val mutableLog = MutableStateFlow<List<ShakeLogEntry>>(emptyList())
        val actionLog = mutableLog.asStateFlow()
        fun clearLog() { mutableLog.value = emptyList() }

        /** Call only after microphone permission is granted, from a resumed activity. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ShakeRecordingService::class.java).setAction(ACTION_ARM))
            } catch (_: Exception) {
                mutableState.value = ShakeRecordingState(message = R.string.shake_start_failed)
            }
        }

        fun stop(context: Context) {
            if (state.value.listening || state.value.saving || state.value.preparing) {
                context.startService(Intent(context, ShakeRecordingService::class.java).setAction(ACTION_DISABLE))
            }
        }

        fun save(context: Context) {
            if (state.value.listening) context.startService(Intent(context, ShakeRecordingService::class.java).setAction(ACTION_SAVE))
        }

        fun edit(context: Context) = sendEditorCommand(context, ACTION_EDIT)
        fun addTime(context: Context) = sendEditorCommand(context, ACTION_ADD)
        fun switchMode(context: Context) = sendEditorCommand(context, ACTION_SWITCH)
        private fun sendEditorCommand(context: Context, action: String) {
            if (state.value.listening) context.startService(Intent(context, ShakeRecordingService::class.java).setAction(action))
        }
    }
}
