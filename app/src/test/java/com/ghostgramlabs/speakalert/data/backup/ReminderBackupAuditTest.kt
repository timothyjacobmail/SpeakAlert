package com.ghostgramlabs.speakalert.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.repository.ReminderRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.io.ByteArrayOutputStream
import java.io.File

class ReminderBackupAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mock<Context>()
    private val resolver = mock<ContentResolver>()
    private val backupUri = mock<Uri>()

    private fun setupBackup(): ByteArrayOutputStream {
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(context.filesDir).thenReturn(temporary.newFolder("files"))
        val bytes = ByteArrayOutputStream()
        whenever(resolver.openOutputStream(backupUri, "wt")).thenReturn(bytes)
        return bytes
    }

    @Test fun `local recording and Unicode text survive export and import`() = runTest {
        val bytes = setupBackup()
        val audio = temporary.newFile("voice.m4a").apply { writeBytes(byteArrayOf(2, 4, 6, 8)) }
        val original = ReminderEntity(title = "العربية हिंदी", reminderText = "Español",
            audioPath = audio.absolutePath, nextTriggerAt = System.currentTimeMillis() + 3600000)
        ReminderBackupManager.export(context, backupUri, listOf(original))
        whenever(resolver.openInputStream(backupUri)).thenAnswer { bytes.toByteArray().inputStream() }
        val repository = mock<ReminderRepository>()
        whenever(repository.getAllActiveReminders()).thenReturn(emptyList())
        whenever(repository.insertReminder(any())).thenReturn(7L)
        val scheduled = mutableListOf<ReminderEntity>()
        val result = ReminderBackupManager.import(context, backupUri, repository, scheduled::add)
        assertEquals(1, result.imported)
        assertEquals(original.title, scheduled.single().title)
        assertEquals(original.reminderText, scheduled.single().reminderText)
        assertArrayEquals(audio.readBytes(), File(scheduled.single().audioPath!!).readBytes())
    }

    @Test fun `restoring an expired recorded reminder skips it and deletes extracted audio`() = runTest {
        val bytes = setupBackup()
        val audio = temporary.newFile("expired.m4a").apply { writeText("old voice") }
        val original = ReminderEntity(
            audioPath = audio.absolutePath,
            createdAt = System.currentTimeMillis() - com.ghostgramlabs.speakalert.data.retention.RecordedReminderExpiry.RETENTION_MS - 1,
            nextTriggerAt = System.currentTimeMillis() + 3_600_000L
        )
        ReminderBackupManager.export(context, backupUri, listOf(original))
        whenever(resolver.openInputStream(backupUri)).thenAnswer { bytes.toByteArray().inputStream() }
        val repository = mock<ReminderRepository>()
        whenever(repository.getAllActiveReminders()).thenReturn(emptyList())
        val scheduled = mutableListOf<ReminderEntity>()
        val result = ReminderBackupManager.import(context, backupUri, repository, scheduled::add)
        assertEquals(0, result.imported)
        assertEquals(1, result.skippedExpired)
        assertTrue(scheduled.isEmpty())
        verify(repository, never()).insertReminder(any())
        assertTrue(File(context.filesDir, "reminders").listFiles().orEmpty().isEmpty())
    }

    @Test fun `audit chosen content URI audio survives backup restore`() = runTest {
        val bytes = setupBackup()
        val audioUri = mock<Uri>()
        val originalAudio = byteArrayOf(2, 4, 6, 8)
        whenever(resolver.openInputStream(audioUri)).thenAnswer { originalAudio.inputStream() }
        val original = ReminderEntity(audioPath = "content://media/external/audio/media/17",
            nextTriggerAt = System.currentTimeMillis() + 3600000)
        ReminderBackupManager.export(context, backupUri, listOf(original))
        whenever(resolver.openInputStream(backupUri)).thenAnswer { bytes.toByteArray().inputStream() }
        val repository = mock<ReminderRepository>()
        whenever(repository.getAllActiveReminders()).thenReturn(emptyList())
        whenever(repository.insertReminder(any())).thenReturn(7L)
        val scheduled = mutableListOf<ReminderEntity>()
        ReminderBackupManager.import(context, backupUri, repository, scheduled::add)
        assertNotNull("Chosen audio must not disappear from a restored reminder", scheduled.single().audioPath)
    }

    @Test fun `readable chosen content URI audio is copied into the backup`() = runTest {
        val bytes = setupBackup()
        val source = "content://media/external/audio/media/17"
        val audioUri = mock<Uri>()
        val originalAudio = byteArrayOf(1, 3, 5, 7)
        whenever(resolver.openInputStream(audioUri)).thenAnswer { originalAudio.inputStream() }
        val original = ReminderEntity(audioPath = source, nextTriggerAt = System.currentTimeMillis() + 3600000)
        Mockito.mockStatic(Uri::class.java).use { uri ->
            uri.`when`<Uri> { Uri.parse(source) }.thenReturn(audioUri)
            ReminderBackupManager.export(context, backupUri, listOf(original))
        }
        whenever(resolver.openInputStream(backupUri)).thenAnswer { bytes.toByteArray().inputStream() }
        val repository = mock<ReminderRepository>()
        whenever(repository.getAllActiveReminders()).thenReturn(emptyList())
        whenever(repository.insertReminder(any())).thenReturn(7L)
        val scheduled = mutableListOf<ReminderEntity>()
        ReminderBackupManager.import(context, backupUri, repository, scheduled::add)
        val restoredPath = scheduled.single().audioPath!!
        assertFalse("Readable audio must be restored as a local copy", restoredPath.startsWith("content://"))
        assertArrayEquals(originalAudio, File(restoredPath).readBytes())
    }
}
