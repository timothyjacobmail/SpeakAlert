package com.ghostgramlabs.speakalert.recorderimport

import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class RecorderAudio(
    val uri: String,
    val addedAt: Long,
    val savedAt: Long,
    val size: Long,
    val duration: Long,
    val pending: Boolean,
    val owner: String?,
    val folder: String,
    val extension: String = "m4a"
) {
    val key: String get() = "$uri@$addedAt"
    fun isFinished() = (owner == null || owner == RECORDER_PACKAGE) && folder == RECORDER_FOLDER &&
        !pending && size > 0 && duration > 0
}

internal const val RECORDER_PACKAGE = "com.vivo.soundrecorder"
internal const val RECORDER_FOLDER = "Recordings/Record/"
internal const val RECORDER_DELAY_MS = 600_000L
internal const val RECORDER_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000L

internal data class RecorderWatch(val enabledAt: Long, val ignored: Set<String>)
internal data class StoredRecorderImport(val key: String, val reminder: ReminderEntity?, val finished: Boolean)

internal interface RecorderAudioSource {
    fun list(addedSince: Long = 0): List<RecorderAudio>
    /** Return false if the recording changed while copying. Never modifies the original. */
    fun copyFinished(audio: RecorderAudio, destination: File): Boolean
}

internal interface RecorderImportStore {
    suspend fun find(key: String): StoredRecorderImport?
    suspend fun pending(): List<StoredRecorderImport>
    /** The reminder and deduplication entry must commit in the same transaction. */
    suspend fun saveIfAbsent(key: String, reminder: ReminderEntity): StoredRecorderImport
    suspend fun finish(key: String)
}

/** A durable deduplication entry survives reminder deletion, expiry and service restarts. */
internal class RecorderImportEngine(
    private val source: RecorderAudioSource,
    private val store: RecorderImportStore,
    private val directory: File,
    private val watch: () -> RecorderWatch?,
    private val schedule: (ReminderEntity) -> Unit,
    private val confirm: (ReminderEntity) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val onError: (Exception) -> Unit = {}
) {
    suspend fun scan(): Boolean = lock.withLock {
        val session = watch() ?: return@withLock false
        var retry = false
        val recovering = store.pending()
        val recoveringKeys = recovering.map { it.key }.toSet()
        for (stored in recovering) {
            if (watch() != session) return@withLock retry
            try {
                complete(stored)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                retry = true
                onError(failure)
            }
        }
        for (audio in source.list(session.enabledAt / 1_000L * 1_000L)) {
            if (watch() != session) break
            if (audio.key in recoveringKeys || (audio.owner != null && audio.owner != RECORDER_PACKAGE) || audio.folder != RECORDER_FOLDER ||
                audio.key in session.ignored || audio.addedAt < session.enabledAt / 1_000L * 1_000L ||
                audio.savedAt < now() - RECORDER_RETENTION_MS) continue
            try {
                var stored = store.find(audio.key)
                if (stored?.finished == true) continue
                if (stored == null) {
                    if (!audio.isFinished() || audio.savedAt > now() - 2_000L) {
                        retry = true
                        continue
                    }
                    check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording directory" }
                    val extension = audio.extension.takeIf { it in setOf("m4a", "mp3", "aac", "amr", "3gp", "wav", "ogg", "opus") } ?: "m4a"
                    val copy = File(directory, "recorder_${UUID.randomUUID()}.$extension")
                    var owned = false
                    try {
                        if (!source.copyFinished(audio, copy)) {
                            retry = true
                            continue
                        }
                        if (watch() != session) break
                        // Cancellation must not leave a committed row without its journal entry.
                        withContext(NonCancellable) {
                            stored = store.saveIfAbsent(audio.key, ReminderEntity(
                                audioPath = copy.absolutePath,
                                createdAt = audio.savedAt,
                                nextTriggerAt = audio.savedAt + RECORDER_DELAY_MS
                            ))
                            owned = stored?.reminder?.audioPath == copy.absolutePath
                        }
                    } finally {
                        if (!owned) copy.delete()
                    }
                }
                val current = stored ?: continue
                if (current.finished) continue
                complete(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                retry = true
                onError(failure)
            }
        }
        retry
    }

    private suspend fun complete(stored: StoredRecorderImport) {
        val reminder = stored.reminder
        // A crash after the database commit must not revive a deleted, completed, fired or expired reminder.
        if (reminder != null && !reminder.isCompleted && reminder.lastFiredAt == null &&
            reminder.createdAt > now() - RECORDER_RETENTION_MS &&
            reminder.audioPath?.let { File(it).isFile } == true) {
            schedule(reminder)
            confirm(reminder)
        }
        store.finish(stored.key)
    }

    companion object {
        private val lock = Mutex()
    }
}
