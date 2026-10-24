package com.ghostgramlabs.speakalert.shake

import java.io.File
import java.util.UUID

/** Completed takes survive process death. In-progress files are never offered to the editor. */
class ShakeDraftStore(filesDir: File) {
    private val directory = File(filesDir, "shake_recordings")

    fun newRecording(): File {
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, "${UUID.randomUUID()}.recording")
    }

    fun finish(recording: File): File {
        require(recording.parentFile == directory && recording.extension == "recording")
        check(recording.length() > 0)
        val finished = File(directory, "${recording.nameWithoutExtension}.m4a")
        check(recording.renameTo(finished))
        return finished
    }

    fun find(id: String): File? {
        if (!ID.matches(id)) return null
        return File(directory, "$id.m4a").takeIf { it.isFile && it.length() > 0 }
    }

    fun list(): List<File> = directory.listFiles()
        ?.filter { it.extension == "m4a" && ID.matches(it.nameWithoutExtension) && it.length() > 0 }
        ?.sortedByDescending { it.lastModified() }.orEmpty()

    fun delete(id: String): Boolean = find(id)?.delete() ?: false

    private companion object {
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
