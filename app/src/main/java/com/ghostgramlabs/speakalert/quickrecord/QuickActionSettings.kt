package com.ghostgramlabs.speakalert.quickrecord

import android.content.Context
import android.content.Intent

object QuickActionSettings {
    fun enabled(context: Context): Boolean = context.getSharedPreferences("quick_action_recording", Context.MODE_PRIVATE)
        .getBoolean("locked_launcher_records", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("quick_action_recording", Context.MODE_PRIVATE)
            .edit().putBoolean("locked_launcher_records", enabled).apply()
    }
}

internal fun shouldRecordQuickActionLaunch(
    locked: Boolean,
    shortcut: Boolean,
    launcher: Boolean,
    reminderNavigation: Boolean,
    enabled: Boolean
) = (locked || shortcut) && launcher && !reminderNavigation && enabled

/** Vivo's observed screen-off shortcut carries TASK_ON_HOME even after unlocking. */
internal fun isVivoQuickActionLaunch(flags: Int, manufacturer: String): Boolean =
    manufacturer.equals("vivo", ignoreCase = true) && (flags and Intent.FLAG_ACTIVITY_TASK_ON_HOME) != 0
