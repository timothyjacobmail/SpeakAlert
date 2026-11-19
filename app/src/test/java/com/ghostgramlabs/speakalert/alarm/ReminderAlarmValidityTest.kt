package com.ghostgramlabs.speakalert.alarm

import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderAlarmValidityTest {
    private val reminder = ReminderEntity(id = 1, nextTriggerAt = 10_000L)

    @Test fun `extending a one time reminder rejects the queued original alarm`() {
        val extended = reminder.copy(nextTriggerAt = 310_000L)
        assertTrue(shouldIgnoreReminderAlarm(extended, 10_000L, false))
        assertFalse(shouldIgnoreReminderAlarm(extended, 310_000L, false))
        assertFalse(shouldIgnoreReminderAlarm(extended.copy(pendingFollowUpAt = 400_000L), 400_000L, true))
    }

    @Test
    fun `done rejects already delivered regular and follow-up alarms`() {
        val completed = reminder.copy(isCompleted = true, pendingFollowUpAt = 20_000L)
        assertTrue(shouldIgnoreReminderAlarm(completed, 10_000L, false))
        assertTrue(shouldIgnoreReminderAlarm(completed, 20_000L, true))
    }

    @Test
    fun `skipped occurrence rejects cleared or replaced follow-up`() {
        assertTrue(shouldIgnoreReminderAlarm(reminder, 20_000L, true))
        assertTrue(shouldIgnoreReminderAlarm(reminder.copy(pendingFollowUpAt = 30_000L), 20_000L, true))
    }

    @Test
    fun `active regular snooze and current follow-up alarms remain eligible`() {
        assertFalse(shouldIgnoreReminderAlarm(reminder, 10_000L, false))
        assertFalse(shouldIgnoreReminderAlarm(reminder.copy(snoozeUntil = 15_000L), 15_000L, false))
        assertFalse(shouldIgnoreReminderAlarm(reminder.copy(pendingFollowUpAt = 20_000L), 20_000L, true))
    }

    @Test
    fun `final recurring occurrence can still fire its explicitly requested snooze`() {
        // Receiver completes a finite schedule on its last fire. Notification Snooze
        // sets snoozeUntil while preserving isCompleted and the recurrence definition.
        val lastOccurrence = reminder.copy(
            recurrenceType = com.ghostgramlabs.speakalert.domain.models.RecurrenceType.DAILY,
            isCompleted = true,
            completedAt = 10_000L,
            lastFiredAt = 10_000L,
            snoozeUntil = 15_000L
        )
        assertFalse(shouldIgnoreReminderAlarm(lastOccurrence, 15_000L, false))
        assertTrue(shouldIgnoreReminderAlarm(lastOccurrence, 10_000L, false))
        assertTrue(shouldIgnoreReminderAlarm(lastOccurrence, 15_000L, true))
    }

    @Test
    fun `done cancels the final snooze and rejects its queued broadcast`() {
        val done = reminder.copy(isCompleted = true, snoozeUntil = null)
        assertTrue(shouldIgnoreReminderAlarm(done, 15_000L, false))
    }
}
