package com.ghostgramlabs.speakalert.recorderimport

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.ghostgramlabs.speakalert.data.database.AppDatabase
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.retention.RecordedReminderExpiryWorker
import com.ghostgramlabs.speakalert.widget.SpeakAlertWidgetUpdater

@Entity(tableName = "recorder_imports")
data class RecorderImportJournal(
    @PrimaryKey val sourceKey: String,
    val reminderId: Long,
    val finished: Boolean = false
)

@Dao
interface RecorderImportDao {
    @Query("SELECT * FROM recorder_imports WHERE sourceKey = :key")
    suspend fun find(key: String): RecorderImportJournal?

    @Query("SELECT * FROM recorder_imports WHERE finished = 0")
    suspend fun pending(): List<RecorderImportJournal>

    @Insert
    suspend fun insert(entry: RecorderImportJournal)

    @Query("UPDATE recorder_imports SET finished = 1 WHERE sourceKey = :key")
    suspend fun finish(key: String)
}

internal class RoomRecorderImportStore(
    context: Context,
    private val db: AppDatabase,
    private val afterCommit: (ReminderEntity) -> Unit = { reminder ->
        runCatching { RecordedReminderExpiryWorker.schedule(context, reminder) }
            .onFailure { android.util.Log.w("RecorderImport", "Could not schedule recording expiry", it) }
        SpeakAlertWidgetUpdater.requestUpdate(context)
    }
) : RecorderImportStore {
    override suspend fun pending(): List<StoredRecorderImport> = db.withTransaction {
        db.recorderImportDao().pending().map { entry ->
            StoredRecorderImport(entry.sourceKey, db.reminderDao().getReminderById(entry.reminderId), false)
        }
    }

    override suspend fun find(key: String): StoredRecorderImport? = db.withTransaction {
        db.recorderImportDao().find(key)?.let { entry ->
            StoredRecorderImport(key, db.reminderDao().getReminderById(entry.reminderId), entry.finished)
        }
    }

    override suspend fun saveIfAbsent(key: String, reminder: ReminderEntity): StoredRecorderImport {
        val stored = db.withTransaction {
            find(key) ?: run {
                val saved = reminder.copy(id = db.reminderDao().insertReminder(reminder))
                db.recorderImportDao().insert(RecorderImportJournal(key, saved.id))
                StoredRecorderImport(key, saved, false)
            }
        }
        stored.reminder?.let(afterCommit)
        return stored
    }

    override suspend fun finish(key: String) = db.recorderImportDao().finish(key)
}
