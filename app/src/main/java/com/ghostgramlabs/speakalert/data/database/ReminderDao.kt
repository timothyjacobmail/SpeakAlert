package com.ghostgramlabs.speakalert.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders ORDER BY nextTriggerAt ASC")
    fun getAllReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isCompleted = 0 ORDER BY nextTriggerAt ASC")
    fun getActiveReminders(): Flow<List<ReminderEntity>>
    
    @Query("SELECT * FROM reminders WHERE isCompleted = 1 ORDER BY completedAt DESC")
    fun getCompletedReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getReminderById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE nextTriggerAt > 0 AND isCompleted = 0")
    suspend fun getAllActiveRemindersOnce(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE audioPath IS NOT NULL AND createdAt <= :cutoff")
    suspend fun getAudioRemindersCreatedBefore(cutoff: Long): List<ReminderEntity>

    @Query("SELECT audioPath FROM reminders WHERE audioPath IS NOT NULL")
    suspend fun getReferencedAudioPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity): Long

    @Update
    suspend fun updateReminder(reminder: ReminderEntity)

    // Change only the deadline, and never overwrite a concurrent edit, Done or delivered alarm.
    @Query("""UPDATE reminders SET nextTriggerAt = :updatedTime
        WHERE id = :id AND nextTriggerAt = :expectedTime AND nextTriggerAt > :now
        AND isCompleted = 0 AND lastFiredAt IS NULL AND snoozeUntil IS NULL
        AND pendingFollowUpAt IS NULL AND recurrenceType = 'NONE'""")
    suspend fun extendUnfiredReminder(id: Long, expectedTime: Long, updatedTime: Long, now: Long): Int

    @Delete
    suspend fun deleteReminder(reminder: ReminderEntity)
    
    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminderById(id: Long)
}
