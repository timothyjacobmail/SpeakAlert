package com.ghostgramlabs.speakalert.recorderimport

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.ui.theme.VoiceReminderTheme
import com.ghostgramlabs.speakalert.util.AppLocale
import com.ghostgramlabs.speakalert.util.ThemePrefs
import kotlinx.coroutines.launch

class RecorderImportSettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyWindowTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by (application as VoiceReminderApp).container.settingsRepository.themeMode
                .collectAsState(initial = ThemePrefs.cached(this))
            VoiceReminderTheme(darkTheme = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.recorder_import_back)) }
                        RecorderImportSettingsCard()
                    }
                }
            }
        }
    }
}

@Composable
fun RecorderImportSettingsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { RecorderImportSettings.preferences(context) }
    var enabled by remember { mutableStateOf(RecorderImportSettings.enabled(context)) }
    var busy by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            enabled = RecorderImportSettings.enabled(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun enable() {
        busy = true
        scope.launch {
            try {
                if (!RecorderImportSettings.enabled(context)) RecorderImportSettings.enable(context)
                RecorderImportSettings.restore(context)
                enabled = RecorderImportSettings.enabled(context)
            } catch (failure: Exception) {
                android.util.Log.w("RecorderImport", "Could not enable Recorder reminders", failure)
                Toast.makeText(context, R.string.recorder_import_enable_failed, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (RecorderImportSettings.hasAudioAccess(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()) enable()
        else Toast.makeText(context, R.string.recorder_import_permissions, Toast.LENGTH_LONG).show()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.recorder_import_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, enabled = !busy && Build.VERSION.SDK_INT >= 29, onCheckedChange = { checked ->
                    if (!checked) {
                        RecorderImportSettings.disable(context)
                        enabled = false
                    } else if (RecorderImportSettings.hasAudioAccess(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                        enable()
                    } else {
                        permissions.launch(buildList {
                            add(RecorderImportSettings.permission())
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray())
                    }
                })
            }
            Text(stringResource(R.string.recorder_import_description), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.recorder_import_originals), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (Build.VERSION.SDK_INT < 29) R.string.recorder_import_unsupported else R.string.recorder_import_background), style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (enabled) {
                TextButton(onClick = {
                    if (RecorderImportSettings.hasAudioAccess(context)) RecorderImportSettings.restore(context)
                    else permissions.launch(arrayOf(RecorderImportSettings.permission()))
                }) { Text(stringResource(R.string.recorder_import_restart)) }
            }
        }
    }
}
