package com.ghostgramlabs.speakalert.data.retention

import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import java.io.File

/** Picked content URIs and files outside the app's recordings directory are not recordings. */
class RecordedReminderExpiry(private val recordingsDir: File) {
    fun ownedFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            File(path).takeIf { it.isAbsolute }?.canonicalFile
                ?.takeIf { it.parentFile == recordingsDir.canonicalFile }
        }.getOrNull()
    }

    fun expiresAt(reminder: ReminderEntity): Long? {
        if (ownedFile(reminder.audioPath) == null) return null
        return if (reminder.createdAt > Long.MAX_VALUE - RETENTION_MS) Long.MAX_VALUE
            else reminder.createdAt + RETENTION_MS
    }

    fun isExpired(reminder: ReminderEntity, now: Long = System.currentTimeMillis()): Boolean =
        expiresAt(reminder)?.let { now >= it } ?: false

    companion object {
        const val RETENTION_MS = 7 * 24 * 60 * 60_000L
    }
}
