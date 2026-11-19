package com.ghostgramlabs.speakalert.shake

import com.ghostgramlabs.speakalert.alarm.AlarmScheduler
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.repository.ReminderRepository
import com.ghostgramlabs.speakalert.domain.models.RecurrenceType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class ShakeReminderExtenderTest {
    private val repository: ReminderRepository = mock()
    private val scheduler: AlarmScheduler = mock()
    private val now = 1_800_000_037_123L
    private val original = ReminderEntity(id = 23, title = "Keep title", audioPath = "voice.m4a",
        nextTriggerAt = now + 300_000L, followUpCheckMinutes = 15)

    @Test fun `successive taps extend the same reminder from its latest deadline`() = runTest {
        var stored = original
        val memory = object : ReminderRepository by repository {
            override suspend fun getReminder(id: Long) = stored
            override suspend fun extendUnfiredReminder(id: Long, expectedTime: Long, updatedTime: Long, now: Long): Boolean {
                assertEquals(stored.nextTriggerAt, expectedTime)
                stored = stored.copy(nextTriggerAt = updatedTime)
                return true
            }
        }
        val extender = ShakeReminderExtender(memory, scheduler, StandardTestDispatcher(testScheduler)) { now }
        val first = extender.extend(23, 5)!!
        val second = extender.extend(23, 30)!!
        assertEquals(original.nextTriggerAt + 300_000L, first.reminder.nextTriggerAt)
        assertEquals(original.nextTriggerAt + 35 * 60_000L, second.reminder.nextTriggerAt)
        assertEquals(original.copy(nextTriggerAt = second.reminder.nextTriggerAt), second.reminder)
        assertTrue(second.scheduled)
        verify(scheduler).schedule(first.reminder, false)
        verify(scheduler).schedule(second.reminder, false)
        verify(repository, never()).insertReminder(any())
        verify(repository, never()).updateReminder(any())
        verify(scheduler, never()).cancel(any())
    }

    @Test fun `missing completed delivered due snoozed and recurring reminders are not changed`() = runTest {
        val extender = ShakeReminderExtender(repository, scheduler, StandardTestDispatcher(testScheduler)) { now }
        val ineligible = listOf(null, original.copy(isCompleted = true), original.copy(lastFiredAt = now),
            original.copy(nextTriggerAt = now), original.copy(snoozeUntil = now + 50_000),
            original.copy(pendingFollowUpAt = now + 50_000), original.copy(recurrenceType = RecurrenceType.DAILY))
        for (reminder in ineligible) {
            whenever(repository.getReminder(23)).thenReturn(reminder)
            assertNull(extender.extend(23, 5))
        }
        verify(repository, never()).extendUnfiredReminder(any(), any(), any(), any())
        verify(scheduler, never()).schedule(any(), any())
    }

    @Test fun `concurrent edit failing guarded update never schedules a stale deadline`() = runTest {
        whenever(repository.getReminder(23)).thenReturn(original)
        whenever(repository.extendUnfiredReminder(any(), any(), any(), any())).thenReturn(false)
        val extender = ShakeReminderExtender(repository, scheduler, StandardTestDispatcher(testScheduler)) { now }
        assertNull(extender.extend(23, 5))
        verify(scheduler, never()).schedule(any(), any())
    }

    @Test fun `completion after guarded update is not rescheduled`() = runTest {
        val updated = original.copy(nextTriggerAt = original.nextTriggerAt + 300_000L, isCompleted = true)
        whenever(repository.getReminder(23)).thenReturn(original, updated)
        whenever(repository.extendUnfiredReminder(any(), any(), any(), any())).thenReturn(true)
        val extender = ShakeReminderExtender(repository, scheduler, StandardTestDispatcher(testScheduler)) { now }
        assertNull(extender.extend(23, 5))
        verify(scheduler, never()).schedule(any(), any())
    }

    @Test fun `alarm failure returns the stored deadline for recovery`() = runTest {
        val updated = original.copy(nextTriggerAt = original.nextTriggerAt + 1_800_000L)
        whenever(repository.getReminder(23)).thenReturn(original, updated)
        whenever(repository.extendUnfiredReminder(any(), any(), any(), any())).thenReturn(true)
        doThrow(IllegalStateException("Alarm failure")).whenever(scheduler).schedule(any(), any())
        val extender = ShakeReminderExtender(repository, scheduler, StandardTestDispatcher(testScheduler)) { now }
        val result = extender.extend(23, 30)!!
        assertFalse(result.scheduled)
        assertEquals(updated, result.reminder)
        verify(repository, never()).deleteReminder(any())
    }

    @Test fun `database failure leaves alarm unchanged`() = runTest {
        whenever(repository.getReminder(23)).thenReturn(original)
        whenever(repository.extendUnfiredReminder(any(), any(), any(), any())).thenThrow(IllegalStateException("Disk full"))
        val extender = ShakeReminderExtender(repository, scheduler, StandardTestDispatcher(testScheduler)) { now }
        try { extender.extend(23, 5); fail("Expected write failure") } catch (_: IllegalStateException) { }
        verify(scheduler, never()).schedule(any(), any())
    }

    @Test fun `at the seven day limit no deadline is shortened`() = runTest {
        whenever(repository.getReminder(23)).thenReturn(original.copy(nextTriggerAt = now + ShakeActionEngine.MAX_DELAY_MS))
        val extender = ShakeReminderExtender(repository, scheduler, StandardTestDispatcher(testScheduler)) { now }
        assertNull(extender.extend(23, 30))
        verify(repository, never()).extendUnfiredReminder(any(), any(), any(), any())
    }
}
