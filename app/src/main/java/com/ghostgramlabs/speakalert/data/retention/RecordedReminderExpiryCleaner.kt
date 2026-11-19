package com.ghostgramlabs.speakalert.data.retention

import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import java.io.File
import kotlinx.coroutines.CancellationException

interface RecordedReminderExpiryStore {
    /** Atomically remove expired rows and their missed-history entries, returning removed rows. */
    suspend fun removeExpired(now: Long): List<ReminderEntity>
    suspend fun referencedAudioPaths(): List<String>
}

/** Shared audio stays until the last reminder using it expires or is deleted. */
class RecordedReminderExpiryCleaner(
    private val store: RecordedReminderExpiryStore,
    private val recordingsDir: File,
    private val onExpired: (ReminderEntity) -> Unit,
    private val onError: (Exception) -> Unit = {}
) {
    suspend fun clean(now: Long = System.currentTimeMillis()): Int {
        val policy = RecordedReminderExpiry(recordingsDir)
        val removed = store.removeExpired(now)
        removed.forEach { reminder ->
            try { onExpired(reminder) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                onError(failure)
            }
        }
        val referenced = store.referencedAudioPaths().mapNotNull(policy::ownedFile).toSet()
        val candidates = removed.mapNotNull { policy.ownedFile(it.audioPath) }.toMutableSet()
        // Recover old orphaned recordings after a process death between row and file deletion.
        recordingsDir.listFiles().orEmpty().filterTo(candidates) {
            it.isFile && it.extension == "m4a" && it.lastModified() <= now - RecordedReminderExpiry.RETENTION_MS
        }
        candidates.forEach { candidate ->
            val owned = policy.ownedFile(candidate.path) ?: return@forEach
            if (owned !in referenced && owned.isFile && !owned.delete()) {
                onError(java.io.IOException("Could not remove expired recording"))
            }
        }
        return removed.size
    }
}
