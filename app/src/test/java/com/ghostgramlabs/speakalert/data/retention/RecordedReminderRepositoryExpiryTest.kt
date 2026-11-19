package com.ghostgramlabs.speakalert.data.retention

import android.content.Context
import com.ghostgramlabs.speakalert.data.database.ReminderDao
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.repository.OfflineReminderRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*
import java.io.File

class RecordedReminderRepositoryExpiryTest {
    @get:Rule val folder = TemporaryFolder()
    private val dao: ReminderDao = mock()
    private val context: Context = mock()
    private val scheduled = mutableListOf<ReminderEntity>()
    private val now = 1_800_000_000_000L

    private fun repository(): OfflineReminderRepository {
        whenever(context.filesDir).thenReturn(folder.root)
        whenever(context.applicationContext).thenReturn(context)
        return OfflineReminderRepository(context, dao, now = { now }, scheduleExpiry = { scheduled += it })
    }

    private fun recorded(id: Long, created: Long) = ReminderEntity(
        id = id, createdAt = created, nextTriggerAt = now + 600_000L,
        audioPath = File(folder.root, "reminders/$id.m4a").path
    )

    @Test fun `expired recordings disappear from all reads even before the worker runs`() = runTest {
        val expired = recorded(1, now - RecordedReminderExpiry.RETENTION_MS)
        val fresh = recorded(2, now - RecordedReminderExpiry.RETENTION_MS + 1)
        val text = ReminderEntity(id = 3, createdAt = 0L, nextTriggerAt = now + 1)
        val rows = listOf(expired, fresh, text)
        whenever(dao.getAllReminders()).thenReturn(flowOf(rows))
        whenever(dao.getActiveReminders()).thenReturn(flowOf(rows))
        whenever(dao.getCompletedReminders()).thenReturn(flowOf(rows))
        whenever(dao.getAllActiveRemindersOnce()).thenReturn(rows)
        whenever(dao.getReminderById(1)).thenReturn(expired)
        val repository = repository()
        assertNull(repository.getReminder(1))
        assertEquals(listOf(fresh, text), repository.getAllRemindersStream().first())
        assertEquals(listOf(fresh, text), repository.getActiveRemindersStream().first())
        assertEquals(listOf(fresh, text), repository.getCompletedRemindersStream().first())
        assertEquals(listOf(fresh, text), repository.getAllActiveReminders())
    }

    @Test fun `editing preserves the original creation date and cleanup deadline`() = runTest {
        val original = recorded(7, now - 600_000L)
        whenever(dao.getReminderById(7)).thenReturn(original)
        val repository = repository()
        repository.updateReminder(original.copy(title = "Edited", createdAt = now))
        val saved = argumentCaptor<ReminderEntity>()
        verify(dao).updateReminder(saved.capture())
        assertEquals(original.createdAt, saved.firstValue.createdAt)
        assertEquals("Edited", saved.firstValue.title)
        assertTrue("An unchanged expiry must reuse the already scheduled cleanup", scheduled.isEmpty())
    }

    @Test fun `attaching a recording to a text reminder schedules its original creation deadline`() = runTest {
        val original = recorded(7, now - 600_000L).copy(audioPath = null)
        whenever(dao.getReminderById(7)).thenReturn(original)
        repository().updateReminder(original.copy(audioPath = recorded(7, now).audioPath, createdAt = now))
        assertEquals(original.createdAt, scheduled.single().createdAt)
        assertEquals(original.id, scheduled.single().id)
    }

    @Test fun `new recording cleanup is scheduled with its real inserted id`() = runTest {
        whenever(dao.insertReminder(any())).thenReturn(43L)
        repository().insertReminder(recorded(0, now))
        assertEquals(43L, scheduled.single().id)
        assertEquals(now, scheduled.single().createdAt)
    }

    @Test fun `a cleanup scheduler failure does not lose an already saved recording`() = runTest {
        whenever(context.filesDir).thenReturn(folder.root)
        whenever(context.applicationContext).thenReturn(context)
        whenever(dao.insertReminder(any())).thenReturn(44L)
        val repository = OfflineReminderRepository(context, dao, scheduleExpiry = { error("Worker unavailable") })
        assertEquals(44L, repository.insertReminder(recorded(0, now)))
        verify(dao).insertReminder(any())
    }
}
