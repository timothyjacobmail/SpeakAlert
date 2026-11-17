package com.ghostgramlabs.speakalert.recorderimport

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object RecorderImportSettings {
    const val PREFS = "recorder_import"
    fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = preferences(context).getBoolean("enabled", false)
    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasAudioAccess(context: Context) = Build.VERSION.SDK_INT >= 29 &&
        ContextCompat.checkSelfPermission(context, permission()) == PackageManager.PERMISSION_GRANTED

    fun watch(context: Context): RecorderWatch? {
        val prefs = preferences(context)
        if (!prefs.getBoolean("enabled", false)) return null
        return RecorderWatch(prefs.getLong("enabled_at", 0L), prefs.getStringSet("ignored", emptySet()).orEmpty().toSet())
    }

    suspend fun enable(context: Context) = withContext(Dispatchers.IO) {
        check(hasAudioAccess(context)) { "Audio access is required" }
        check(Build.VERSION.SDK_INT >= 29)
        // Commit the baseline before enabling or starting the observer. Never scan old takes.
        val startedAt = System.currentTimeMillis()
        val ignored = RecorderMediaSource(context).list().map { it.key }.toSet()
        check(preferences(context).edit().putLong("enabled_at", startedAt).putStringSet("ignored", ignored)
            .putBoolean("enabled", true).commit()) { "Could not save Recorder reminders setting" }
    }

    fun disable(context: Context) {
        preferences(context).edit().putBoolean("enabled", false).apply()
        context.stopService(Intent(context, RecorderMonitorService::class.java))
    }

    /** Called from a visible activity or a system boot/package-replaced exemption. */
    fun restore(context: Context) {
        if (enabled(context) && hasAudioAccess(context)) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RecorderMonitorService::class.java)) }
                .onFailure { android.util.Log.w("RecorderImport", "Open Recorder reminders to restart monitoring", it) }
        }
    }
}
