package com.ghostgramlabs.speakalert.quickrecord

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import com.ghostgramlabs.speakalert.MainActivity

/** A launcher with no private content, so system shortcuts can reach it over the keyguard. */
class QuickActionLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val shortcut = isVivoQuickActionLaunch(intent.flags, Build.MANUFACTURER)
        val record = shouldRecordQuickActionLaunch(
            locked = locked,
            shortcut = shortcut,
            launcher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER),
            reminderNavigation = intent.hasExtra("reminderId") || intent.getBooleanExtra("openAddEdit", false),
            enabled = QuickActionSettings.enabled(this)
        )
        android.util.Log.i("QuickRecording", "Launcher flags=${intent.flags.toString(16)} locked=$locked shortcut=$shortcut record=$record")
        configureRecordingWindow(showWhenLocked = record)
        val target = if (record) {
            android.util.Log.i("QuickRecording", "Routing quick-action activation to recorder")
            Intent(this, QuickActionRecordingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else Intent(this, MainActivity::class.java).apply {
            intent.extras?.let { putExtras(it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(target)
        finish()
    }
}
