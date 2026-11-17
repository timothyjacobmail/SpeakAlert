package com.ghostgramlabs.speakalert.recorderimport

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

@RequiresApi(Build.VERSION_CODES.Q)
internal class RecorderMediaSource(private val context: Context) : RecorderAudioSource {
    override fun list(addedSince: Long): List<RecorderAudio> {
        val columns = arrayOf("_id", "volume_name", "date_added", "date_modified", "_size", "duration",
            "is_pending", "owner_package_name", "relative_path", "_display_name")
        return buildList {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns,
                // Some vivo builds expose Recorder files while returning zero rows for an
                // OWNER_PACKAGE_NAME predicate. Query the dedicated folder and check any
                // disclosed owner in the engine; null ownership is allowed only in this folder.
                "relative_path = ? AND date_added >= ?",
                arrayOf(RECORDER_FOLDER, (addedSince / 1_000L).toString()), "date_added ASC")?.use { c ->
                while (c.moveToNext()) {
                    val volume = c.getString(1) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    add(RecorderAudio(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), c.getLong(0)).toString(),
                        c.getLong(2) * 1_000L, maxOf(c.getLong(2), c.getLong(3)) * 1_000L,
                        c.getLong(4), c.getLong(5), c.getInt(6) != 0,
                        c.getString(7), c.getString(8).orEmpty(),
                        c.getString(9).orEmpty().substringAfterLast('.', "m4a").lowercase(java.util.Locale.ROOT)
                    ))
                }
            } ?: error("Audio library unavailable")
        }
    }

    override fun copyFinished(audio: RecorderAudio, destination: File): Boolean {
        if (!audio.isFinished()) return false
        context.contentResolver.openInputStream(Uri.parse(audio.uri))?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return false
        if (destination.length() != audio.size || list(audio.addedAt).none { it == audio }) return false
        // Validate a finalized, decodable container rather than importing a still-open take.
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(destination.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0
        } finally {
            retriever.release()
        }
    }
}
