package com.ghostgramlabs.speakalert.data.retention

import androidx.room.withTransaction
import com.ghostgramlabs.speakalert.data.database.AppDatabase
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import java.io.File

class RoomRecordedReminderExpiryStore(
    private val database: AppDatabase,
    recordingsDir: File
) : RecordedReminderExpiryStore {
    private val policy = RecordedReminderExpiry(recordingsDir)

    override suspend fun removeExpired(now: Long): List<ReminderEntity> = database.withTransaction {
        val dao = database.reminderDao()
        val expired = dao.getAudioRemindersCreatedBefore(now - RecordedReminderExpiry.RETENTION_MS)
            .filter { policy.isExpired(it, now) }
        expired.forEach { reminder ->
            database.missedReminderDao().deleteByReminderId(reminder.id)
            dao.deleteReminderById(reminder.id)
        }
        expired
    }

    override suspend fun referencedAudioPaths(): List<String> = database.reminderDao().getReferencedAudioPaths()
}
