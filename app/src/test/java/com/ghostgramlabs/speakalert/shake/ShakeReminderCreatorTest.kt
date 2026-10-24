package com.ghostgramlabs.speakalert.shake

import com.ghostgramlabs.speakalert.alarm.AlarmScheduler
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.repository.ReminderRepository
import com.ghostgramlabs.speakalert.domain.models.RecurrenceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ShakeReminderCreatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository: ReminderRepository = mock()
    private val scheduler: AlarmScheduler = mock()

    private fun recording(): File {
        val drafts = ShakeDraftStore(temporary.root)
        return drafts.finish(drafts.newRecording().apply { writeText("recorded voice") })
    }

    @Test fun `completed take becomes a one time reminder exactly five minutes after stopping`() = runTest {
        val draft = recording()
        whenever(repository.insertReminder(any())).thenReturn(73L)
        val creator = ShakeReminderCreator(repository, scheduler, temporary.root, StandardTestDispatcher(testScheduler))
        val stoppedAt = 1_800_000_037_123L // Keep seconds and milliseconds; do not round to a minute.

        val result = creator.create(draft, stoppedAt, followUpCheckMinutes = 10)

        assertTrue(result.scheduled)
        assertEquals(73L, result.reminder.id)
        assertEquals(stoppedAt + 300_000L, result.reminder.nextTriggerAt)
        assertEquals(stoppedAt, result.reminder.createdAt)
        assertEquals(RecurrenceType.NONE, result.reminder.recurrenceType)
        assertEquals(10, result.reminder.followUpCheckMinutes)
        assertFalse(result.reminder.isCompleted)
        assertNull(result.reminder.title)
        val audio = File(result.reminder.audioPath!!)
        assertEquals(File(temporary.root, "reminders"), audio.parentFile)
        assertEquals("recorded voice", audio.readText())
        assertFalse(draft.exists())
        inOrder(repository, scheduler) {
            verify(repository).insertReminder(any())
            verify(scheduler).schedule(result.reminder, false)
        }
    }

    @Test fun `database failure keeps inbox take and removes unused audio copy`() = runTest {
        val draft = recording()
        whenever(repository.insertReminder(any())).thenThrow(IllegalStateException("Disk full"))
        val creator = ShakeReminderCreator(repository, scheduler, temporary.root, StandardTestDispatcher(testScheduler))

        try {
            creator.create(draft, 1_000L)
            fail("Expected the failed database write to be reported")
        } catch (_: IllegalStateException) { }

        assertEquals("recorded voice", draft.readText())
        assertTrue(File(temporary.root, "reminders").listFiles()!!.isEmpty())
        verify(scheduler, never()).schedule(any(), any())
    }

    @Test fun `alarm failure keeps saved reminder and audio for editing without duplicate inbox take`() = runTest {
        val draft = recording()
        whenever(repository.insertReminder(any())).thenReturn(74L)
        doThrow(IllegalStateException("Alarm unavailable")).whenever(scheduler).schedule(any(), any())
        val creator = ShakeReminderCreator(repository, scheduler, temporary.root, StandardTestDispatcher(testScheduler))

        val result = creator.create(draft, 2_000L)

        assertFalse(result.scheduled)
        assertEquals(74L, result.reminder.id)
        assertEquals("recorded voice", File(result.reminder.audioPath!!).readText())
        assertFalse(draft.exists())
        verify(repository, never()).deleteReminder(any())
    }

    @Test fun `empty recording creates no reminder or alarm`() = runTest {
        val empty = temporary.newFile("empty.m4a")
        val creator = ShakeReminderCreator(repository, scheduler, temporary.root, StandardTestDispatcher(testScheduler))
        try {
            creator.create(empty, 0L)
            fail("Expected invalid recording to be rejected")
        } catch (_: IllegalArgumentException) { }
        verify(repository, never()).insertReminder(any())
        verify(scheduler, never()).schedule(any(), any())
    }

    @Test fun `edited deadline replaces the default without rounding or creating duplicate reminders`() = runTest {
        whenever(repository.insertReminder(any())).thenReturn(81L)
        val creator = ShakeReminderCreator(repository, scheduler, temporary.root, StandardTestDispatcher(testScheduler))
        val stoppedAt = 1_800_000_037_123L
        val selected = stoppedAt + 40 * 60_000L
        val result = creator.create(recording(), stoppedAt, triggerAt = selected)
        assertEquals(selected, result.reminder.nextTriggerAt)
        assertEquals(stoppedAt, result.reminder.createdAt)
        verify(repository, times(1)).insertReminder(any())
        verify(scheduler, times(1)).schedule(result.reminder, false)
    }

    @Test fun `successive recordings get separate files and reminder ids`() = runTest {
        whenever(repository.insertReminder(any())).thenReturn(1L, 2L)
        val creator = ShakeReminderCreator(repository, scheduler, temporary.root, StandardTestDispatcher(testScheduler))
        val first = creator.create(recording(), 1_000L)
        val second = creator.create(recording(), 2_000L)
        assertNotEquals(first.reminder.id, second.reminder.id)
        assertNotEquals(first.reminder.audioPath, second.reminder.audioPath)
        assertTrue(File(first.reminder.audioPath!!).exists())
        assertTrue(File(second.reminder.audioPath!!).exists())
    }

    @Test fun `canceling caller during database handoff still completes alarm and preserves audio`() = runTest {
        val inserted = CompletableDeferred<Unit>()
        val allowInsert = CompletableDeferred<Unit>()
        val stored = mutableListOf<ReminderEntity>()
        val delayedRepository = object : ReminderRepository by repository {
            override suspend fun insertReminder(reminder: ReminderEntity): Long {
                inserted.complete(Unit)
                allowInsert.await()
                stored += reminder.copy(id = 99L)
                return 99L
            }
        }
        val draft = recording()
        val creator = ShakeReminderCreator(delayedRepository, scheduler, temporary.root, StandardTestDispatcher(testScheduler))
        val job = launch { creator.create(draft, 1_000L) }
        runCurrent()
        assertTrue(inserted.isCompleted)
        job.cancel()
        allowInsert.complete(Unit)
        advanceUntilIdle()
        assertEquals("recorded voice", File(stored.single().audioPath!!).readText())
        verify(scheduler).schedule(stored.single(), false)
        assertFalse(draft.exists())
    }
}
