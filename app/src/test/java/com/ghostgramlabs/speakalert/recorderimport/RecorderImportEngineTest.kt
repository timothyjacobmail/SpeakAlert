package com.ghostgramlabs.speakalert.recorderimport

import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecorderImportEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_800_000_100_000L
    private var watch: RecorderWatch? = RecorderWatch(now - 60_000L, emptySet())
    private val audio = RecorderAudio("content://media/external_primary/audio/media/73", now - 20_000L,
        now - 5_000L, 5, 1_000, false, RECORDER_PACKAGE, RECORDER_FOLDER)
    private val scheduled = mutableListOf<ReminderEntity>()
    private val confirmed = mutableListOf<ReminderEntity>()
    private val errors = mutableListOf<Exception>()
    private val store = MemoryStore()
    private val source = Source()
    private fun directory() = File(temporary.root, "reminders")
    private fun engine(schedule: (ReminderEntity) -> Unit = { scheduled += it }, confirm: (ReminderEntity) -> Unit = { confirmed += it }) =
        RecorderImportEngine(source, store, directory(), { watch }, schedule, confirm, { now }, { errors += it })

    private inner class Source : RecorderAudioSource {
        var entries = listOf(audio)
        var stable = true
        var copies = 0
        var onCopy: () -> Unit = {}
        val original by lazy { temporary.newFile("original.m4a").apply { writeText("voice") } }
        override fun list(addedSince: Long) = entries.filter { it.addedAt >= addedSince }
        override fun copyFinished(audio: RecorderAudio, destination: File): Boolean {
            copies++
            original.copyTo(destination)
            onCopy()
            return stable
        }
    }

    private class MemoryStore : RecorderImportStore {
        val entries = linkedMapOf<String, StoredRecorderImport>()
        var saves = 0
        var failSave = false
        override suspend fun find(key: String) = entries[key]
        override suspend fun pending() = entries.values.filterNot { it.finished }
        override suspend fun saveIfAbsent(key: String, reminder: ReminderEntity): StoredRecorderImport {
            if (failSave) error("Database full")
            return entries.getOrPut(key) { StoredRecorderImport(key, reminder.copy(id = (++saves).toLong()), false) }
        }
        override suspend fun finish(key: String) { entries[key] = entries.getValue(key).copy(finished = true) }
    }

    @Test fun `saved recording gets a durable copy and confirmation after scheduling exactly ten minutes from save`() = runTest {
        assertFalse(engine(confirm = { assertEquals(listOf(it), scheduled); confirmed += it }).scan())
        val reminder = scheduled.single()
        assertEquals(audio.savedAt, reminder.createdAt)
        assertEquals(audio.savedAt + 600_000L, reminder.nextTriggerAt)
        assertEquals("voice", File(reminder.audioPath!!).readText())
        assertEquals(directory(), File(reminder.audioPath!!).parentFile)
        assertEquals("voice", source.original.readText())
        assertEquals(listOf(reminder), confirmed)
        assertFalse(reminder.isCompleted)
        assertEquals(0, reminder.followUpCheckMinutes)
    }

    @Test fun `repeated updates and a new engine do not duplicate a reminder or confirmation`() = runTest {
        repeat(5) { engine().scan() }
        assertEquals(1, source.copies)
        assertEquals(1, store.saves)
        assertEquals(1, scheduled.size)
        assertEquals(1, confirmed.size)
    }

    @Test fun `baseline includes same-second old recordings and takes saved while disabled`() = runTest {
        watch = RecorderWatch(audio.addedAt + 600L, setOf(audio.key))
        assertFalse(engine().scan())
        assertEquals(0, source.copies)
        watch = null
        assertFalse(engine().scan())
        // Re-enabling establishes a fresh baseline including all recordings already present.
        watch = RecorderWatch(now, setOf(audio.key))
        assertFalse(engine().scan())
        assertTrue(scheduled.isEmpty())
    }

    @Test fun `other audio and call-recording folders are ignored`() = runTest {
        source.entries = listOf(audio.copy(owner = "music.app"), audio.copy(folder = "Recordings/Call/"))
        assertFalse(engine().scan())
        assertEquals(0, source.copies)
    }

    @Test fun `hidden ownership is accepted only in the known Recorder folder`() = runTest {
        source.entries = listOf(audio.copy(owner = null, folder = "Music/"))
        assertFalse(engine().scan())
        assertEquals(0, source.copies)
        source.entries = listOf(audio.copy(owner = null))
        assertFalse(engine().scan())
        assertEquals(1, confirmed.size)
    }

    @Test fun `pending empty and not finalized recordings are retried without importing partial audio`() = runTest {
        for (entry in listOf(audio.copy(pending = true), audio.copy(size = 0), audio.copy(duration = 0), audio.copy(savedAt = now))) {
            source.entries = listOf(entry)
            assertTrue(engine().scan())
        }
        assertEquals(0, source.copies)
        source.entries = listOf(audio)
        assertFalse(engine().scan())
        assertEquals(1, store.saves)
    }

    @Test fun `a changing source leaves no reminder or partial file and can be retried`() = runTest {
        source.stable = false
        assertTrue(engine().scan())
        assertTrue(directory().listFiles()!!.isEmpty())
        assertTrue(scheduled.isEmpty())
        source.stable = true
        assertFalse(engine().scan())
        assertEquals(1, store.saves)
    }

    @Test fun `database failure removes unused copy and preserves the original`() = runTest {
        store.failSave = true
        assertTrue(engine().scan())
        assertTrue(directory().listFiles()!!.isEmpty())
        assertEquals("voice", source.original.readText())
        store.failSave = false
        assertFalse(engine().scan())
        assertEquals(1, confirmed.size)
    }

    @Test fun `interrupted alarm handoff recovers its existing row even if the original is gone`() = runTest {
        assertTrue(engine(schedule = { error("Alarm unavailable") }).scan())
        assertEquals(1, store.saves)
        assertTrue(confirmed.isEmpty())
        source.entries = emptyList()
        source.original.delete()
        assertFalse(engine().scan())
        assertEquals(1, store.saves)
        assertEquals(1, source.copies)
        assertEquals(1, confirmed.size)
        assertTrue(store.entries.getValue(audio.key).finished)
    }

    @Test fun `notification failure retries the handoff without copying or inserting again`() = runTest {
        assertTrue(engine(confirm = { error("Notifications unavailable") }).scan())
        assertFalse(engine().scan())
        assertEquals(1, source.copies)
        assertEquals(1, store.saves)
        assertEquals(1, confirmed.size)
    }

    @Test fun `deleting or completing reminders never reimports or revives them`() = runTest {
        engine().scan()
        val entry = store.entries.getValue(audio.key)
        store.entries[audio.key] = entry.copy(reminder = null, finished = false)
        engine().scan()
        store.entries[audio.key] = entry.copy(reminder = entry.reminder!!.copy(isCompleted = true), finished = false)
        engine().scan()
        assertEquals(1, store.saves)
        assertEquals(1, scheduled.size)
    }

    @Test fun `turning off during a copy prevents committing a new reminder`() = runTest {
        source.onCopy = { watch = null }
        engine().scan()
        assertEquals(0, store.saves)
        assertTrue(directory().listFiles()!!.isEmpty())
        assertEquals("voice", source.original.readText())
    }

    @Test fun `expired recordings are ignored and reused media ids with a new creation date are distinct`() = runTest {
        source.entries = listOf(audio.copy(savedAt = now - RECORDER_RETENTION_MS - 1))
        assertFalse(engine().scan())
        assertEquals(0, store.saves)
        source.entries = listOf(audio)
        engine().scan()
        source.entries = listOf(audio.copy(addedAt = audio.addedAt + 1_000L))
        engine().scan()
        assertEquals(2, store.saves)
    }

    @Test fun `concurrent scans serialize before copying or committing`() = runTest {
        List(10) { async { engine().scan() } }.awaitAll()
        assertEquals(1, source.copies)
        assertEquals(1, confirmed.size)
        assertEquals(1, store.saves)
    }
}
