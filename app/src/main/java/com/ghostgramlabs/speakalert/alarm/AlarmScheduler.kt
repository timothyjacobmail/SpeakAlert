package com.ghostgramlabs.speakalert.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ghostgramlabs.speakalert.data.model.ReminderEntity

interface AlarmScheduler {
    fun schedule(reminder: ReminderEntity, isBootReschedule: Boolean = false)
    fun cancel(reminder: ReminderEntity)
}

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(reminder: ReminderEntity, isBootReschedule: Boolean) {
        // SNOOZE PRIORITY: If snoozeUntil is set, use it instead of nextTriggerAt.
        // This is TEMPORARY - snoozeUntil is cleared when the alarm fires.
        // After snooze fires, recurrence resumes from nextTriggerAt (unchanged).
        val triggerTime = reminder.snoozeUntil ?: reminder.nextTriggerAt
        val expiry = com.ghostgramlabs.speakalert.data.retention.RecordedReminderExpiry(java.io.File(context.filesDir, "reminders"))
        if (expiry.isExpired(reminder, maxOf(triggerTime, System.currentTimeMillis()))) {
            cancel(reminder)
            return
        }
        
        com.ghostgramlabs.speakalert.util.FileLogger.log("SCHEDULER: Scheduling alarm id=${reminder.id} (boot=$isBootReschedule) for $triggerTime (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(triggerTime))})")
        
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra("reminderId", reminder.id)
            putExtra("isBootReschedule", isBootReschedule)
            putExtra("fireTime", triggerTime)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Log.d("VoiceReminder", "Scheduling alarm for id=${reminder.id} at $triggerTime")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    com.ghostgramlabs.speakalert.util.FileLogger.log("SCHEDULER: Set EXACT alarm")
                } else {
                    Log.w("VoiceReminder", "Cannot schedule exact alarm, falling back to inexact")
                    com.ghostgramlabs.speakalert.util.FileLogger.log("SCHEDULER: No exact alarm permission, using INEXACT")
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                com.ghostgramlabs.speakalert.util.FileLogger.log("SCHEDULER: Set EXACT alarm (pre-S)")
            }
            com.ghostgramlabs.speakalert.util.FileLogger.log("SCHEDULER: Alarm scheduled successfully")
        } catch (e: SecurityException) {
            Log.e("VoiceReminder", "SecurityException scheduling alarm", e)
            com.ghostgramlabs.speakalert.util.FileLogger.logError("SCHEDULER", "SecurityException", e)
        }
    }

    override fun cancel(reminder: ReminderEntity) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra("reminderId", reminder.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        FollowUpAlarmScheduler.cancel(context, reminder.id)
    }
}
