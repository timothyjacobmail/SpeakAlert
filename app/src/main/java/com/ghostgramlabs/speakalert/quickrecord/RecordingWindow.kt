package com.ghostgramlabs.speakalert.quickrecord

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/** Android 8.0 predates the Activity lock-screen APIs. */
@Suppress("DEPRECATION")
internal fun Activity.configureRecordingWindow(showWhenLocked: Boolean, wakeScreen: Boolean = false) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        setShowWhenLocked(showWhenLocked)
        setTurnScreenOn(wakeScreen)
    } else {
        val show = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        val wake = WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        if (showWhenLocked) window.addFlags(show) else window.clearFlags(show)
        if (wakeScreen) window.addFlags(wake) else window.clearFlags(wake)
    }
}
