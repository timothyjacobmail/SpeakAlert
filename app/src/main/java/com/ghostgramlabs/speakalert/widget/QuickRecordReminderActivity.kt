package com.ghostgramlabs.speakalert.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.ui.AppViewModelProvider
import com.ghostgramlabs.speakalert.ui.addedit.AddEditViewModel
import com.ghostgramlabs.speakalert.ui.addedit.RecordingIssue
import com.ghostgramlabs.speakalert.ui.theme.VoiceReminderTheme
import com.ghostgramlabs.speakalert.util.AppLocale
import com.ghostgramlabs.speakalert.util.ThemePrefs
import kotlinx.coroutines.launch

/** A short, visible capture flow. Leaving the activity always releases the microphone. */
class QuickRecordReminderActivity : ComponentActivity() {
    private val editor: AddEditViewModel by viewModels { AppViewModelProvider.Factory }
    private var autoStartPending = true
    private var micGranted by mutableStateOf(false)
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) lifecycleScope.launch { lifecycle.withResumed { startRecording() } }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyWindowTheme(this)
        super.onCreate(savedInstanceState)
        // A retained ViewModel owns the take across rotation. After process death let the user
        // explicitly start a new take instead of silently restarting a lost recording.
        autoStartPending = savedInstanceState == null
        enableEdgeToEdge()
        if (savedInstanceState == null) editor.applyDefaultsForNewReminder()
        setContent {
            val theme by (application as VoiceReminderApp).container.settingsRepository.themeMode
                .collectAsState(initial = ThemePrefs.cached(this))
            VoiceReminderTheme(darkTheme = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }) {
                RecordingScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (autoStartPending) {
            autoStartPending = false
            if (micGranted) startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && editor.uiState.value.isRecording) editor.stopRecording()
        super.onStop()
    }

    private fun startRecording() {
        if (editor.uiState.value.isSaving || editor.uiState.value.saveCompleted) return
        com.ghostgramlabs.speakalert.service.ReminderPlaybackService.stop(this)
        com.ghostgramlabs.speakalert.alarm.ToneAlertPlayer.stop()
        editor.startRecording()
    }

    @Composable
    private fun RecordingScreen() {
        val state by editor.uiState.collectAsState()
        LaunchedEffect(state.saveCompleted) {
            if (state.saveCompleted) {
                Toast.makeText(this@QuickRecordReminderActivity, R.string.ae_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        // Avoid cancelling a database/scheduler handoff while it is being saved.
        BackHandler(enabled = state.isSaving) { }
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.widget_record_title), style = MaterialTheme.typography.headlineMedium)
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                when {
                    state.isSaving || state.saveCompleted -> {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.quick_record_saving))
                    }
                    state.isRecording -> {
                        Text(stringResource(R.string.quick_record_recording), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.quick_record_elapsed, state.recordingElapsedSeconds / 60, state.recordingElapsedSeconds % 60))
                        Button(onClick = editor::stopRecording, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.quick_record_stop))
                        }
                    }
                    !micGranted -> {
                        Text(stringResource(R.string.quick_record_permission))
                        Button(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text(stringResource(R.string.quick_record_permission_button))
                        }
                        TextButton(onClick = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }) { Text(stringResource(R.string.quick_record_settings)) }
                    }
                    else -> {
                        if (state.recordedAudioPath != null) {
                            Text(stringResource(R.string.quick_record_when), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.quick_record_choose))
                            if (state.showError || state.showPastTimeError) {
                                Text(stringResource(R.string.quick_record_save_failed), color = MaterialTheme.colorScheme.error)
                            }
                            if (state.recordingIssue == RecordingIssue.SILENT) {
                                Text(stringResource(R.string.ae_record_silent), color = MaterialTheme.colorScheme.error)
                            }
                            AddEditViewModel.QUICK_RECORD_DELAY_MINUTES.forEach { minutes ->
                                Button(
                                    onClick = { editor.saveQuickRecordedReminder(minutes) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (minutes == 60) stringResource(R.string.quick_record_hour)
                                        else stringResource(R.string.quick_record_minutes, minutes))
                                }
                            }
                        } else {
                            Text(stringResource(if (state.recordingIssue != null) R.string.quick_record_failed else R.string.quick_record_ready))
                        }
                        OutlinedButton(onClick = { startRecording() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(if (state.recordedAudioPath != null) R.string.quick_record_retake else R.string.widget_record_button))
                        }
                    }
                }
                if (!state.isSaving && !state.saveCompleted) {
                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}
