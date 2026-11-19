package com.ghostgramlabs.speakalert.quickrecord

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.ui.theme.VoiceReminderTheme
import com.ghostgramlabs.speakalert.util.AppLocale
import com.ghostgramlabs.speakalert.util.ThemePrefs
import kotlinx.coroutines.launch

/** Only capture controls are shown over the keyguard; the reminder library stays behind it. */
class QuickActionRecordingActivity : ComponentActivity() {
    private var pendingActivation = false
    private var permissionsGranted by mutableStateOf(false)
    private val createdAt = SystemClock.uptimeMillis()
    private var volumePress = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = canRecord()
        if (permissionsGranted) {
            pendingActivation = true
            lifecycleScope.launch { lifecycle.withResumed { dispatchActivation() } }
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrapContext(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyWindowTheme(this)
        super.onCreate(savedInstanceState)
        configureRecordingWindow(showWhenLocked = true, wakeScreen = true)
        enableEdgeToEdge()
        pendingActivation = savedInstanceState == null && !intent.getBooleanExtra(QuickActionRecordingService.EXTRA_CONTROLS_ONLY, false)
        setContent {
            val theme by (application as VoiceReminderApp).container.settingsRepository.themeMode
                .collectAsState(initial = ThemePrefs.cached(this))
            VoiceReminderTheme(darkTheme = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }) {
                Controls()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted = canRecord()
    }

    override fun onPostResume() {
        super.onPostResume()
        window.decorView.post { dispatchActivation() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) dispatchActivation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!intent.getBooleanExtra(QuickActionRecordingService.EXTRA_CONTROLS_ONLY, false)) {
            pendingActivation = true
            lifecycleScope.launch { lifecycle.withResumed { dispatchActivation() } }
        }
    }

    private fun canRecord() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun dispatchActivation() {
        if (!pendingActivation || !permissionsGranted || !hasWindowFocus()) return
        pendingActivation = false
        try {
            QuickActionRecordingService.toggle(this)
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, R.string.quick_action_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.downTime > createdAt + 750L &&
            QuickActionRecordingService.state.value.phase == QuickRecordingPhase.RECORDING) {
            if (event.repeatCount == 0) volumePress = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && volumePress) {
            volumePress = false
            QuickActionRecordingService.stop(this)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Composable
    private fun Controls() {
        val state by QuickActionRecordingService.state.collectAsState()
        var sawActive by remember { mutableStateOf(state.phase == QuickRecordingPhase.RECORDING || state.phase == QuickRecordingPhase.SAVING) }
        LaunchedEffect(state.phase) {
            if (state.phase == QuickRecordingPhase.RECORDING || state.phase == QuickRecordingPhase.SAVING) sawActive = true
            if (sawActive && state.phase == QuickRecordingPhase.SAVED) finish()
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Mic, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.quick_action_title), style = MaterialTheme.typography.headlineMedium)
                if (!permissionsGranted) {
                    Text(stringResource(R.string.quick_action_permissions))
                    Button(onClick = {
                        val request = { permissions.launch(buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray()) }
                        val keyguard = getSystemService(KeyguardManager::class.java)
                        if (keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(this@QuickActionRecordingActivity,
                            object : KeyguardManager.KeyguardDismissCallback() {
                                override fun onDismissSucceeded() { request() }
                            })
                        else request()
                    }) { Text(stringResource(R.string.quick_action_allow)) }
                    TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) {
                        Text(stringResource(R.string.quick_action_settings))
                    }
                } else {
                    Text(stringResource(when (state.phase) {
                        QuickRecordingPhase.IDLE -> R.string.quick_action_starting
                        QuickRecordingPhase.RECORDING -> R.string.quick_action_recording
                        QuickRecordingPhase.SAVING -> R.string.quick_action_saving
                        QuickRecordingPhase.SAVED -> if (state.scheduled) R.string.quick_action_saved else R.string.quick_action_schedule_failed
                        QuickRecordingPhase.FAILED -> R.string.quick_action_failed
                    }), style = MaterialTheme.typography.titleLarge)
                    if (state.phase == QuickRecordingPhase.RECORDING) {
                        Text(stringResource(R.string.quick_action_recording_detail))
                        Button(onClick = { QuickActionRecordingService.stop(this@QuickActionRecordingActivity) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.quick_action_stop))
                        }
                    }
                }
                TextButton(onClick = { finish() }) {
                    Text(stringResource(if (state.phase == QuickRecordingPhase.RECORDING) R.string.quick_action_close else R.string.action_close))
                }
            }
        }
    }
}
