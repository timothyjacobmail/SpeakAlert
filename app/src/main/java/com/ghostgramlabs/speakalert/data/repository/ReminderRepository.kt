package com.ghostgramlabs.speakalert.data.repository

import android.content.Context
import com.ghostgramlabs.speakalert.data.database.ReminderDao
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.widget.SpeakAlertWidgetUpdater
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.ghostgramlabs.speakalert.data.retention.RecordedReminderExpiry
import com.ghostgramlabs.speakalert.data.retention.RecordedReminderExpiryWorker
import java.io.File

interface ReminderRepository {
    fun getAllRemindersStream(): Flow<List<ReminderEntity>>
    fun getActiveRemindersStream(): Flow<List<ReminderEntity>>
    fun getCompletedRemindersStream(): Flow<List<ReminderEntity>>
    suspend fun getReminder(id: Long): ReminderEntity?
    suspend fun getAllActiveReminders(): List<ReminderEntity>
    suspend fun insertReminder(reminder: ReminderEntity): Long
    suspend fun updateReminder(reminder: ReminderEntity)
    suspend fun deleteReminder(reminder: ReminderEntity)
}

class OfflineReminderRepository(
    private val context: Context,
    private val reminderDao: ReminderDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val scheduleExpiry: (ReminderEntity) -> Unit = { RecordedReminderExpiryWorker.schedule(context, it) }
) : ReminderRepository {
    private val expiry = RecordedReminderExpiry(File(context.filesDir, "reminders"))
    private fun unexpired(reminders: List<ReminderEntity>): List<ReminderEntity> {
        val at = now()
        return reminders.filterNot { expiry.isExpired(it, at) }
    }
    override fun getAllRemindersStream(): Flow<List<ReminderEntity>> = reminderDao.getAllReminders().map(::unexpired)
    override fun getActiveRemindersStream(): Flow<List<ReminderEntity>> = reminderDao.getActiveReminders().map(::unexpired)
    override fun getCompletedRemindersStream(): Flow<List<ReminderEntity>> = reminderDao.getCompletedReminders().map(::unexpired)
    override suspend fun getReminder(id: Long): ReminderEntity? = reminderDao.getReminderById(id)?.takeUnless { expiry.isExpired(it, now()) }
    override suspend fun getAllActiveReminders(): List<ReminderEntity> = unexpired(reminderDao.getAllActiveRemindersOnce())
    override suspend fun insertReminder(reminder: ReminderEntity): Long {
        val id = reminderDao.insertReminder(reminder)
        requestExpiry(reminder.copy(id = id))
        SpeakAlertWidgetUpdater.requestUpdate(context)
        return id
    }
    override suspend fun updateReminder(reminder: ReminderEntity) {
        // The editor may construct a fresh entity while saving. Editing must never restart
        // the seven-day lifetime of an existing reminder.
        val original = reminderDao.getReminderById(reminder.id)
        val updated = original?.let { reminder.copy(createdAt = it.createdAt) } ?: reminder
        reminderDao.updateReminder(updated)
        requestExpiry(updated)
        SpeakAlertWidgetUpdater.requestUpdate(context)
    }
    override suspend fun deleteReminder(reminder: ReminderEntity) {
        reminderDao.deleteReminder(reminder)
        SpeakAlertWidgetUpdater.requestUpdate(context)
    }

    private fun requestExpiry(reminder: ReminderEntity) {
        // A maintenance scheduling failure must not turn an already-saved reminder into a
        // failed save. Startup and periodic sweeps also enforce retention.
        runCatching { scheduleExpiry(reminder) }
            .onFailure { android.util.Log.w("RecordingExpiry", "Could not schedule recording cleanup", it) }
    }
}
