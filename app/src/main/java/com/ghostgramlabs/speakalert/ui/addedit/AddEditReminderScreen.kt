package com.ghostgramlabs.speakalert.ui.addedit

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import com.ghostgramlabs.speakalert.util.launchOrToast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.ui.components.AppTimePickerDialog
import com.ghostgramlabs.speakalert.ui.components.mirrorInRtl
import com.ghostgramlabs.speakalert.ui.AppViewModelProvider
import com.ghostgramlabs.speakalert.domain.models.RecurrenceType
import com.ghostgramlabs.speakalert.ui.components.PremiumHeaderCard
import com.ghostgramlabs.speakalert.ui.components.PremiumScreenBackground
import com.ghostgramlabs.speakalert.ui.components.SectionCard
import com.ghostgramlabs.speakalert.ui.components.SystemDatePickerDialog
import com.ghostgramlabs.speakalert.ui.components.SystemTimePickerDialog
import com.ghostgramlabs.speakalert.ui.components.shouldUseSystemDateTimePickers
import com.ghostgramlabs.speakalert.util.APP_DISPLAY_NAME
import com.ghostgramlabs.speakalert.util.sanitizeUnitFloat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditReminderScreen(
    reminderId: Long = -1L,
    shakeDraftId: String? = null,
    navigateBack: () -> Unit,
    onNavigateUp: () -> Unit,
    viewModel: AddEditViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    // Hidden preferences simplify new reminders, but never conceal data already attached to the
    // reminder being edited.
    val showVoiceRecordingSection = uiState.showVoiceRecordingSection ||
        (!uiState.recordedAudioPath.isNullOrBlank() && !uiState.isCustomAudioFile)
    val showAudioFileSection = uiState.showAudioFileSection ||
        (!uiState.recordedAudioPath.isNullOrBlank() && uiState.isCustomAudioFile)
    val showTypedReminderSection = uiState.showTypedReminderSection || uiState.reminderText.isNotBlank()
    val showShortLabelSection = uiState.showShortLabelSection || uiState.title.isNotBlank()
    val context = LocalContext.current
    var showUnsavedDialog by remember { mutableStateOf(false) }
    val requestExit: () -> Unit = {
        if (uiState.hasUnsavedChanges && !uiState.saveCompleted) {
            showUnsavedDialog = true
        } else {
            viewModel.discardDraft()
            onNavigateUp()
        }
    }

    BackHandler(enabled = uiState.hasUnsavedChanges && !uiState.saveCompleted) {
        showUnsavedDialog = true
    }
    
    // Load reminder if editing, otherwise seed user-level defaults for new reminders.
    LaunchedEffect(reminderId) {
        // Whatever was playing (the alarm, or Play on the details screen) must not carry on
        // into the editor, where it would talk over a preview or end up in a new recording.
        com.ghostgramlabs.speakalert.service.ReminderPlaybackService.stop(context)
        com.ghostgramlabs.speakalert.alarm.ToneAlertPlayer.stop()
        if (reminderId != -1L) {
            viewModel.loadReminder(reminderId)
        } else {
            viewModel.applyDefaultsForNewReminder(shakeDraftId)
        }
    }
    
    // Save success state for animation
    var showSaveSuccess by remember { mutableStateOf(false) }
    
    LaunchedEffect(uiState.saveCompleted) {
        if (uiState.saveCompleted) {
            showSaveSuccess = true
            android.widget.Toast.makeText(context, context.getString(R.string.ae_saved), android.widget.Toast.LENGTH_SHORT).show()
            // Short delay for animation before navigating
            kotlinx.coroutines.delay(800L)
            navigateBack()
        }
    }

    LaunchedEffect(uiState.showPastTimeError) {
        if (uiState.showPastTimeError) {
             android.widget.Toast.makeText(context, context.getString(R.string.ae_cannot_past), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(uiState.recordingIssue) {
        val issue = uiState.recordingIssue
        val messageRes = when (issue) {
            RecordingIssue.TOO_SHORT -> R.string.ae_record_too_short
            RecordingIssue.CAPTURE_FAILED -> R.string.ae_record_failed
            RecordingIssue.IMPORT_FAILED -> R.string.shake_import_failed
            RecordingIssue.SILENT -> R.string.ae_record_silent
            null -> null
        }
        if (messageRes != null) {
            // A quick tap is everyday behaviour, so keep that nudge short; the two that ask the
            // user to go check something stay up long enough to read.
            val duration = if (issue == RecordingIssue.TOO_SHORT) {
                Toast.LENGTH_SHORT
            } else {
                Toast.LENGTH_LONG
            }
            Toast.makeText(context, context.getString(messageRes), duration).show()
            viewModel.clearRecordingIssue()
        }
    }
    
    val micPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        // Persist read permission so reminder audio still works after app restart.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers may not grant persistable permissions; continue with best effort.
        }

        val displayName = resolveDisplayName(context, uri)
        viewModel.setCustomAudio(uri.toString(), displayName)
    }
    var showDatePickerDialog by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    val dateFormatter = remember(com.ghostgramlabs.speakalert.util.TimeFormat.timePattern) { java.text.SimpleDateFormat("EEE, MMM d, yyyy", java.util.Locale.getDefault()) }
    val timeFormatter = remember(com.ghostgramlabs.speakalert.util.TimeFormat.timePattern) { java.text.SimpleDateFormat(com.ghostgramlabs.speakalert.util.TimeFormat.timePattern, java.util.Locale.getDefault()) }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.ae_unsaved_title)) },
            text = { Text(stringResource(R.string.ae_unsaved_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsavedDialog = false
                        viewModel.saveReminder()
                    },
                    enabled = !uiState.isSaving
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(stringResource(R.string.ae_keep_editing))
                    }
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            viewModel.discardDraft()
                            onNavigateUp()
                        }
                    ) {
                        Text(stringResource(R.string.action_discard))
                    }
                }
            }
        )
    }

    if (uiState.isImporting) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (uiState.initialReminderId != -1L) stringResource(R.string.ae_title_edit) else stringResource(R.string.ae_title_new),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = requestExit) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier.mirrorInRtl()
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.saveReminder() },
                        enabled = !uiState.isSaving && !uiState.saveCompleted
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_save))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                )
            )
        }
    ) { innerPadding ->
        PremiumScreenBackground(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                PremiumHeaderCard(
                    title = if (uiState.initialReminderId != -1L) stringResource(R.string.ae_header_edit) else stringResource(R.string.ae_header_create),
                    subtitle = stringResource(
                        R.string.ae_datetime_at,
                        dateFormatter.format(java.util.Date(uiState.triggerTime)),
                        timeFormatter.format(java.util.Date(uiState.triggerTime))
                    )
                )

            SectionCard(title = stringResource(R.string.ae_section_content)) {
                Text(
                    text = stringResource(R.string.ae_content_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!showVoiceRecordingSection && !showAudioFileSection && !showTypedReminderSection) {
                    Text(
                        text = stringResource(R.string.ae_no_content_sections),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                    // 1. Voice Recording Section
                    if (showVoiceRecordingSection) {
                        com.ghostgramlabs.speakalert.ui.components.VoiceRecorderCard(
                        isRecording = uiState.isRecording,
                        isPlaying = uiState.isPlaying && !uiState.isCustomAudioFile,
                        hasRecording = uiState.recordedAudioPath != null && !uiState.isCustomAudioFile,
                        onRecordClick = {
                            if (micPermissionState.status.isGranted) {
                                viewModel.startRecording()
                            } else {
                                micPermissionState.launchPermissionRequest()
                            }
                        },
                        onStopClick = { viewModel.stopRecording() },
                        onPlayClick = { viewModel.playRecording() },
                        onStopPlaybackClick = { viewModel.stopPlayback() },
                        playbackProgress = uiState.playbackProgress,
                        onSeek = { viewModel.seekTo(it) },
                        recordingElapsedSeconds = uiState.recordingElapsedSeconds,
                        currentAmplitude = uiState.currentAmplitude
                        )
                    }

                    if (showVoiceRecordingSection && showAudioFileSection) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.ae_or_choose_audio),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 3. Choose Audio File section (new feature extension)
                    if (showAudioFileSection) {
                        Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (uiState.isCustomAudioFile && !uiState.recordedAudioPath.isNullOrBlank()) {
                                val previewStopCd = stringResource(R.string.ae_cd_stop_preview)
                                val previewPlayCd = stringResource(R.string.ae_cd_play_preview)
                                val previewProgressCd = stringResource(R.string.ae_cd_audio_progress)
                                val previewPercent = stringResource(
                                    R.string.a11y_percent,
                                    (uiState.playbackProgress.sanitizeUnitFloat() * 100).toInt()
                                )
                                Text(
                                    text = uiState.customAudioFileName ?: stringResource(R.string.ae_selected_audio),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (uiState.isPlaying) viewModel.stopPlayback() else viewModel.playRecording()
                                        },
                                        modifier = Modifier
                                            .size(56.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = CircleShape
                                            )
                                            .semantics {
                                                contentDescription = if (uiState.isPlaying) previewStopCd else previewPlayCd
                                            }
                                    ) {
                                        Icon(
                                            imageVector = if (uiState.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Slider(
                                        value = uiState.playbackProgress.sanitizeUnitFloat(),
                                        onValueChange = { viewModel.seekTo(it) },
                                        enabled = uiState.isPlaying,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 8.dp)
                                            .semantics {
                                                contentDescription = previewProgressCd
                                                stateDescription = previewPercent
                                            }
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { viewModel.removeCustomAudio() }
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.action_remove))
                                    }
                                    TextButton(
                                        onClick = {
                                            audioPickerLauncher.launchOrToast(
                                                context,
                                                arrayOf(
                                                    "audio/mpeg",
                                                    "audio/mp3",
                                                    "audio/wav",
                                                    "audio/x-wav",
                                                    "audio/mp4",
                                                    "audio/m4a",
                                                    "audio/ogg",
                                                    "audio/*"
                                                ),
                                                R.string.file_picker_unavailable
                                            )
                                        },
                                        enabled = !uiState.isRecording
                                    ) {
                                        Text(stringResource(R.string.action_change))
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        audioPickerLauncher.launchOrToast(
                                            context,
                                            arrayOf(
                                                "audio/mpeg",
                                                "audio/mp3",
                                                "audio/wav",
                                                "audio/x-wav",
                                                "audio/mp4",
                                                "audio/m4a",
                                                "audio/ogg",
                                                "audio/*"
                                            ),
                                            R.string.file_picker_unavailable
                                        )
                                    },
                                    enabled = !uiState.isRecording
                                ) {
                                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.ae_choose_audio_file))
                                }
                            }
                        }
                        }
                    }

                    if (showTypedReminderSection && (showVoiceRecordingSection || showAudioFileSection)) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.ae_or_type),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 5. Text Input (Message)
                    if (showTypedReminderSection) {
                        OutlinedTextField(
                        value = uiState.reminderText,
                        onValueChange = { if (it.length <= 1000) viewModel.updateReminderText(it) },
                        label = { Text(stringResource(R.string.ae_msg_label)) },
                        placeholder = { Text(stringResource(R.string.ae_msg_placeholder)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 200.dp),
                        minLines = 4,
                        maxLines = 6,
                        shape = RoundedCornerShape(12.dp),
                        isError = uiState.showError,
                        supportingText = {
                            Column {
                                Text(
                                    text = stringResource(R.string.ae_msg_help),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = stringResource(R.string.ae_char_count, uiState.reminderText.length, 1000),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp),
                                    textAlign = TextAlign.End
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                        )
                    }

                    if (uiState.showError) {
                        Text(
                            text = stringResource(R.string.ae_need_content),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
            }

            if (showShortLabelSection) {
                SectionCard(title = stringResource(R.string.ae_section_label)) {
                Text(
                    text = stringResource(R.string.ae_label_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.title,
                        onValueChange = { if (it.length <= 40) viewModel.updateTitle(it) },
                        label = { Text(stringResource(R.string.ae_section_label)) },
                        placeholder = { Text(stringResource(R.string.ae_label_placeholder)) },
                        supportingText = {
                            // Stacked, not side by side: a longer translation of the hint would
                            // otherwise squeeze the counter into a one-character column and wrap
                            // it vertically. Matches the reminder message field above.
                            Column {
                                Text(
                                    text = stringResource(R.string.ae_label_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = stringResource(R.string.ae_char_count, uiState.title.length, 40),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 2.dp),
                                    textAlign = TextAlign.End
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )
                }
            }

            SectionCard(title = stringResource(R.string.ae_section_schedule)) {
                    val stateOn = stringResource(R.string.state_on)
                    val stateOff = stringResource(R.string.state_off)
                    val stateActiveLabel = stringResource(R.string.state_active)
                    val neverLabel = stringResource(R.string.ae_repeat_never)
                    // UI-only enhancement: keep the same stored timestamp, but make selection clearer.
                    Text(
                        text = stringResource(R.string.ae_when_fire),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.ae_datetime_at,
                            dateFormatter.format(java.util.Date(uiState.triggerTime)),
                            timeFormatter.format(java.util.Date(uiState.triggerTime))
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                    ) {
                        Column {
                    // Date Row
                    ScheduleRow(
                        icon = Icons.Outlined.CalendarMonth,
                        label = stringResource(R.string.ae_date),
                        value = dateFormatter.format(java.util.Date(uiState.triggerTime)),
                        onClick = { showDatePickerDialog = true }
                    )
                    
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    
                    // Time Row
                    ScheduleRow(
                        icon = Icons.Outlined.Schedule,
                        label = stringResource(R.string.ae_time),
                        value = timeFormatter.format(java.util.Date(uiState.triggerTime)),
                        onClick = { showTimePickerDialog = true }
                    )
                    
                    if (uiState.showPastTimeError) {
                         Text(
                             text = stringResource(R.string.ae_time_future),
                             color = MaterialTheme.colorScheme.error,
                             style = MaterialTheme.typography.bodySmall,
                             modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                         )
                    }
                    
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    
                    // Repeat Row
                    var showRecurrenceSheet by remember { mutableStateOf(false) }
                    val recurrenceSummary = if (uiState.recurrenceType == RecurrenceType.NONE) {
                        neverLabel
                    } else {
                        com.ghostgramlabs.speakalert.ui.util.localizedRecurrenceSummary(
                            type = uiState.recurrenceType,
                            json = uiState.recurrenceJson,
                            nextTriggerAt = uiState.triggerTime,
                            includeTime = false,
                            includeEndRule = true
                        )
                    }
                    ScheduleRow(
                        icon = Icons.Filled.Repeat,
                        label = stringResource(R.string.ae_repeat),
                        value = recurrenceSummary,
                        onClick = { showRecurrenceSheet = true }
                    )
                    
                    if (showRecurrenceSheet) {
                        RecurrenceSelectionSheet(
                            initialType = uiState.recurrenceType,
                            initialJson = uiState.recurrenceJson,
                            minEndDateTimeMillis = uiState.triggerTime,
                            onRecurrenceSelected = { model ->
                                viewModel.setRecurrence(model)
                                showRecurrenceSheet = false
                            },
                            onDismiss = { showRecurrenceSheet = false }
                        )
                    }
                    
                    // Loop Toggle
                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .semantics(mergeDescendants = true) {
                                stateDescription = if (uiState.loopPlayback) stateOn else stateOff
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AllInclusive,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (uiState.loopPlayback) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.ae_loop),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (uiState.loopPlayback) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                stringResource(R.string.ae_loop_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.loopPlayback) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Switch(
                            checked = uiState.loopPlayback,
                            onCheckedChange = { viewModel.setLoopPlayback(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    Divider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .semantics(mergeDescendants = true) {
                                stateDescription = if (uiState.followUpCheckMinutes > 0) {
                                    stateActiveLabel
                                } else {
                                    stateOff
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.NotificationsActive,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = if (uiState.followUpCheckMinutes > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.ae_followup),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (uiState.followUpCheckMinutes > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    if (uiState.followUpCheckMinutes == 0) {
                                        stringResource(R.string.ae_followup_desc)
                                    } else {
                                        pluralStringResource(
                                            R.plurals.ae_followup_active,
                                            uiState.followUpCheckMinutes,
                                            uiState.followUpCheckMinutes
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (uiState.followUpCheckMinutes > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (uiState.followUpCheckMinutes > 0) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                                }
                            ) {
                                Text(
                                    text = if (uiState.followUpCheckMinutes > 0) stateActiveLabel else stateOff,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (uiState.followUpCheckMinutes > 0) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                        com.ghostgramlabs.speakalert.ui.components.FollowUpDurationPicker(
                            currentMinutes = uiState.followUpCheckMinutes,
                            onChange = { viewModel.setFollowUpCheckMinutes(it) }
                        )
                    }
                }
            }
            }

            if (showDatePickerDialog) {
                val applyPickedDate: (Long) -> Unit = { pickedDate ->
                    if (addEditIsDateTodayOrFuture(pickedDate)) {
                        val candidate = mergeDateWithCurrentTime(uiState.triggerTime, pickedDate)
                        viewModel.setTriggerTime(candidate)
                        showTimePickerDialog = true
                    } else {
                        Toast.makeText(context, context.getString(R.string.err_date_future), Toast.LENGTH_SHORT).show()
                    }
                    showDatePickerDialog = false
                }
                if (shouldUseSystemDateTimePickers()) {
                    SystemDatePickerDialog(
                        initialSelectedDateMillisUtc = addEditDatePickerSelectionMillis(uiState.triggerTime),
                        onDismiss = { showDatePickerDialog = false },
                        onConfirm = applyPickedDate,
                    )
                } else {
                    val dateState = rememberDatePickerState(
                        initialSelectedDateMillis = addEditDatePickerSelectionMillis(uiState.triggerTime)
                    )
                    DatePickerDialog(
                        onDismissRequest = { showDatePickerDialog = false },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val pickedDate = dateState.selectedDateMillis
                                    if (pickedDate != null) {
                                        applyPickedDate(pickedDate)
                                    } else {
                                        showDatePickerDialog = false
                                    }
                                },
                                enabled = dateState.selectedDateMillis != null
                            ) {
                                Text(stringResource(R.string.action_apply))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDatePickerDialog = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    ) {
                        DatePicker(state = dateState)
                    }
                }
            }

            if (showTimePickerDialog) {
                val cal = remember(uiState.triggerTime) {
                    java.util.Calendar.getInstance().apply { timeInMillis = uiState.triggerTime }
                }
                val applyPickedTime: (Int, Int) -> Unit = { hour, minute ->
                    viewModel.setTriggerTime(
                        mergeTimeWithCurrentDate(
                            currentTime = uiState.triggerTime,
                            hour = hour,
                            minute = minute
                        )
                    )
                    showTimePickerDialog = false
                }
                if (shouldUseSystemDateTimePickers()) {
                    SystemTimePickerDialog(
                        initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                        initialMinute = cal.get(java.util.Calendar.MINUTE),
                        is24Hour = com.ghostgramlabs.speakalert.util.TimeFormat.use24Hour,
                        onDismiss = { showTimePickerDialog = false },
                        onConfirm = applyPickedTime,
                    )
                } else {
                    AppTimePickerDialog(
                        initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                        initialMinute = cal.get(java.util.Calendar.MINUTE),
                        is24Hour = com.ghostgramlabs.speakalert.util.TimeFormat.use24Hour,
                        title = stringResource(R.string.time_picker_title),
                        onDismiss = { showTimePickerDialog = false },
                        onConfirm = { pickedHour, pickedMinute -> applyPickedTime(pickedHour, pickedMinute) }
                    )
                }
            }

            // Save Button
            Spacer(modifier = Modifier.height(8.dp))
            val savingCd = stringResource(R.string.ae_cd_saving)
            Button(
                onClick = { viewModel.saveReminder() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                enabled = !uiState.isSaving && !uiState.saveCompleted,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .semantics { contentDescription = savingCd },
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.ae_save_reminder),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            
            // Bottom spacing for gesture nav
            Spacer(modifier = Modifier.height(80.dp))
        }
        }
    }
}

@Composable
private fun ScheduleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = value
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp).mirrorInRtl()
        )
    }
}

private fun resolveDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull() ?: uri.lastPathSegment
}

private fun mergeDateWithCurrentTime(currentTime: Long, selectedDate: Long): Long {
    val current = java.util.Calendar.getInstance().apply { timeInMillis = currentTime }
    val utcDate = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = selectedDate
    }
    return java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.YEAR, utcDate.get(java.util.Calendar.YEAR))
        set(java.util.Calendar.MONTH, utcDate.get(java.util.Calendar.MONTH))
        set(java.util.Calendar.DAY_OF_MONTH, utcDate.get(java.util.Calendar.DAY_OF_MONTH))
        set(java.util.Calendar.HOUR_OF_DAY, current.get(java.util.Calendar.HOUR_OF_DAY))
        set(java.util.Calendar.MINUTE, current.get(java.util.Calendar.MINUTE))
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun addEditDatePickerSelectionMillis(time: Long): Long {
    val localDate = java.util.Calendar.getInstance().apply {
        timeInMillis = time
    }
    return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(java.util.Calendar.YEAR, localDate.get(java.util.Calendar.YEAR))
        set(java.util.Calendar.MONTH, localDate.get(java.util.Calendar.MONTH))
        set(java.util.Calendar.DAY_OF_MONTH, localDate.get(java.util.Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun addEditIsDateTodayOrFuture(selectedDate: Long): Boolean {
    val pickedLocalStart = java.util.Calendar.getInstance().apply {
        val utcDate = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = selectedDate
        }
        set(java.util.Calendar.YEAR, utcDate.get(java.util.Calendar.YEAR))
        set(java.util.Calendar.MONTH, utcDate.get(java.util.Calendar.MONTH))
        set(java.util.Calendar.DAY_OF_MONTH, utcDate.get(java.util.Calendar.DAY_OF_MONTH))
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    return pickedLocalStart >= startOfTodayMillis()
}

private fun mergeTimeWithCurrentDate(currentTime: Long, hour: Int, minute: Int): Long {
    return java.util.Calendar.getInstance().apply {
        timeInMillis = currentTime
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun startOfTodayMillis(): Long {
    return java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
