package com.ghostgramlabs.speakalert.shake

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.data.repository.dataStore
import com.ghostgramlabs.speakalert.ui.AppViewModelProvider
import com.ghostgramlabs.speakalert.ui.addedit.AddEditReminderScreen
import com.ghostgramlabs.speakalert.ui.addedit.AddEditViewModel
import com.ghostgramlabs.speakalert.ui.components.mirrorInRtl
import com.ghostgramlabs.speakalert.ui.theme.VoiceReminderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Opening recordings requires the ordinary device unlock; only capture runs over the lock screen. */
class ShakeRecordingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.ghostgramlabs.speakalert.util.AppLocale.wrapContext(newBase))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        com.ghostgramlabs.speakalert.util.ThemePrefs.applyWindowTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by (application as VoiceReminderApp).container.settingsRepository.themeMode
                .collectAsState(initial = com.ghostgramlabs.speakalert.util.ThemePrefs.cached(this))
            VoiceReminderTheme(darkTheme = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }) {
                // One editor per activity instance. Finishing returns to the inbox beneath it,
                // and releases its ViewModel and temporary copy.
                val draftId = intent.getStringExtra(EXTRA_DRAFT)
                if (draftId != null) {
                    val editor: AddEditViewModel = viewModel(factory = AppViewModelProvider.Factory)
                    AddEditReminderScreen(
                        shakeDraftId = draftId,
                        navigateBack = { finish() },
                        onNavigateUp = { finish() },
                        viewModel = editor
                    )
                } else {
                    var customize by rememberSaveable { mutableStateOf(false) }
                    val store = remember { ShakeSettingsStore(dataStore) }
                    if (customize) ShakeSettingsScreen(store) { customize = false }
                    else Inbox { customize = true }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Inbox(onCustomize: () -> Unit) {
        val state by ShakeRecordingService.state.collectAsState()
        val log by ShakeRecordingService.actionLog.collectAsState()
        val pending = state.mode in listOf(ShakeMode.AFTER_RECORDING, ShakeMode.MINUTES, ShakeMode.HOURS)
        val inboxAvailable = !state.saving && !state.recording && !pending
        val drafts = remember { ShakeDraftStore(filesDir) }
        var files by remember { mutableStateOf(emptyList<File>()) }
        var refresh by remember { mutableIntStateOf(0) }
        var deletion by remember { mutableStateOf<File?>(null) }
        var permissionError by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refresh++
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(state.revision, refresh) {
            files = withContext(Dispatchers.IO) { drafts.list() }
        }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (canListen()) {
                permissionError = false
                lifecycleScope.launch {
                    lifecycle.withResumed { ShakeRecordingService.start(this@ShakeRecordingsActivity) }
                }
            } else permissionError = true
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.shake_title)) }, navigationIcon = {
                IconButton(onClick = { finish() }) {
                    Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back), Modifier.mirrorInRtl())
                }
            })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.shake_description)) }
                item { Text(stringResource(R.string.shake_session_note), style = MaterialTheme.typography.bodySmall) }
                item { OutlinedButton(onClick = onCustomize) { Text(stringResource(R.string.shake_customize)) } }
                item {
                    if (state.listening) {
                        Text(stringResource(if (pending) state.mode.labelRes() else if (state.recording) R.string.shake_recording else R.string.shake_ready))
                        if (state.recording) Button(onClick = { ShakeRecordingService.save(this@ShakeRecordingsActivity) }) {
                            Text(stringResource(R.string.shake_save))
                        }
                        OutlinedButton(onClick = { ShakeRecordingService.stop(this@ShakeRecordingsActivity) }) {
                            Text(stringResource(R.string.shake_disable))
                        }
                    } else Button(enabled = !state.saving && !state.preparing, onClick = {
                        if (canListen()) {
                            permissionError = false
                            ShakeRecordingService.start(this@ShakeRecordingsActivity)
                        } else permissions.launch(buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray())
                    }) { Text(stringResource(R.string.shake_enable)) }
                }
                if (permissionError) item { Text(stringResource(R.string.shake_permissions), color = MaterialTheme.colorScheme.error) }
                state.message?.let { message -> item { Text(stringResource(message)) } }
                state.statusText?.let { status -> item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(status, style = MaterialTheme.typography.titleMedium)
                            if (state.mode == ShakeMode.AFTER_RECORDING) {
                                TextButton(onClick = { ShakeRecordingService.edit(this@ShakeRecordingsActivity) }) {
                                    Text(stringResource(R.string.shake_action_enter))
                                }
                            } else {
                                TextButton(onClick = { ShakeRecordingService.addTime(this@ShakeRecordingsActivity) }) {
                                    Text(stringResource(R.string.shake_action_add))
                                }
                                TextButton(onClick = { ShakeRecordingService.switchMode(this@ShakeRecordingsActivity) }) {
                                    Text(stringResource(R.string.shake_action_switch))
                                }
                            }
                            Button(onClick = { ShakeRecordingService.save(this@ShakeRecordingsActivity) }) {
                                Text(stringResource(R.string.shake_finish_time))
                            }
                        }
                    }
                } }
                state.reminderId?.let { reminderId ->
                    item {
                        TextButton(onClick = {
                            startActivity(android.content.Intent(this@ShakeRecordingsActivity, com.ghostgramlabs.speakalert.MainActivity::class.java)
                                .putExtra("reminderId", reminderId))
                        }) { Text(stringResource(R.string.shake_view_reminder)) }
                    }
                }
                item { Text(stringResource(R.string.shake_inbox), style = MaterialTheme.typography.titleLarge) }
                if (files.isEmpty()) item { Text(stringResource(R.string.shake_empty)) }
                items(files, key = { it.name }) { file ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(DateFormat.getDateTimeInstance().format(Date(file.lastModified())))
                            TextButton(enabled = inboxAvailable, onClick = {
                                // Finalize any ongoing shake recording before previewing a take.
                                ShakeRecordingService.save(this@ShakeRecordingsActivity)
                                startActivity(android.content.Intent(this@ShakeRecordingsActivity, ShakeRecordingsActivity::class.java)
                                    .putExtra(EXTRA_DRAFT, file.nameWithoutExtension))
                            }) { Text(stringResource(R.string.shake_use)) }
                            TextButton(enabled = inboxAvailable, onClick = { deletion = file }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.shake_action_log), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.shake_log_help), style = MaterialTheme.typography.bodySmall)
                    if (log.isNotEmpty()) TextButton(onClick = { ShakeRecordingService.clearLog() }) { Text(stringResource(R.string.shake_clear_log)) }
                }
                items(log) { entry ->
                    Text(DateFormat.getTimeInstance().format(Date(entry.at)) + " · " + entry.text,
                        style = MaterialTheme.typography.bodySmall)
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
        deletion?.let { file ->
            AlertDialog(onDismissRequest = { deletion = null },
                title = { Text(stringResource(R.string.shake_delete_question)) },
                text = { Text(stringResource(R.string.shake_delete_description)) },
                confirmButton = { TextButton(enabled = inboxAvailable, onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { drafts.delete(file.nameWithoutExtension) }
                        refresh++
                    }
                    deletion = null
                }) { Text(stringResource(R.string.action_delete)) } },
                dismissButton = { TextButton(onClick = { deletion = null }) { Text(stringResource(R.string.action_cancel)) } })
        }
    }

    private fun canListen(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            getSystemService(android.app.NotificationManager::class.java)
                .getNotificationChannel("shake_recording")?.importance != android.app.NotificationManager.IMPORTANCE_NONE

    companion object {
        private const val EXTRA_DRAFT = "shakeDraftId"
    }
}
