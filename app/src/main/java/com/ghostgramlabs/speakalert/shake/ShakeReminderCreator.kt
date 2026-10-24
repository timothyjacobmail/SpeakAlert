package com.ghostgramlabs.speakalert.shake

import com.ghostgramlabs.speakalert.alarm.AlarmScheduler
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.repository.ReminderRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ShakeReminderResult(val reminder: ReminderEntity, val scheduled: Boolean)

/** The inbox original is consumed only after a reminder owns a durable copy of its audio. */
class ShakeReminderCreator(
    private val repository: ReminderRepository,
    private val scheduler: AlarmScheduler,
    private val filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun create(
        recording: File,
        stoppedAt: Long,
        followUpCheckMinutes: Int = 0,
        triggerAt: Long = stoppedAt + DEFAULT_DELAY_MS
    ): ShakeReminderResult = withContext(ioDispatcher + NonCancellable) {
        // Complete the handoff even when the controls close during a database write.
        require(recording.isFile && recording.length() > 0) { "No completed recording" }
        val audioDir = File(filesDir, "reminders")
        check(audioDir.isDirectory || audioDir.mkdirs())
        val audio = File(audioDir, "${UUID.randomUUID()}.m4a")
        val saved: ReminderEntity
        try {
            recording.copyTo(audio)
            val reminder = ReminderEntity(
                audioPath = audio.absolutePath,
                createdAt = stoppedAt,
                nextTriggerAt = triggerAt,
                followUpCheckMinutes = followUpCheckMinutes.coerceAtLeast(0)
            )
            saved = reminder.copy(id = repository.insertReminder(reminder))
        } catch (failure: Exception) {
            audio.delete()
            throw failure
        }

        // A scheduling failure must keep the database row and its audio so the user can edit
        // and retry that same reminder. Returning its id avoids creating a duplicate draft.
        val scheduled = try {
            scheduler.schedule(saved)
            true
        } catch (failure: Exception) {
            android.util.Log.w("ShakeRecording", "Reminder saved but scheduling failed", failure)
            false
        }
        recording.delete()
        ShakeReminderResult(saved, scheduled)
    }

    companion object {
        const val DEFAULT_DELAY_MS = 5 * 60 * 1_000L
    }
}
