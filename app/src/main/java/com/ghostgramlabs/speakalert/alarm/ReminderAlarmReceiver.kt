package com.ghostgramlabs.speakalert.alarm

import android.annotation.SuppressLint
import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.data.model.MissedReminderEntity
import com.ghostgramlabs.speakalert.domain.RecurrenceUtils
import com.ghostgramlabs.speakalert.domain.models.MissedPolicy
import com.ghostgramlabs.speakalert.domain.models.RecurrenceType
import com.ghostgramlabs.speakalert.domain.models.TimeUnit
import com.ghostgramlabs.speakalert.service.ReminderPlaybackService
import com.ghostgramlabs.speakalert.util.APP_DISPLAY_NAME
import com.ghostgramlabs.speakalert.util.FileLogger
import com.ghostgramlabs.speakalert.util.FullScreenIntentSupport
import com.ghostgramlabs.speakalert.util.PrivateAudioRoute
import com.ghostgramlabs.speakalert.util.ReminderAudioSource
import com.ghostgramlabs.speakalert.util.isDefaultAppDisplayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReminderAlarmReceiver : BroadcastReceiver() {

    companion object {
        // Grace windows for determining if a reminder is "late"
        private const val GRACE_WINDOW_INTERVAL_MS = 60 * 1000L       // 60 seconds for minutes/hours
        private const val GRACE_WINDOW_CALENDAR_MS = 5 * 60 * 1000L  // 5 minutes for daily/weekly/monthly
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra("reminderId", -1L)
        if (reminderId == -1L) {
            FileLogger.log("ALARM: Received but no reminderId")
            return
        }

        FileLogger.log("ALARM: Received for reminder $reminderId")
        Log.d("VoiceReminder", "Alarm Received for reminder $reminderId")
        
        val notificationHelper = NotificationHelper(context)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                FileLogger.log("ALARM: Starting coroutine processing")
                
                val app = context.applicationContext as VoiceReminderApp
                val repository = app.container.reminderRepository
                val settingsRepository = app.container.settingsRepository
                val scheduler = app.container.alarmScheduler
                val missedRepository = app.container.missedReminderRepository

                FileLogger.log("ALARM: Got app components")

                val reminder = repository.getReminder(reminderId)
                if (reminder == null) {
                    // Deleted or expired reminders can still have an alarm already in flight.
                    // Those stale broadcasts must not create a new "missing reminder" alert.
                    FileLogger.log("ALARM: Ignoring deleted or expired reminder $reminderId")
                    return@launch
                }

                FileLogger.log("ALARM: Found reminder - title='${reminder.title}', recurrenceType=${reminder.recurrenceType}")

                val now = System.currentTimeMillis()
                val scheduledTime = intent.getLongExtra(
                    "fireTime",
                    reminder.snoozeUntil ?: reminder.nextTriggerAt
                )
                val isFollowUpTrigger = intent.getBooleanExtra("isFollowUpAlarm", false) ||
                    (reminder.pendingFollowUpAt != null && reminder.pendingFollowUpAt == scheduledTime)
                if (shouldIgnoreReminderAlarm(reminder, scheduledTime, isFollowUpTrigger)) {
                    FileLogger.log("ALARM: Ignoring completed reminder or stale follow-up for $reminderId at $scheduledTime")
                    return@launch
                }
                val isSnoozeTrigger = !isFollowUpTrigger &&
                    reminder.snoozeUntil != null &&
                    reminder.snoozeUntil == scheduledTime
                
                // CRITICAL FIX: explicit check to prevent double-firing (e.g. after reboot)
                // If we already fired for this scheduled time (or later), do not fire again.
                // We use a small buffer (1 second) just in case of minor timestamp differences, but strictly logic is >=
                val lastFiredAt = reminder.lastFiredAt
                if (lastFiredAt != null && lastFiredAt >= scheduledTime) {
                    FileLogger.log("ALARM: Reminder $reminderId already fired at $lastFiredAt (>= $scheduledTime). Skipping.")
                    return@launch
                }

                val delay = now - scheduledTime
                val graceWindow = getGraceWindow(reminder.recurrenceType, reminder.recurrenceJson)
                val isLate = delay > graceWindow
                
                FileLogger.log("ALARM: scheduledTime=$scheduledTime, now=$now, delay=${delay}ms, graceWindow=${graceWindow}ms, isLate=$isLate")

                // Get missed policy from recurrence model (default: SKIP_TO_NEXT)
                val recurrenceModel = RecurrenceUtils.fromJson(reminder.recurrenceType, reminder.recurrenceJson)
                val settingsDefaultPolicy = parseMissedPolicy(settingsRepository.defaultMissedPolicy.first())
                val recurrencePolicy = recurrenceModel?.missedPolicy?.takeIf {
                    it != MissedPolicy.SKIP_TO_NEXT || settingsDefaultPolicy == MissedPolicy.SKIP_TO_NEXT
                }
                val missedPolicy = recurrencePolicy ?: reminder.missedPolicy.takeIf {
                    // For legacy reminders that still have the model default persisted, prefer app setting.
                    it != MissedPolicy.SKIP_TO_NEXT || settingsDefaultPolicy == MissedPolicy.SKIP_TO_NEXT
                } ?: settingsDefaultPolicy
                val toneOnlyMode = settingsRepository.toneOnlyMode.first()
                val toneOnlyAlertToneUri = settingsRepository.toneOnlyAlertToneUri.first()
                val privatePlaybackEnabled = settingsRepository.privatePlaybackEnabled.first()
                val dndBypassEnabled = settingsRepository.dndBypassEnabled.first()
                val loopTimeoutMinutes = settingsRepository.loopTimeoutMinutes.first()
                val fullScreenAlertEnabled = settingsRepository.fullScreenAlertEnabled.first()
                val persistUntilDone = settingsRepository.persistUntilDone.first()
                val audioPath = reminder.audioPath
                val hasAudioConfigured = !audioPath.isNullOrBlank()
                val hasAudio = ReminderAudioSource.isPlayable(context, audioPath)
                val hasText = !reminder.reminderText.isNullOrBlank()
                
                FileLogger.log("ALARM: missedPolicy=$missedPolicy")

                
                // ===== DND & QUIET TIME CHECK =====
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val interruptionFilter = notificationManager?.currentInterruptionFilter
                    ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                val isDndActive = interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                    interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                val silenceForDnd = isDndActive && !dndBypassEnabled
                
                val quietTimeEnabled = settingsRepository.quietTimeEnabled.first()
                var isQuietTime = false
                if (quietTimeEnabled) {
                    val startHour = settingsRepository.quietTimeStartHour.first()
                    val startMinute = settingsRepository.quietTimeStartMinute.first()
                    val endHour = settingsRepository.quietTimeEndHour.first()
                    val endMinute = settingsRepository.quietTimeEndMinute.first()
                    
                    if (isTimeInWindow(now, startHour, startMinute, endHour, endMinute)) {
                        isQuietTime = true
                    }
                }
                
                // An explicit, time-boxed "not until X" the user set minutes ago. It outranks the
                // DND bypass, which is a standing preference about loudness rather than about
                // whether the reminder should happen at all. Suppression reuses the quiet-hours
                // path below, so a paused reminder lands in Missed and each reminder's own
                // MissedPolicy still decides whether it catches up afterwards.
                val pauseUntil = settingsRepository.pauseRemindersUntil.first()
                val isPaused = pauseUntil > now
                if (isPaused) {
                    FileLogger.log("ALARM: Paused by user until $pauseUntil (now=$now)")
                }

                if (silenceForDnd) {
                    FileLogger.log("ALARM: Silenced by DND because DND bypass setting is off")
                } else if (isDndActive) {
                    FileLogger.log("ALARM: DND is active; DND bypass setting is on, trying reminder alert")
                }

                if (isPaused || isQuietTime || silenceForDnd) {
                    val silenceReason = when {
                        isPaused -> "Pause"
                        silenceForDnd -> "DND"
                        else -> "Quiet Time"
                    }
                    FileLogger.log("ALARM: Silenced by $silenceReason ($now)")
                    
                    // Add to Missed Inbox
                    val missedEntry = MissedReminderEntity(
                        reminderId = reminder.id,
                        title = buildMissedDisplayTitle(context, reminder.title, reminder.reminderText),
                        scheduledTime = scheduledTime,
                        detectedTime = now,
                        reminderText = reminder.reminderText
                    )
                    missedRepository.insertMissedReminder(missedEntry)
                    
                    // Schedule Next Recurrence
                    var updatedReminder = reminder.copy(
                        lastFiredAt = now,
                        snoozeUntil = null,
                        pendingFollowUpAt = null
                    )
                    FollowUpAlarmScheduler.cancel(context, reminder.id)
                    if (!isFollowUpTrigger) {
                        updatedReminder = RecurrenceUtils.updateForNextOccurrence(updatedReminder)
                        val nextTrigger = RecurrenceUtils.computeNextTrigger(updatedReminder, now)
                        
                        if (nextTrigger != null) {
                             updatedReminder = updatedReminder.copy(nextTriggerAt = nextTrigger)
                             scheduler.schedule(updatedReminder)
                             FileLogger.log("ALARM: $silenceReason - Scheduled next at $nextTrigger")
                        } else {
                            // One-time reminders stay active until explicit Done/Dismiss.
                            if (reminder.recurrenceType == RecurrenceType.NONE) {
                                FileLogger.log("ALARM: $silenceReason - One-time reminder kept active (awaiting Done/Dismiss)")
                            }
                        }
                    }
                    repository.updateReminder(updatedReminder)
                    
                    return@launch // EXIT - No notification or playback
                }

                // Handle late/missed reminders (Existing Logic)
                if (isLate && reminder.recurrenceType != RecurrenceType.NONE && !isFollowUpTrigger) {
                    FileLogger.log("ALARM: Reminder is LATE (missed)")
                    
                    when (missedPolicy) {
                        MissedPolicy.SKIP_TO_NEXT -> {
                            // Log to missed inbox, skip notification, schedule next
                            FileLogger.log("ALARM: SKIP_TO_NEXT - logging to missed inbox and scheduling next")
                            
                            // Add to missed inbox
                            val missedEntry = MissedReminderEntity(
                                reminderId = reminder.id,
                                title = buildMissedDisplayTitle(context, reminder.title, reminder.reminderText),
                                scheduledTime = scheduledTime,
                                detectedTime = now,
                                reminderText = reminder.reminderText
                            )
                            missedRepository.insertMissedReminder(missedEntry)
                            
                            // Schedule next occurrence without showing notification
                            var updatedReminder = reminder.copy(
                                lastFiredAt = now,
                                snoozeUntil = null,
                                pendingFollowUpAt = null
                            )
                            FollowUpAlarmScheduler.cancel(context, reminder.id)
                            updatedReminder = RecurrenceUtils.updateForNextOccurrence(updatedReminder)
                            val nextTrigger = RecurrenceUtils.computeNextTrigger(updatedReminder, now)
                            
                            if (nextTrigger != null) {
                                updatedReminder = updatedReminder.copy(nextTriggerAt = nextTrigger)
                                scheduler.schedule(updatedReminder)
                                FileLogger.log("ALARM: Scheduled next occurrence at $nextTrigger")
                            }
                            repository.updateReminder(updatedReminder)
                            
                            return@launch // Exit early - no notification
                        }
                        
                        MissedPolicy.FIRE_ON_RESUME -> {
                            // Show "Missed" notification (no autoplay), then schedule next
                            FileLogger.log("ALARM: FIRE_ON_RESUME - showing missed notification")
                            
                            val missedNotificationShown = withContext(Dispatchers.Main) {
                                val strings = com.ghostgramlabs.speakalert.util.AppLocale.localizedContext(context)
                                notificationHelper.showNotification(
                                    reminder.id,
                                    strings.getString(
                                        R.string.notif_missed_title,
                                        buildMissedDisplayTitle(context, reminder.title, reminder.reminderText)
                                    ),
                                    if (hasAudioConfigured && !hasAudio && !hasText) {
                                        strings.getString(R.string.notif_missed_scheduled_audio_unavailable, formatTime(scheduledTime))
                                    } else {
                                        strings.getString(R.string.notif_missed_scheduled_tap, formatTime(scheduledTime))
                                    },
                                    audioPath = if (hasAudio) audioPath else null,
                                    reminderText = if (hasText) reminder.reminderText else null,
                                    autoplayOnTap = false,
                                    toneOnlyMode = toneOnlyMode,
                                    dndBypassEnabled = dndBypassEnabled,
                                    playingSound = toneOnlyMode,
                                    persistUntilDone = persistUntilDone
                                )
                            }
                            if (toneOnlyMode && missedNotificationShown) {
                                ToneAlertPlayer.start(context, loopTimeoutMinutes, toneOnlyAlertToneUri, dndBypass = dndBypassEnabled)
                            }
                            
                            // Still schedule next occurrence
                            var updatedReminder = reminder.copy(
                                lastFiredAt = now,
                                snoozeUntil = null,
                                pendingFollowUpAt = null
                            )
                            FollowUpAlarmScheduler.cancel(context, reminder.id)
                            updatedReminder = RecurrenceUtils.updateForNextOccurrence(updatedReminder)
                            val nextTrigger = RecurrenceUtils.computeNextTrigger(updatedReminder, now)
                            
                            if (nextTrigger != null) {
                                updatedReminder = updatedReminder.copy(nextTriggerAt = nextTrigger)
                                scheduler.schedule(updatedReminder)
                            }
                            repository.updateReminder(updatedReminder)
                            
                            return@launch // Exit - showed missed notification, no autoplay
                        }
                    }
                }

                // ===== NORMAL ON-TIME REMINDER HANDLING =====
                
                // Get settings
                val autoPlayEnabled = settingsRepository.autoPlayEnabled.first()
                val unlockedOnly = settingsRepository.autoPlayOnUnlockOnly.first()
                val speakText = settingsRepository.speakTextIfNoVoice.first()
                
                FileLogger.log("ALARM: Settings - autoPlay=$autoPlayEnabled, unlockedOnly=$unlockedOnly, speakText=$speakText, toneOnlyMode=$toneOnlyMode")
                
                val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val isLocked = keyguardManager.isKeyguardLocked
                // A keyguard is not the same thing as a screen the user cannot see. It reports
                // unlocked on a device with no PIN or pattern, and on one that Smart Lock has
                // opened for a trusted place or watch - yet the screen is still dark and the
                // reminder still needs to take it over. Ask whether the screen is on instead of
                // inferring it from the lock.
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val screenOff = !powerManager.isInteractive
                
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val telecomInCall = isTelecomInCall(context)
                // Avoid false positives from MODE_IN_COMMUNICATION on some devices.
                val inCall = telecomInCall || audioManager.mode == AudioManager.MODE_IN_CALL

                FileLogger.log("ALARM: State - locked=$isLocked, inCall=$inCall, telecomInCall=$telecomInCall, audioMode=${audioManager.mode}, hasAudioConfigured=$hasAudioConfigured, hasAudio=$hasAudio, hasText=$hasText")
                val useLockScreenFullScreen = fullScreenAlertEnabled && (isLocked || screenOff)
                // Everything here has to hold for the alert to take over the screen, and when it
                // does not the notification quietly degrades to a normal one. Record which part
                // failed: on Android 14+ the system revokes full-screen access by default, so
                // "enabled in the app" and "allowed by the system" are different answers.
                FileLogger.log(
                    "ALARM: Full-screen - setting=$fullScreenAlertEnabled, locked=$isLocked, " +
                        "screenOff=$screenOff, " +
                        "systemAllows=${FullScreenIntentSupport.canUseFullScreenIntent(context)}, " +
                        "using=$useLockScreenFullScreen"
                )
                
                val alertPayload = buildReminderAlertPayload(
                    reminder = reminder,
                    isFollowUpTrigger = isFollowUpTrigger,
                    hasPlayableAudio = hasAudio,
                    hasAudioConfigured = hasAudioConfigured,
                    strings = AlertStrings.from(context)
                )

                // ===== ANDROID 15 BOOT & AUTOPLAY LOGIC =====
                val isBootReschedule = intent.getBooleanExtra("isBootReschedule", false)
                
                // GUARD: Check if we are too close to boot time (2 minutes)
                // If the app is still in the "boot completion" window, we must NOT start
                // a mediaPlayback foreground service.
                val lastBoot = settingsRepository.lastBootTimestamp.first()
                val hasBootTimestamp = lastBoot > 0L && lastBoot <= now
                val timeSinceBoot = if (hasBootTimestamp) now - lastBoot else Long.MAX_VALUE
                val isCloseToBoot = hasBootTimestamp && timeSinceBoot < 120_000L // 2 minutes
                val isAndroid15OrAbove = Build.VERSION.SDK_INT >= 35

                // ANDROID 15+ RESTRICTION: Block autoplay only while still in the boot window.
                // isBootReschedule is retained for diagnostics and for identifying boot-origin alarms.
                val bootBlocked = isAndroid15OrAbove && isBootReschedule && isCloseToBoot
                
                if (bootBlocked) {
                    FileLogger.log("ALARM: Autoplay BLOCKED. android15+=$isAndroid15OrAbove, isBootReschedule=$isBootReschedule, timeSinceBoot=${timeSinceBoot/1000}s (threshold 120s)")
                }
                
                val canAutoPlay = shouldAutoPlayReminder(
                    autoPlayEnabled = autoPlayEnabled,
                    inCall = inCall,
                    unlockedOnly = unlockedOnly,
                    isLocked = isLocked,
                    playbackAudioPath = alertPayload.playbackAudioPath,
                    playbackText = alertPayload.playbackText,
                    isFollowUpTrigger = isFollowUpTrigger,
                    speakTextIfNoVoice = speakText,
                    bootBlocked = bootBlocked,
                    toneOnlyMode = toneOnlyMode
                )
                
                FileLogger.log("ALARM: android15+=$isAndroid15OrAbove, bootReschedule=$isBootReschedule, hasBootTs=$hasBootTimestamp, closeToBoot=$isCloseToBoot, bootBlocked=$bootBlocked, canAutoPlay=$canAutoPlay")

                val notificationShown = if (canAutoPlay) {
                    FileLogger.log("ALARM: Attempting to start service for autoplay")
                    val playbackRequested = try {
                        withContext(Dispatchers.Main) {
                            if (alertPayload.playbackAudioPath != null) {
                                FileLogger.log("ALARM: Starting service with audio: ${alertPayload.playbackAudioPath}, loop=${reminder.loopPlayback}")
                                ReminderPlaybackService.start(
                                    context = context,
                                    id = reminder.id,
                                    title = alertPayload.title,
                                    audioPath = alertPayload.playbackAudioPath,
                                    ttsText = null,
                                    loop = reminder.loopPlayback,
                                    isFromBootContext = bootBlocked,
                                    privatePlayback = privatePlaybackEnabled,
                                    dndBypass = dndBypassEnabled
                                )
                            } else if (!alertPayload.playbackText.isNullOrBlank()) {
                                FileLogger.log("ALARM: Starting service with TTS, loop=${reminder.loopPlayback}")
                                ReminderPlaybackService.start(
                                    context = context,
                                    id = reminder.id,
                                    title = alertPayload.title,
                                    audioPath = null,
                                    ttsText = alertPayload.playbackText,
                                    loop = reminder.loopPlayback,
                                    isFromBootContext = bootBlocked,
                                    privatePlayback = privatePlaybackEnabled,
                                    dndBypass = dndBypassEnabled
                                )
                            } else {
                                false
                            }
                        }
                    } catch (e: Exception) {
                        // Catches ForegroundServiceStartNotAllowedException or any SecurityException
                        FileLogger.logError("ALARM", "Failed to request playback service", e)
                        // Note: If service fails, the notification is still shown below, fulfilling the "Tap to play" fallback.
                        false
                    }
                    FileLogger.log("ALARM: Playback launch accepted=$playbackRequested")
                    
                    // Keep the alert visible even when Android rejects the playback launch.
                    val shown = withContext(Dispatchers.Main) {
                        notificationHelper.showNotification(
                            reminder.id,
                            alertPayload.title,
                            alertPayload.message,
                            audioPath = alertPayload.playbackAudioPath,
                            reminderText = alertPayload.playbackText,
                            autoplayOnTap = alertPayload.autoplayOnTap,
                            toneOnlyMode = toneOnlyMode,
                            useFullScreenAlert = useLockScreenFullScreen,
                            isFollowUpAlert = alertPayload.isFollowUpAlert,
                            dndBypassEnabled = dndBypassEnabled,
                            silentAlert = playbackRequested && (!privatePlaybackEnabled ||
                                PrivateAudioRoute.hasExternalPrivateRoute(context)),
                            // A rejected launch must retain the normal notification sound and Play action.
                            playingSound = playbackRequested,
                            persistUntilDone = persistUntilDone
                        )
                    }
                    FileLogger.log("ALARM: Notification shown=$shown, playback launch accepted=$playbackRequested")
                    shown
                } else {
                    FileLogger.log("ALARM: Showing standard notification (no autoplay)")
                    val shown = withContext(Dispatchers.Main) {
                        notificationHelper.showNotification(
                            reminder.id,
                            alertPayload.title,
                            alertPayload.message,
                            audioPath = alertPayload.playbackAudioPath,
                            reminderText = alertPayload.playbackText,
                            autoplayOnTap = alertPayload.autoplayOnTap,
                            toneOnlyMode = toneOnlyMode,
                            useFullScreenAlert = useLockScreenFullScreen,
                            isFollowUpAlert = alertPayload.isFollowUpAlert,
                            dndBypassEnabled = dndBypassEnabled,
                            silentAlert = false,
                            // No autoplay here; only a tone-only alarm makes sound, otherwise keep Play.
                            playingSound = toneOnlyMode,
                            persistUntilDone = persistUntilDone
                        )
                    }
                    FileLogger.log("ALARM: Notification shown: $shown")
                    shown
                }

                if (toneOnlyMode && notificationShown) {
                    ToneAlertPlayer.start(context, loopTimeoutMinutes, toneOnlyAlertToneUri, dndBypass = dndBypassEnabled)
                }

                // Update state and AUTO-RESCHEDULE for recurring reminders
                // This ensures the next trigger time is reflected in the UI immediately
                var updatedReminder = reminder.copy(
                    lastFiredAt = now,
                    snoozeUntil = null,
                    pendingFollowUpAt = null
                )
                if (isFollowUpTrigger) {
                    FollowUpAlarmScheduler.cancel(context, reminder.id)
                }
                
                if (isFollowUpTrigger) {
                    FileLogger.log("ALARM: Follow-up trigger handled without advancing recurrence")
                } else if (reminder.recurrenceType != RecurrenceType.NONE) {
                    FileLogger.log("ALARM: Recurring reminder - auto-rescheduling")
                    // Advance recurrence count if needed
                    updatedReminder = RecurrenceUtils.updateForNextOccurrence(updatedReminder)
                    // Compute next trigger time
                    val nextTrigger = RecurrenceUtils.computeNextTrigger(updatedReminder, now)
                    if (nextTrigger != null) {
                        updatedReminder = updatedReminder.copy(nextTriggerAt = nextTrigger)
                        scheduler.schedule(updatedReminder)
                        FileLogger.log("ALARM: Auto-rescheduled next occurrence at $nextTrigger")
                    } else {
                        // Recurrence ended
                        updatedReminder = updatedReminder.copy(isCompleted = true, completedAt = now)
                        scheduler.cancel(updatedReminder)
                        FileLogger.log("ALARM: Recurrence ended, marked completed")
                    }
                } else {
                    // One-time reminders are not auto-completed on fire.
                    // Completion happens on explicit user action (Done/Dismiss).
                    if (notificationShown) {
                        FileLogger.log("ALARM: One-time reminder fired - awaiting Done/Dismiss")
                    } else {
                        FileLogger.log("ALARM: One-time reminder - notification failed, logging as missed")
                        missedRepository.insertMissedReminder(
                            com.ghostgramlabs.speakalert.data.model.MissedReminderEntity(
                                reminderId = reminder.id,
                                title = buildMissedDisplayTitle(context, reminder.title, reminder.reminderText),
                                scheduledTime = scheduledTime,
                                reminderText = reminder.reminderText
                            )
                        )
                    }
                }

                if (reminder.followUpCheckMinutes > 0 && !updatedReminder.isCompleted) {
                    // Keep exactly one follow-up alive. This matters most after Snooze:
                    // the snoozed alert becomes the new active cycle and replaces any older follow-up.
                    FollowUpAlarmScheduler.cancel(context, reminder.id)
                    // A normal/snooze fire starts a fresh follow-up cycle; a follow-up fire counts
                    // toward the cap. Limit 0 means "until done" — no cap, matching pre-2.0.32
                    // behavior; Done/Snooze/Quiet Time still end the cycle.
                    val firedSoFar = if (isFollowUpTrigger) reminder.followUpFireCount + 1 else 0
                    val followUpMaxRepeats = settingsRepository.followUpMaxRepeats.first()
                    if (isFollowUpTrigger && followUpMaxRepeats > 0 && firedSoFar >= followUpMaxRepeats) {
                        updatedReminder = updatedReminder.copy(
                            pendingFollowUpAt = null,
                            followUpFireCount = firedSoFar
                        )
                        FileLogger.log(
                            "ALARM: Follow-up repeat limit ($followUpMaxRepeats) reached for ${reminder.id}; stopping follow-ups"
                        )
                    } else {
                        val followUpBaseTime = maxOf(now, scheduledTime)
                        val followUpAt = com.ghostgramlabs.speakalert.util.DateUtils.normalizeToMinute(
                            followUpBaseTime + reminder.followUpCheckMinutes * 60 * 1000L
                        )
                        updatedReminder = updatedReminder.copy(
                            pendingFollowUpAt = followUpAt,
                            followUpFireCount = firedSoFar
                        )
                        FollowUpAlarmScheduler.schedule(context, reminder.id, followUpAt)
                        FileLogger.log(
                            when {
                                isFollowUpTrigger -> "ALARM: Scheduled follow-up check ${firedSoFar + 1}/${if (followUpMaxRepeats > 0) "$followUpMaxRepeats" else "until-done"} at $followUpAt"
                                isSnoozeTrigger -> "ALARM: Scheduled post-snooze follow-up check at $followUpAt"
                                else -> "ALARM: Scheduled follow-up check at $followUpAt"
                            }
                        )
                    }
                }
                
                repository.updateReminder(updatedReminder)
                FileLogger.log("ALARM: Database updated")

                // Review pacing must never affect delivery or recurrence scheduling.
                // Skipped occurrences, snoozes and follow-ups do not earn extra usage days.
                if (notificationShown && !isFollowUpTrigger && !isSnoozeTrigger) {
                    try {
                        settingsRepository.recordReminderDelivery(now)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLogger.logError("REVIEW", "Could not record reminder usage", e)
                    }
                }
                
            } catch (e: Exception) {
                FileLogger.logError("ALARM", "Error processing alarm", e)
                try {
                    withContext(Dispatchers.Main) {
                        notificationHelper.showNotification(
                            reminderId,
                            APP_DISPLAY_NAME,
                            "Error: ${e.message?.take(50) ?: "Unknown"}",
                            autoplayOnTap = false
                        )
                    }
                    FileLogger.log("ALARM: Showed error notification")
                } catch (e2: Exception) {
                    FileLogger.logError("ALARM", "Failed to show error notification", e2)
                }
            } finally {
                FileLogger.log("ALARM: Processing complete, finishing pending result")
                pendingResult.finish()
            }
        }
    }

    /**
     * Returns the grace window in milliseconds based on recurrence type.
     * - Interval-based (minutes/hours): 60 seconds
     * - Calendar-based (daily/weekly/monthly): 5 minutes
     */
    private fun getGraceWindow(recurrenceType: RecurrenceType, recurrenceJson: String?): Long {
        if (recurrenceType == RecurrenceType.NONE) {
            // One-time reminders: always show even if late
            return Long.MAX_VALUE
        }
        
        if (recurrenceType == RecurrenceType.CUSTOM) {
            // Check if it's interval-based (minutes/hours)
            val model = RecurrenceUtils.fromJson(recurrenceType, recurrenceJson)
            if (model is com.ghostgramlabs.speakalert.domain.models.RecurrenceModel.Custom) {
                return when (model.unit) {
                    TimeUnit.MINUTES, TimeUnit.HOURS -> GRACE_WINDOW_INTERVAL_MS
                    else -> GRACE_WINDOW_CALENDAR_MS
                }
            }
        }
        
        // Daily, Weekly, Monthly use calendar-based grace window
        return GRACE_WINDOW_CALENDAR_MS
    }

    private fun formatTime(millis: Long): String {
        val sdf = java.text.SimpleDateFormat(com.ghostgramlabs.speakalert.util.TimeFormat.timePattern, java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }

    private fun parseMissedPolicy(raw: String): MissedPolicy {
        return when (raw) {
            "FIRE_ON_RESUME", "FIRE" -> MissedPolicy.FIRE_ON_RESUME
            "SKIP_TO_NEXT", "SKIP" -> MissedPolicy.SKIP_TO_NEXT
            else -> MissedPolicy.SKIP_TO_NEXT
        }
    }

    private fun buildMissedDisplayTitle(context: Context, title: String?, reminderText: String?): String {
        val userTitle = title
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.isDefaultAppDisplayName() }
        if (userTitle != null) return userTitle

        val textFallback = reminderText
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { text ->
                val words = text.split(Regex("\\s+"))
                if (words.size > 8) words.take(8).joinToString(" ") else text
            }
        return textFallback ?: com.ghostgramlabs.speakalert.util.AppLocale.localizedContext(context)
            .getString(R.string.fallback_reminder_title)
    }

    @SuppressLint("MissingPermission")
    private fun isTelecomInCall(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecomManager?.isInCall == true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isTimeInWindow(now: Long, startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = cal.get(java.util.Calendar.MINUTE)
        
        val currentTotalMinutes = currentHour * 60 + currentMinute
        val startTotalMinutes = startHour * 60 + startMinute
        val endTotalMinutes = endHour * 60 + endMinute
        
        return if (startTotalMinutes <= endTotalMinutes) {
            // Standard range (e.g. 9 AM to 5 PM)
            currentTotalMinutes in startTotalMinutes until endTotalMinutes
        } else {
            // Crosses midnight (e.g. 10 PM to 7 AM)
            currentTotalMinutes >= startTotalMinutes || currentTotalMinutes < endTotalMinutes
        }
    }
}
