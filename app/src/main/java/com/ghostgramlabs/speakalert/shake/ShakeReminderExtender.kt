package com.ghostgramlabs.speakalert.shake

import com.ghostgramlabs.speakalert.alarm.AlarmScheduler
import com.ghostgramlabs.speakalert.data.repository.ReminderRepository
import com.ghostgramlabs.speakalert.domain.models.RecurrenceType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Extends the existing unfired take; guarded SQL preserves concurrent edits and completion. */
class ShakeReminderExtender(
    private val repository: ReminderRepository,
    private val scheduler: AlarmScheduler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun extend(id: Long, minutes: Int): ShakeReminderResult? = withContext(ioDispatcher + NonCancellable) {
        require(minutes == 5 || minutes == 30)
        val current = repository.getReminder(id) ?: return@withContext null
        val now = clock()
        if (current.isCompleted || current.lastFiredAt != null || current.snoozeUntil != null ||
            current.pendingFollowUpAt != null || current.recurrenceType != RecurrenceType.NONE ||
            current.nextTriggerAt <= now) return@withContext null
        val updatedTime = (current.nextTriggerAt + minutes * 60_000L).coerceAtMost(now + ShakeActionEngine.MAX_DELAY_MS)
        if (updatedTime <= current.nextTriggerAt ||
            !repository.extendUnfiredReminder(id, current.nextTriggerAt, updatedTime, now)) return@withContext null
        val updated = repository.getReminder(id) ?: return@withContext null
        if (updated.isCompleted || updated.nextTriggerAt != updatedTime || updated.lastFiredAt != null ||
            updated.snoozeUntil != null || updated.pendingFollowUpAt != null || updated.recurrenceType != RecurrenceType.NONE)
            return@withContext null
        val scheduled = try {
            // Same PendingIntent identity replaces the old alarm without a cancellation gap.
            scheduler.schedule(updated)
            true
        } catch (_: Exception) { false }
        ShakeReminderResult(updated, scheduled)
    }
}
