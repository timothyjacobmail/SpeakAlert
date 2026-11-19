package com.ghostgramlabs.speakalert.alarm

import com.ghostgramlabs.speakalert.data.model.ReminderEntity

// Cancellation cannot retract a broadcast that has already been delivered.
internal fun shouldIgnoreReminderAlarm(
    reminder: ReminderEntity,
    scheduledTime: Long,
    isFollowUpTrigger: Boolean
): Boolean {
    // A finite recurring schedule is completed when its final occurrence fires,
    // but the notification can still explicitly snooze that occurrence.
    // Done clears snoozeUntil, so a queued broadcast remains rejected afterward.
    val isRequestedSnooze = !isFollowUpTrigger && reminder.snoozeUntil == scheduledTime
    return (reminder.isCompleted && !isRequestedSnooze) ||
        (isFollowUpTrigger && reminder.pendingFollowUpAt != scheduledTime) ||
        (!isFollowUpTrigger && reminder.recurrenceType == com.ghostgramlabs.speakalert.domain.models.RecurrenceType.NONE &&
            scheduledTime != (reminder.snoozeUntil ?: reminder.nextTriggerAt))
}
