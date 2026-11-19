package com.ghostgramlabs.speakalert.recorderimport

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghostgramlabs.speakalert.data.database.AppDatabase
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecorderImportDatabaseTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun recorderFolderLookupWorksWithoutAnOwnerPredicate() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        assumeTrue(RecorderImportSettings.hasAudioAccess(context))
        val expected = context.contentResolver.query(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id"), "relative_path = ?", arrayOf(RECORDER_FOLDER), null)?.use { it.count } ?: 0
        assumeTrue(expected > 0)
        // On the tested vivo, the owner-predicate query returned zero even though the folder
        // exposed five files. Keep this device integration regression separate from pure policy tests.
        assertEquals(expected, RecorderMediaSource(context).list().size)
    }

    @Test fun migrationAddsJournalWithoutChangingExistingReminders() {
        val name = "recorder-migration-test.sqlite"
        context.deleteDatabase(name)
        fun helper(version: Int) = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY, audioPath TEXT)")
                        db.execSQL("INSERT INTO reminders VALUES (73, 'original.m4a')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_7_8.migrate(db)
                    }
                }).build()
        )
        try {
            helper(7).use { it.writableDatabase }
            helper(8).use { upgraded ->
                val db = upgraded.writableDatabase
                db.query("SELECT audioPath FROM reminders WHERE id = 73").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("original.m4a", c.getString(0))
                }
                db.execSQL("INSERT INTO recorder_imports VALUES ('media/73@date', 73, 0)")
                db.query("SELECT finished FROM recorder_imports").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(0, c.getInt(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun journalAndReminderCommitTogetherAndConcurrentImportsKeepOneRow() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val store = RoomRecorderImportStore(context, db, afterCommit = {})
            val candidate = ReminderEntity(audioPath = "owned-copy.m4a", nextTriggerAt = 1_800_000_700_000L)
            val results = List(8) { async { store.saveIfAbsent("media/73@date", candidate) } }.awaitAll()
            assertEquals(1, results.map { it.reminder!!.id }.toSet().size)
            assertEquals(1, db.reminderDao().getAllActiveRemindersOnce().size)
            assertEquals(1, store.pending().size)
            val id = results.first().reminder!!.id
            db.reminderDao().deleteReminderById(id)
            assertNull(store.find("media/73@date")!!.reminder)
            store.finish("media/73@date")
            assertTrue(store.find("media/73@date")!!.finished)
            assertTrue(store.pending().isEmpty())
        } finally { db.close() }
    }
}
