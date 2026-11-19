package com.ghostgramlabs.speakalert.data.retention

import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.domain.models.RecurrenceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordedReminderExpiryTest {
    @get:Rule val folder = TemporaryFolder()
    private val directory get() = File(folder.root, "reminders").apply { mkdirs() }
    private val week = RecordedReminderExpiry.RETENTION_MS
    private val created = 1_800_000_037_123L

    private fun recording(name: String) = File(directory, name).apply { writeText("voice") }
    private fun reminder(id: Long, audio: File?, at: Long = created) = ReminderEntity(
        id = id, audioPath = audio?.path, createdAt = at, nextTriggerAt = at + 600_000L
    )

    @Test fun `recordings expire at exactly seven days from creation`() {
        val policy = RecordedReminderExpiry(directory)
        val reminder = reminder(1L, recording("voice.m4a"))
        assertFalse(policy.isExpired(reminder, created + week - 1L))
        assertTrue(policy.isExpired(reminder, created + week))
        assertEquals(created + week, policy.expiresAt(reminder))
    }

    @Test fun `completion snoozing repeating and rescheduling do not extend expiry`() {
        val policy = RecordedReminderExpiry(directory)
        val reminder = reminder(1L, recording("voice.m4a")).copy(
            isCompleted = true, completedAt = created + week - 1,
            recurrenceType = RecurrenceType.DAILY, nextTriggerAt = created + 20 * week,
            snoozeUntil = created + 2 * week, pendingFollowUpAt = created + 2 * week
        )
        assertTrue(policy.isExpired(reminder, created + week))
    }

    @Test fun `text picked content URIs and outside files never expire under the recording policy`() {
        val policy = RecordedReminderExpiry(directory)
        assertFalse(policy.isExpired(reminder(1, null), created + 10 * week))
        assertFalse(policy.isExpired(reminder(2, null).copy(audioPath = "content://audio/picked"), created + 10 * week))
        val outside = folder.newFile("picked.mp3")
        assertFalse(policy.isExpired(reminder(3, outside), created + 10 * week))
        assertNull(policy.ownedFile(File(directory, "../picked.mp3").path))
    }

    @Test fun `future creation time does not overflow into premature expiry`() {
        val policy = RecordedReminderExpiry(directory)
        val reminder = reminder(1, recording("future.m4a"), Long.MAX_VALUE - 1L)
        assertFalse(policy.isExpired(reminder, created))
        assertEquals(Long.MAX_VALUE, policy.expiresAt(reminder))
    }

    @Test fun `expiry removes the reminder its missed history and its audio`() = runTest {
        val audio = recording("old.m4a")
        val expired = reminder(1, audio)
        val store = MemoryStore(mutableListOf(expired), directory)
        store.missedIds += 1L
        val cancelled = mutableListOf<Long>()
        val count = RecordedReminderExpiryCleaner(store, directory, { cancelled += it.id }).clean(created + week)
        assertEquals(1, count)
        assertTrue(store.rows.isEmpty())
        assertTrue(store.missedIds.isEmpty())
        assertEquals(listOf(1L), cancelled)
        assertFalse(audio.exists())
    }

    @Test fun `a newer duplicate keeps shared audio after the original expires`() = runTest {
        val audio = recording("shared.m4a")
        val store = MemoryStore(mutableListOf(reminder(1, audio), reminder(2, audio, created + 1_000L)), directory)
        val cleaner = RecordedReminderExpiryCleaner(store, directory, {})
        assertEquals(1, cleaner.clean(created + week))
        assertTrue(audio.exists())
        assertEquals(listOf(2L), store.rows.map { it.id })
        assertEquals(1, cleaner.clean(created + week + 1_000L))
        assertFalse(audio.exists())
    }

    @Test fun `cleanup recovers old orphan audio and leaves fresh or referenced files`() = runTest {
        val orphan = recording("orphan.m4a").apply { setLastModified(created) }
        val fresh = recording("fresh.m4a").apply { setLastModified(created + week) }
        val referenced = recording("referenced.m4a").apply { setLastModified(created) }
        val outside = folder.newFile("outside.m4a").apply { setLastModified(created) }
        val store = MemoryStore(mutableListOf(reminder(1, referenced, created + 1_000L)), directory)
        RecordedReminderExpiryCleaner(store, directory, {}).clean(created + week)
        assertFalse(orphan.exists())
        assertTrue(fresh.exists())
        assertTrue(referenced.exists())
        assertTrue(outside.exists())
    }

    @Test fun `one failed alert cancellation does not prevent cleanup of the remaining recordings`() = runTest {
        val first = recording("first.m4a")
        val second = recording("second.m4a")
        val store = MemoryStore(mutableListOf(reminder(1, first), reminder(2, second)), directory)
        val attempted = mutableListOf<Long>()
        val failures = mutableListOf<Exception>()
        val cleaner = RecordedReminderExpiryCleaner(store, directory, {
            attempted += it.id
            if (it.id == 1L) error("Alarm service unavailable")
        }, { failures += it })
        assertEquals(2, cleaner.clean(created + week))
        assertEquals(listOf(1L, 2L), attempted)
        assertEquals(1, failures.size)
        assertFalse(first.exists())
        assertFalse(second.exists())
    }

    private class MemoryStore(val rows: MutableList<ReminderEntity>, directory: File) : RecordedReminderExpiryStore {
        val missedIds = mutableSetOf<Long>()
        private val policy = RecordedReminderExpiry(directory)
        override suspend fun removeExpired(now: Long): List<ReminderEntity> {
            val expired = rows.filter { policy.isExpired(it, now) }
            rows.removeAll(expired.toSet())
            missedIds.removeAll(expired.map { it.id }.toSet())
            return expired
        }
        override suspend fun referencedAudioPaths() = rows.mapNotNull { it.audioPath }
    }
}
