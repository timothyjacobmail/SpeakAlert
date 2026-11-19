package com.ghostgramlabs.speakalert.data.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.repository.ReminderRepository
import com.ghostgramlabs.speakalert.domain.RecurrenceUtils
import com.ghostgramlabs.speakalert.domain.models.MissedPolicy
import com.ghostgramlabs.speakalert.domain.models.RecurrenceType
import com.ghostgramlabs.speakalert.util.FileLogger
import com.ghostgramlabs.speakalert.util.ReminderAudioSource
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exports upcoming (active) reminders to a single ZIP file and imports them back.
 *
 * The ZIP contains `backup.json` (metadata + reminder fields, enums stored as names so the format
 * survives refactors) plus an `audio/` entry per reminder whose recording/audio file still exists.
 * On import, audio is extracted into the app's private recordings dir and reminders are inserted as
 * new rows: past one-time reminders are skipped, past recurring reminders are advanced to their
 * next occurrence, and reminders identical to an existing active one are skipped as duplicates.
 */
object ReminderBackupManager {

    const val MIME_TYPE = "application/zip"
    private const val FORMAT_VERSION = 1
    private const val JSON_ENTRY = "backup.json"
    private const val AUDIO_DIR_ENTRY = "audio/"

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun suggestedFileName(): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        return "speakalert-backup-$stamp.zip"
    }

    private data class BackupReminder(
        val title: String?,
        val reminderText: String?,
        val transcript: String?,
        val audioEntry: String?,
        val createdAt: Long,
        val nextTriggerAt: Long,
        val recurrenceType: String,
        val recurrenceJson: String?,
        val missedPolicy: String,
        val loopPlayback: Boolean,
        val followUpCheckMinutes: Int,
        /**
         * The chosen file's content URI, kept only when its bytes could not be copied into the
         * backup. Absent in older backups.
         */
        val audioUri: String? = null
    )

    private data class BackupFile(
        val format: Int,
        val exportedAt: Long,
        val reminders: List<BackupReminder>
    )

    data class ImportResult(
        val imported: Int,
        val skippedDuplicates: Int,
        val skippedExpired: Int
    )

    /** Writes [reminders] to [uri]; returns how many were written. */
    fun export(context: Context, uri: Uri, reminders: List<ReminderEntity>): Int {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("Cannot open backup destination")
        ZipOutputStream(output.buffered()).use { zip ->
            // Audio is written before backup.json: whether a chosen file is still readable is only
            // known once it is opened, and the JSON must list exactly the entries that exist.
            // Import handles entries in any order.
            val entries = reminders.mapIndexed { index, reminder ->
                val source = reminder.audioPath?.takeIf { it.isNotBlank() }
                val audioEntry = source?.let { writeAudioEntry(context, zip, index, it) }
                BackupReminder(
                    title = reminder.title,
                    reminderText = reminder.reminderText,
                    transcript = reminder.transcript,
                    audioEntry = audioEntry,
                    createdAt = reminder.createdAt,
                    nextTriggerAt = reminder.nextTriggerAt,
                    recurrenceType = reminder.recurrenceType.name,
                    recurrenceJson = reminder.recurrenceJson,
                    missedPolicy = reminder.missedPolicy.name,
                    loopPlayback = reminder.loopPlayback,
                    followUpCheckMinutes = reminder.followUpCheckMinutes,
                    // An unreadable chosen file keeps its reference, so the restored reminder
                    // reports its audio as unavailable just like the original instead of
                    // silently turning into a text-only reminder.
                    audioUri = source?.takeIf { audioEntry == null && isContentUri(it) }
                )
            }
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            zip.write(
                gson.toJson(
                    BackupFile(
                        format = FORMAT_VERSION,
                        exportedAt = System.currentTimeMillis(),
                        reminders = entries
                    )
                ).toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        return reminders.size
    }

    /**
     * Copies a recording file or a chosen content URI into [zip]. Returns the entry name, or null
     * when the source can no longer be read (deleted, or picker access revoked).
     */
    private fun writeAudioEntry(context: Context, zip: ZipOutputStream, index: Int, source: String): String? {
        val name: String
        val input = if (isContentUri(source)) {
            name = ReminderAudioSource.resolveDisplayName(context, source)?.takeIf { it.isNotBlank() } ?: "audio"
            runCatching { context.contentResolver.openInputStream(Uri.parse(source)) }.getOrNull()
                ?: return null
        } else {
            val file = File(source).takeIf { it.isFile && it.canRead() } ?: return null
            name = file.name
            file.inputStream()
        }
        val entryName = "$AUDIO_DIR_ENTRY${index}_${name.replace('/', '_').replace('\\', '_')}"
        input.use {
            zip.putNextEntry(ZipEntry(entryName))
            it.copyTo(zip)
            zip.closeEntry()
        }
        return entryName
    }

    private fun isContentUri(source: String): Boolean = source.startsWith("content://")

    /**
     * Reads a backup from [uri], inserts the reminders through [repository], and schedules each
     * inserted reminder via [scheduleReminder].
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        repository: ReminderRepository,
        scheduleReminder: (ReminderEntity) -> Unit
    ): ImportResult {
        var backup: BackupFile? = null
        val extractedAudio = mutableMapOf<String, File>()
        // Same dir AddEditViewModel records into, so imported audio is managed like native audio.
        val audioDir = File(context.filesDir, "reminders").apply { mkdirs() }
        val importStamp = System.currentTimeMillis()

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open backup file")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == JSON_ENTRY ->
                        backup = gson.fromJson(InputStreamReader(zip, Charsets.UTF_8), BackupFile::class.java)
                    !entry.isDirectory && entry.name.startsWith(AUDIO_DIR_ENTRY) -> {
                        // File(...).name drops any path segments, guarding against zip-slip names.
                        val safeName = File(entry.name).name
                        val target = File(audioDir, "imported_${importStamp}_$safeName")
                        target.outputStream().use { zip.copyTo(it) }
                        extractedAudio[entry.name] = target
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val parsed = backup ?: throw IllegalArgumentException("Not a valid backup file")
        val now = System.currentTimeMillis()
        val existing = repository.getAllActiveReminders()
        var imported = 0
        var skippedDuplicates = 0
        var skippedExpired = 0

        for (item in parsed.reminders) {
            val recurrenceType = runCatching { RecurrenceType.valueOf(item.recurrenceType) }
                .getOrDefault(RecurrenceType.NONE)
            val missedPolicy = runCatching { MissedPolicy.valueOf(item.missedPolicy) }
                .getOrDefault(MissedPolicy.SKIP_TO_NEXT)

            // Only files extracted by this import may be cleaned up; a kept URI is never a file.
            val extractedFile = item.audioEntry?.let { extractedAudio[it] }
            var candidate = ReminderEntity(
                title = item.title,
                reminderText = item.reminderText,
                transcript = item.transcript,
                audioPath = extractedFile?.absolutePath
                    ?: item.audioUri?.takeIf { isContentUri(it) },
                createdAt = item.createdAt,
                nextTriggerAt = item.nextTriggerAt,
                recurrenceType = recurrenceType,
                recurrenceJson = item.recurrenceJson,
                missedPolicy = missedPolicy,
                loopPlayback = item.loopPlayback,
                followUpCheckMinutes = item.followUpCheckMinutes
            )

            if (com.ghostgramlabs.speakalert.data.retention.RecordedReminderExpiry(File(context.filesDir, "reminders")).isExpired(candidate, now)) {
                skippedExpired++
                extractedFile?.delete()
                continue
            }

            if (candidate.nextTriggerAt <= now) {
                val nextTrigger = if (recurrenceType != RecurrenceType.NONE) {
                    RecurrenceUtils.computeNextTrigger(candidate, now)
                } else {
                    null
                }
                if (nextTrigger == null) {
                    skippedExpired++
                    extractedFile?.delete()
                    continue
                }
                candidate = candidate.copy(nextTriggerAt = nextTrigger)
            }

            val isDuplicate = existing.any {
                it.title == candidate.title &&
                    it.reminderText == candidate.reminderText &&
                    it.recurrenceType == candidate.recurrenceType &&
                    it.recurrenceJson == candidate.recurrenceJson &&
                    it.nextTriggerAt == candidate.nextTriggerAt
            }
            if (isDuplicate) {
                skippedDuplicates++
                extractedFile?.delete()
                continue
            }

            val id = repository.insertReminder(candidate)
            scheduleReminder(candidate.copy(id = id))
            imported++
        }

        FileLogger.log(
            "BACKUP: Import complete - imported=$imported, duplicates=$skippedDuplicates, expired=$skippedExpired"
        )
        return ImportResult(imported, skippedDuplicates, skippedExpired)
    }
}
