package com.ghostgramlabs.speakalert.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.model.MissedReminderEntity
import com.ghostgramlabs.speakalert.recorderimport.RecorderImportDao
import com.ghostgramlabs.speakalert.recorderimport.RecorderImportJournal

@Database(entities = [ReminderEntity::class, MissedReminderEntity::class, RecorderImportJournal::class], version = 8, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun missedReminderDao(): MissedReminderDao
    abstract fun recorderImportDao(): RecorderImportDao

    companion object {
        @Volatile
        private var Instance: AppDatabase? = null

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reminders ADD COLUMN reminderText TEXT DEFAULT NULL")
            }
        }
        
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reminders ADD COLUMN loopPlayback INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE missed_reminders ADD COLUMN reminderText TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reminders ADD COLUMN followUpCheckMinutes INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE reminders ADD COLUMN pendingFollowUpAt INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reminders ADD COLUMN followUpFireCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS recorder_imports (sourceKey TEXT NOT NULL, reminderId INTEGER NOT NULL, finished INTEGER NOT NULL, PRIMARY KEY(sourceKey))")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(context, AppDatabase::class.java, "reminder_database")
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration() // Rebuild DB if migration path is missing
                    .build()
                    .also { Instance = it }
            }
        }
    }
}
