package com.ghostgramlabs.speakalert.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.ui.components.AppTimePickerDialog
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.ui.AppViewModelProvider
import com.ghostgramlabs.speakalert.util.APP_DISPLAY_NAME
import com.ghostgramlabs.speakalert.util.BatteryOptimizationSupport
import com.ghostgramlabs.speakalert.util.DateUtils
import com.ghostgramlabs.speakalert.util.ReminderAudioSource
import com.ghostgramlabs.speakalert.util.isDefaultAppDisplayName
import com.ghostgramlabs.speakalert.ui.components.ActionSheetRow
import com.ghostgramlabs.speakalert.ui.components.PremiumHeaderCard
import com.ghostgramlabs.speakalert.ui.components.mirrorInRtl
import com.ghostgramlabs.speakalert.ui.components.PremiumScreenBackground
import com.ghostgramlabs.speakalert.ui.components.ReminderCard
import com.ghostgramlabs.speakalert.ui.components.RecurringCompletionDialog
import com.ghostgramlabs.speakalert.ui.settings.HelpDialog
import com.ghostgramlabs.speakalert.ui.components.SystemDatePickerDialog
import com.ghostgramlabs.speakalert.ui.components.SystemTimePickerDialog
import com.ghostgramlabs.speakalert.ui.components.shouldUseSystemDateTimePickers
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    navigateToItemUpdate: (Long) -> Unit,
    navigateToAddItem: () -> Unit,
    navigateToSettings: () -> Unit,
    allowStartupOverlays: Boolean = true,
    allowNotificationPrompt: Boolean = allowStartupOverlays,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val quietHours by viewModel.quietHours.collectAsState()
    val pausedUntil by viewModel.pausedUntil.collectAsState()
    // Cleared the moment the pause actually ends rather than on a polling tick, so the banner
    // never outlives the state it describes. Sleeping exactly as long as the pause has left also
    // means no timer runs while nothing is pending.
    var clockTick by remember { mutableStateOf(System.currentTimeMillis()) }
    val isPaused = pausedUntil > clockTick
    LaunchedEffect(pausedUntil) {
        clockTick = System.currentTimeMillis()
        val remaining = pausedUntil - clockTick
        if (remaining > 0) {
            delay(remaining)
            clockTick = System.currentTimeMillis()
        }
    }
    var showPauseSheet by remember { mutableStateOf(false) }
    val unnamedTitleStyle = com.ghostgramlabs.speakalert.ui.settings.rememberUnnamedReminderTitleStyle()
    var selectedFilter by remember { mutableStateOf(FilterType.TODAY) }
    var currentPlayingId by remember { mutableStateOf<Long>(-1L) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var fabOffsetX by rememberSaveable { mutableStateOf(0f) }
    var fabOffsetY by rememberSaveable { mutableStateOf(0f) }

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val fabSizePx = with(density) { 72.dp.toPx() }
    val fabMarginPx = with(density) { 16.dp.toPx() }
    val minFabX = -(screenWidthPx - fabSizePx - fabMarginPx * 2).coerceAtLeast(0f)
    val minFabY = -(screenHeightPx - fabSizePx - fabMarginPx * 2).coerceAtLeast(0f)

    val scrollState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Listen for Playback Status Broadcasts
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "ACTION_PLAYBACK_STATUS") {
                    val id = intent.getLongExtra("reminderId", -1L)
                    val isPlaying = intent.getBooleanExtra("isPlaying", false)
                    if (isPlaying) {
                        if (id != -1L) currentPlayingId = id
                    } else {
                        currentPlayingId = -1L
                    }
                }
            }
        }
        val filter = android.content.IntentFilter("ACTION_PLAYBACK_STATUS")
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // Failsafe: if playback notification is gone, clear stale UI playing state.
    LaunchedEffect(context, currentPlayingId) {
        while (currentPlayingId != -1L) {
            delay(1200)
            if (!isPlaybackNotificationActive(context)) {
                currentPlayingId = -1L
            }
        }
    }
    
    // Request notification permission on Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notificationPermissionState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
        
        LaunchedEffect(allowNotificationPrompt) {
            if (allowNotificationPrompt && !notificationPermissionState.status.isGranted) {
                notificationPermissionState.launchPermissionRequest()
            }
        }
    }
    
    val audioBeingImproved by com.ghostgramlabs.speakalert.audio.AudioCleanupTracker.inProgress.collectAsState()

    // Re-read on every resume: the user grants this out in system Settings, so the banner has to
    // be gone the moment they come back rather than waiting for a restart.
    var batteryRestricted by remember {
        mutableStateOf(BatteryOptimizationSupport.isBatteryOptimizationEnabled(context))
    }
    val homeLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(homeLifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryRestricted = BatteryOptimizationSupport.isBatteryOptimizationEnabled(context)
            }
        }
        homeLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { homeLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Restore Reminder Dialog State
    var showRestoreDialog by remember { mutableStateOf(false) }
    var reminderToRestore by remember { mutableStateOf<ReminderEntity?>(null) }
    var showMarkDoneDialog by remember { mutableStateOf(false) }
    var reminderToMarkDone by remember { mutableStateOf<ReminderEntity?>(null) }
    var isRestoringFromUndo by remember { mutableStateOf(false) }
    var showMissedRecoveryDialog by rememberSaveable { mutableStateOf(false) }
    var missedRecoveryHandled by rememberSaveable { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    
    // Recurring Action States
    var reminderToStop by remember { mutableStateOf<ReminderEntity?>(null) }
    var reminderToMarkOccurrence by remember { mutableStateOf<ReminderEntity?>(null) }
    
    // Material 3 date/time picker chain for reschedule/undo
    var showRescheduleDatePicker by remember { mutableStateOf(false) }
    var showRescheduleTimePicker by remember { mutableStateOf(false) }
    var pendingRescheduleTimeMillis by remember { mutableStateOf<Long?>(null) }
    var pendingRestoreTarget by remember { mutableStateOf<ReminderEntity?>(null) }
    var pendingRestoreFromUndo by remember { mutableStateOf(false) }

    val openReschedulePicker = { reminder: ReminderEntity?, fromUndo: Boolean ->
        val now = System.currentTimeMillis()
        pendingRestoreTarget = reminder
        pendingRestoreFromUndo = fromUndo
        pendingRescheduleTimeMillis = reminder?.nextTriggerAt?.takeIf { it > now } ?: now
        showRescheduleDatePicker = true
    }
    
    // Handle Restore Dialog Actions
    if (showRestoreDialog && reminderToRestore != null) {
        val restoreTarget = reminderToRestore
        com.ghostgramlabs.speakalert.ui.components.RestoreReminderDialog(
            onDismiss = {
                showRestoreDialog = false
                reminderToRestore = null
            },
            onReschedule = {
                showRestoreDialog = false
                openReschedulePicker(restoreTarget, isRestoringFromUndo)
            },
            onMoveToMissed = {
                restoreTarget?.let { viewModel.moveToMissed(it) }
                reminderToRestore = null
                isRestoringFromUndo = false
            },
            onPlay = {
                restoreTarget?.let { viewModel.playReminder(context, it) }
                reminderToRestore = null
                isRestoringFromUndo = false
            },
            onKeepAsDone = {
                // Do nothing - reminder stays completed
                reminderToRestore = null
                isRestoringFromUndo = false
            }
        )
    }

    if (showRescheduleDatePicker && pendingRescheduleTimeMillis != null) {
        val cancelDateFlow = {
            showRescheduleDatePicker = false
            pendingRescheduleTimeMillis = null
            pendingRestoreTarget = null
            pendingRestoreFromUndo = false
        }
        val applyPickedDate: (Long) -> Unit = { selectedDate ->
            if (homeIsDateTodayOrFuture(selectedDate)) {
                pendingRescheduleTimeMillis = homeMergeDateWithCurrentTime(
                    pendingRescheduleTimeMillis ?: System.currentTimeMillis(),
                    selectedDate
                )
                showRescheduleDatePicker = false
                showRescheduleTimePicker = true
            } else {
                Toast.makeText(context, context.getString(R.string.err_date_future), Toast.LENGTH_SHORT).show()
            }
        }
        if (shouldUseSystemDateTimePickers()) {
            SystemDatePickerDialog(
                initialSelectedDateMillisUtc = homeUtcStartOfTodayMillis(
                    pendingRescheduleTimeMillis ?: System.currentTimeMillis()
                ),
                onDismiss = cancelDateFlow,
                onConfirm = applyPickedDate,
            )
        } else {
            val dateState = rememberDatePickerState(
                initialSelectedDateMillis = homeUtcStartOfTodayMillis(
                    pendingRescheduleTimeMillis ?: System.currentTimeMillis()
                )
            )
            DatePickerDialog(
                onDismissRequest = cancelDateFlow,
                confirmButton = {
                    TextButton(
                        onClick = {
                            val selectedDate = dateState.selectedDateMillis
                            if (selectedDate != null) {
                                applyPickedDate(selectedDate)
                            }
                        },
                        enabled = dateState.selectedDateMillis != null
                    ) {
                        Text(stringResource(R.string.action_apply))
                    }
                },
                dismissButton = {
                    TextButton(onClick = cancelDateFlow) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            ) {
                DatePicker(state = dateState)
            }
        }
    }

    if (showRescheduleTimePicker && pendingRescheduleTimeMillis != null) {
        val current = remember(pendingRescheduleTimeMillis) {
            java.util.Calendar.getInstance().apply {
                timeInMillis = pendingRescheduleTimeMillis ?: System.currentTimeMillis()
            }
        }
        val cancelTimeFlow = {
            showRescheduleTimePicker = false
            pendingRescheduleTimeMillis = null
            pendingRestoreTarget = null
            pendingRestoreFromUndo = false
        }
        val applyPickedTime: (Int, Int) -> Unit = { hour, minute ->
            val selectedTime = homeMergeTimeWithCurrentDate(
                pendingRescheduleTimeMillis ?: System.currentTimeMillis(),
                hour,
                minute
            )
            if (selectedTime <= System.currentTimeMillis()) {
                Toast.makeText(context, context.getString(R.string.err_time_future), Toast.LENGTH_SHORT).show()
            } else {
                if (pendingRestoreFromUndo) {
                    viewModel.undoDelete(selectedTime)
                } else {
                    pendingRestoreTarget?.let { viewModel.restoreReminder(it, selectedTime) }
                }
                showRescheduleTimePicker = false
                pendingRescheduleTimeMillis = null
                pendingRestoreTarget = null
                pendingRestoreFromUndo = false
                showRestoreDialog = false
                reminderToRestore = null
                isRestoringFromUndo = false
            }
        }
        if (shouldUseSystemDateTimePickers()) {
            SystemTimePickerDialog(
                initialHour = current.get(java.util.Calendar.HOUR_OF_DAY),
                initialMinute = current.get(java.util.Calendar.MINUTE),
                is24Hour = com.ghostgramlabs.speakalert.util.TimeFormat.use24Hour,
                onDismiss = cancelTimeFlow,
                onConfirm = applyPickedTime,
            )
        } else {
            AppTimePickerDialog(
                initialHour = current.get(java.util.Calendar.HOUR_OF_DAY),
                initialMinute = current.get(java.util.Calendar.MINUTE),
                is24Hour = com.ghostgramlabs.speakalert.util.TimeFormat.use24Hour,
                title = stringResource(R.string.time_picker_title),
                onDismiss = cancelTimeFlow,
                onConfirm = { pickedHour, pickedMinute -> applyPickedTime(pickedHour, pickedMinute) }
            )
        }
    }

    LaunchedEffect(uiState.missedReminders, allowStartupOverlays) {
        if (!allowStartupOverlays) {
            showMissedRecoveryDialog = false
            return@LaunchedEffect
        }
        if (uiState.missedReminders.isEmpty()) {
            showMissedRecoveryDialog = false
            missedRecoveryHandled = false
        } else if (!missedRecoveryHandled) {
            showMissedRecoveryDialog = true
            missedRecoveryHandled = true
        }
    }

    if (showMissedRecoveryDialog && uiState.missedReminders.isNotEmpty()) {
        MissedReminderRecoveryDialog(
            missedReminders = uiState.missedReminders,
            onDismiss = { showMissedRecoveryDialog = false },
            onPlayNow = {
                uiState.missedReminders.firstOrNull()?.let { missed ->
                    viewModel.fireMissedReminder(context, missed)
                }
                showMissedRecoveryDialog = false
            }
        )
    }

    // Stop Recurring Confirmation Dialog
    if (reminderToStop != null) {
        ModalBottomSheet(
            onDismissRequest = { reminderToStop = null },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_stop_recurring_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.home_stop_recurring_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ActionSheetRow(
                    icon = Icons.Filled.Stop,
                    label = stringResource(R.string.home_stop_recurring_action),
                    subLabel = stringResource(R.string.home_stop_recurring_sub),
                    onClick = {
                        reminderToStop?.let { viewModel.deleteReminder(it) }
                        reminderToStop = null
                    },
                    isDestructive = true
                )
                OutlinedButton(
                    onClick = { reminderToStop = null },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(stringResource(R.string.home_keep_reminder))
                }
            }
        }
    }

    // Mark Occurrence Done Confirmation Dialog
    if (reminderToMarkOccurrence != null) {
        ModalBottomSheet(
            onDismissRequest = { reminderToMarkOccurrence = null },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_mark_occurrence_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.home_mark_occurrence_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ActionSheetRow(
                    icon = Icons.Filled.Done,
                    label = stringResource(R.string.home_mark_occurrence_action),
                    subLabel = stringResource(R.string.home_mark_occurrence_sub),
                    onClick = {
                        reminderToMarkOccurrence?.let { viewModel.markTodayAsDone(it) }
                        reminderToMarkOccurrence = null
                    },
                    emphasize = true
                )
                OutlinedButton(
                    onClick = { reminderToMarkOccurrence = null },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }

    // Mark as Done Confirmation Dialog
    if (showMarkDoneDialog && reminderToMarkDone != null) {
        ModalBottomSheet(
            onDismissRequest = {
                showMarkDoneDialog = false
                reminderToMarkDone = null
            },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_mark_done_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.home_mark_done_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ActionSheetRow(
                    icon = Icons.Filled.Done,
                    label = stringResource(R.string.home_move_to_done),
                    subLabel = stringResource(R.string.home_move_to_done_sub),
                    onClick = {
                        reminderToMarkDone?.let { viewModel.completeReminder(it) }
                        showMarkDoneDialog = false
                        reminderToMarkDone = null
                    },
                    emphasize = true
                )
                OutlinedButton(
                    onClick = {
                        showMarkDoneDialog = false
                        reminderToMarkDone = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
    
    
    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false }, onOpenSettings = navigateToSettings)
    }

    if (showPauseSheet) {
        PauseRemindersSheet(
            quietHours = quietHours,
            onDismiss = { showPauseSheet = false },
            onPause = { until ->
                showPauseSheet = false
                viewModel.setPausedUntil(until)
            }
        )
    }

    val filters = listOf(
        FilterType.UPCOMING to stringResource(R.string.home_filter_upcoming),
        FilterType.TODAY to stringResource(R.string.home_filter_today),
        FilterType.MISSED to stringResource(R.string.home_filter_missed),
        FilterType.COMPLETED to stringResource(R.string.home_filter_done)
    )

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        APP_DISPLAY_NAME,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    // Missed reminders badge
                    if (uiState.missedReminders.isNotEmpty()) {
                        Box(
                            modifier = Modifier.padding(end = 4.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            IconButton(onClick = { selectedFilter = FilterType.MISSED }) {
                                Icon(
                                    Icons.Outlined.Notifications,
                                    contentDescription = stringResource(R.string.home_cd_missed),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(top = 6.dp, end = 4.dp)
                                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            ) {
                                Box(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (uiState.missedReminders.size > 99) "99+" else "${uiState.missedReminders.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    // Only offered while reminders are running: once paused, the banner below
                    // owns the state and carries Resume, so there is one place to look.
                    if (!isPaused) {
                        IconButton(onClick = { showPauseSheet = true }) {
                            Icon(
                                Icons.Filled.PauseCircleOutline,
                                contentDescription = stringResource(R.string.home_pause_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(
                            Icons.Filled.HelpOutline,
                            contentDescription = stringResource(R.string.home_cd_help),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = navigateToSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.home_cd_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = navigateToAddItem,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(72.dp)
                        .offset { IntOffset(fabOffsetX.roundToInt(), fabOffsetY.roundToInt()) }
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                fabOffsetX = (fabOffsetX + dragAmount.x).coerceIn(minFabX, 0f)
                                fabOffsetY = (fabOffsetY + dragAmount.y).coerceIn(minFabY, 0f)
                            }
                        }
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.home_cd_add),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { innerPadding ->
        PremiumScreenBackground(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PremiumHeaderCard(
                    title = getGreeting(),
                    subtitle = getSubtitle(uiState),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 14.dp)
                        .semantics(mergeDescendants = true) {}
                )

                // A startup sheet asked once and was gone, so the setting that decides whether
                // reminders arrive at all could be lost to a single stray tap. This states the
                // problem where it cannot be missed and removes itself the moment the phone
                // stops restricting the app - so it is never a nag, only an unresolved fault.
                if (batteryRestricted) {
                    BatteryRestrictedBanner(
                        onClick = {
                            if (!BatteryOptimizationSupport.requestIgnoreBatteryOptimizations(context)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.batt_toast_unavailable),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 14.dp)
                    )
                }

                // Quiet hours divert reminders to Missed without a sound, which is exactly what a
                // broken reminder app looks like from the outside. Saying so here, and naming the
                // window, turns a silent evening back into something the user recognises as their
                // own setting. Tapping goes to the setting that controls it.
                if (isPaused) {
                    PausedBanner(
                        until = pausedUntil,
                        onResume = { viewModel.setPausedUntil(0L) },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 14.dp)
                    )
                }

                quietHours?.let { window ->
                    QuietHoursBanner(
                        window = window,
                        onClick = navigateToSettings,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 14.dp)
                    )
                }


                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .fillMaxWidth()
                ) {
                    items(filters) { (type, label) ->
                        val isSelected = selectedFilter == type
                        val hasMissedReminders = type == FilterType.MISSED && uiState.missedReminders.isNotEmpty()

                        Surface(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                            },
                            contentColor = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
                                }
                            ),
                            modifier = Modifier
                                .height(46.dp)
                                .clickable { selectedFilter = type }
                                .animateContentSize()
                                .semantics {
                                    role = androidx.compose.ui.semantics.Role.Tab
                                    selected = isSelected
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                )

                                if (hasMissedReminders) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                }
                            }
                        }
                    }
                }

                val missedList = uiState.missedReminders
            
                if (selectedFilter == FilterType.MISSED) {
                    if (missedList.isEmpty()) {
                        EmptyState(
                            icon = Icons.Filled.NotificationsOff,
                            title = stringResource(R.string.home_empty_all_caught_up),
                            subtitle = stringResource(R.string.home_empty_no_missed)
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            OutlinedButton(
                                onClick = {
                                    missedList.forEach { viewModel.dismissMissedReminder(it) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.home_dismiss_all), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        
                        MissedReminderList(
                            missedReminders = missedList,
                            currentPlayingId = currentPlayingId,
                            onFireClick = {
                                currentPlayingId = it.reminderId
                                viewModel.fireMissedReminder(context, it)
                            },
                            onStopClick = {
                                currentPlayingId = -1L
                                com.ghostgramlabs.speakalert.service.ReminderPlaybackService.stop(context)
                            },
                            onDismissClick = { viewModel.dismissMissedReminder(it) },
                            onEditClick = { navigateToItemUpdate(it.reminderId) }
                        )
                    }
                } else {
                    val list = when (selectedFilter) {
                        FilterType.TODAY -> uiState.todayReminders
                        FilterType.UPCOMING -> uiState.upcomingReminders
                        FilterType.COMPLETED -> uiState.completedReminders
                        FilterType.ALL -> (uiState.todayReminders + uiState.upcomingReminders + uiState.completedReminders).sortedBy { it.nextTriggerAt }
                        else -> emptyList()
                    }

                    if (list.isEmpty()) {
                        EmptyState(
                            icon = when (selectedFilter) {
                                 FilterType.TODAY -> Icons.Filled.Schedule
                                 FilterType.COMPLETED -> Icons.Filled.Done
                                 else -> Icons.Filled.Mic
                            },
                            title = when (selectedFilter) {
                                FilterType.COMPLETED -> stringResource(R.string.home_empty_nothing_done)
                                FilterType.UPCOMING -> stringResource(R.string.home_empty_no_upcoming)
                                else -> stringResource(R.string.home_empty_no_reminders)
                            },
                            subtitle = when (selectedFilter) {
                                FilterType.COMPLETED -> stringResource(R.string.home_empty_done_sub)
                                FilterType.UPCOMING -> stringResource(R.string.home_empty_upcoming_sub)
                                else -> stringResource(R.string.home_empty_default_sub)
                            }
                        )
                } else {
                    LazyColumn(
                        state = scrollState,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 100.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(list, key = { it.id }) { reminder ->
                            val summary = if (reminder.recurrenceType == com.ghostgramlabs.speakalert.domain.models.RecurrenceType.NONE) {
                                null
                            } else {
                                com.ghostgramlabs.speakalert.ui.util.localizedRecurrenceSummary(
                                    type = reminder.recurrenceType,
                                    json = reminder.recurrenceJson,
                                    nextTriggerAt = reminder.nextTriggerAt,
                                    includeEndRule = true
                                )
                            }
                            // Smart fallback label
                            val timeOnly = DateUtils.formatTimeOnly(reminder.nextTriggerAt)
                            val displayTitle = com.ghostgramlabs.speakalert.ui.settings.reminderTitle(reminder, unnamedTitleStyle)
                            
                            // Context-aware Date Label: Hide "Today" if in Today tab
                            val isTodayTab = selectedFilter == FilterType.TODAY
                            val isTodayDate = DateUtils.isToday(reminder.nextTriggerAt)
                            val rawDateLabel = com.ghostgramlabs.speakalert.ui.util.localizedDateLabel(reminder.nextTriggerAt)
                            val finalDateLabel = if (isTodayTab && isTodayDate) "" else rawDateLabel
                            
                            ReminderCard(
                                title = displayTitle,
                                badgeTime = timeOnly,
                                dateLabel = finalDateLabel,
                                recurrenceSummary = summary,
                                recurrenceType = reminder.recurrenceType,
                                recurrenceJson = reminder.recurrenceJson,
                                hasAudio = !reminder.audioPath.isNullOrBlank(),
                                hasText = !reminder.reminderText.isNullOrBlank(),
                                isTextToSpeechEnabled = uiState.isTextToSpeechEnabled,
                                hasCustomAudioFile = ReminderAudioSource.isContentUri(reminder.audioPath),
                                isImprovingAudio = reminder.audioPath in audioBeingImproved,
                                isPlaying = currentPlayingId == reminder.id,
                                isCompleted = reminder.isCompleted,
                                loopEnabled = reminder.loopPlayback,
                                followUpCheckMinutes = reminder.followUpCheckMinutes,
                                onPlayClick = { 
                                    currentPlayingId = reminder.id
                                    viewModel.playReminder(context, reminder) 
                                },
                                onStopClick = {
                                    if (currentPlayingId == reminder.id) currentPlayingId = -1L
                                    com.ghostgramlabs.speakalert.service.ReminderPlaybackService.stop(context)
                                },
                                onClick = { navigateToItemUpdate(reminder.id) },
                                onEditClick = { navigateToItemUpdate(reminder.id) },
                                onDeleteClick = { 
                                    if (reminder.recurrenceType != com.ghostgramlabs.speakalert.domain.models.RecurrenceType.NONE) {
                                        reminderToStop = reminder
                                    } else {
                                        viewModel.deleteReminder(reminder)
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = context.getString(R.string.home_snackbar_deleted),
                                                actionLabel = context.getString(R.string.action_undo),
                                                duration = SnackbarDuration.Short
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                val preview = viewModel.previewUndo()
                                                val now = System.currentTimeMillis()
                                                if (preview != null && preview.nextTriggerAt < now) {
                                                    // Time is past, force picker
                                                    reminderToRestore = preview
                                                    isRestoringFromUndo = true
                                                    openReschedulePicker(preview, true)
                                                } else {
                                                    viewModel.undoDelete()
                                                }
                                            }
                                        }
                                    }
                                },
                                onCompleteClick = {
                                    if (reminder.recurrenceType != com.ghostgramlabs.speakalert.domain.models.RecurrenceType.NONE) {
                                        // Recurring: Show confirmation to mark THIS occurrence done
                                        reminderToMarkOccurrence = reminder
                                    } else {
                                        // One-time: Complete immediately
                                        viewModel.completeReminder(reminder)
                                    }
                                },
                                onDuplicateClick = {
                                    scope.launch {
                                        val newId = viewModel.duplicateReminder(reminder.id)
                                        if (newId != null) {
                                            val result = snackbarHostState.showSnackbar(
                                                message = context.getString(R.string.home_snackbar_duplicated),
                                                actionLabel = context.getString(R.string.action_open),
                                                duration = SnackbarDuration.Short
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                navigateToItemUpdate(newId)
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun getGreeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> stringResource(R.string.home_greeting_morning)
        hour < 17 -> stringResource(R.string.home_greeting_afternoon)
        else -> stringResource(R.string.home_greeting_evening)
    }
}

@Composable
private fun getSubtitle(uiState: HomeUiState): String {
    val activeCount = uiState.upcomingReminders.size + uiState.todayReminders.size
    return if (activeCount == 0) {
        stringResource(R.string.home_all_caught_up)
    } else {
        pluralStringResource(R.plurals.home_active_reminders, activeCount, activeCount)
    }
}

@Composable
fun MissedReminderList(
    missedReminders: List<com.ghostgramlabs.speakalert.data.model.MissedReminderEntity>,
    currentPlayingId: Long,
    onFireClick: (com.ghostgramlabs.speakalert.data.model.MissedReminderEntity) -> Unit,
    onStopClick: () -> Unit,
    onDismissClick: (com.ghostgramlabs.speakalert.data.model.MissedReminderEntity) -> Unit,
    onEditClick: (com.ghostgramlabs.speakalert.data.model.MissedReminderEntity) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(missedReminders, key = { it.id }) { missed ->
            MissedReminderItem(
                missed = missed,
                isPlaying = currentPlayingId == missed.reminderId,
                onFireClick = { onFireClick(missed) },
                onStopClick = onStopClick,
                onDismissClick = { onDismissClick(missed) },
                onEditClick = { onEditClick(missed) }
            )
        }
    }
}

@Composable
fun MissedReminderItem(
    missed: com.ghostgramlabs.speakalert.data.model.MissedReminderEntity,
    isPlaying: Boolean,
    onFireClick: () -> Unit,
    onStopClick: () -> Unit,
    onDismissClick: () -> Unit,
    onEditClick: () -> Unit
) {
    val reminderFallback = stringResource(R.string.common_reminder)
    val displayTitle = remember(missed.title, missed.reminderText, reminderFallback) {
        val userTitle = missed.title
            .trim()
            .takeIf { it.isNotEmpty() && !it.isDefaultAppDisplayName() }
        if (userTitle != null) {
            userTitle
        } else {
            val textFallback = com.ghostgramlabs.speakalert.transit.BusAlert.display(missed.reminderText)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { text ->
                    val words = text.split(Regex("\\s+"))
                    if (words.size > 8) words.take(8).joinToString(" ") else text
                }
            textFallback ?: reminderFallback
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isPlaying) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                    ) {
                        Text(
                            text = stringResource(R.string.home_badge_missed),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    if (isPlaying) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.86f)
                        ) {
                            Text(
                                text = stringResource(R.string.alert_playing_now),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    
                    // Show text body if available and not identical to title
                    if (!missed.reminderText.isNullOrBlank() && !displayTitle.equals(missed.reminderText, ignoreCase = true)) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = com.ghostgramlabs.speakalert.transit.BusAlert.display(missed.reminderText).orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    
                    Text(
                        text = DateUtils.formatDateTime(missed.scheduledTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // A missed reminder is often one the user still wants, just at another time.
                IconButton(onClick = onEditClick) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.ae_header_edit),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(14.dp))
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onDismissClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
                    )
                ) {
                    Text(stringResource(R.string.action_dismiss))
                }
                Button(
                    onClick = if (isPlaying) onStopClick else onFireClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isPlaying) stringResource(R.string.action_stop) else stringResource(R.string.action_play_now))
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun homeMergeDateWithCurrentTime(currentTime: Long, selectedDateMillis: Long): Long {
    val current = java.util.Calendar.getInstance().apply { timeInMillis = currentTime }
    val utcDate = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = selectedDateMillis
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

private fun homeMergeTimeWithCurrentDate(currentTime: Long, hour: Int, minute: Int): Long {
    return java.util.Calendar.getInstance().apply {
        timeInMillis = currentTime
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun homeUtcStartOfTodayMillis(time: Long = System.currentTimeMillis()): Long {
    return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = time
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun homeIsDateTodayOrFuture(selectedDateMillis: Long): Boolean {
    return selectedDateMillis >= homeUtcStartOfTodayMillis()
}

private fun homeStartOfDayMillis(time: Long = System.currentTimeMillis()): Long {
    return java.util.Calendar.getInstance().apply {
        timeInMillis = time
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}

enum class FilterType {
    TODAY, UPCOMING, COMPLETED, MISSED, ALL
}

private fun isPlaybackNotificationActive(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return notificationManager.activeNotifications.any {
        it.id == com.ghostgramlabs.speakalert.service.ReminderPlaybackService.NOTIFICATION_ID
    }
}

/**
 * Standing notice that this phone is allowed to stop the app in the background.
 *
 * Deliberately not dismissible: it is not an announcement but a report of a fault that stops
 * reminders arriving, and it disappears on its own the moment the fault is fixed. Styled as a
 * warning rather than as a promotion so it reads as something to resolve, not something to sell.
 */
@Composable
private fun BatteryRestrictedBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.BatteryAlert,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_batt_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_batt_banner_body, APP_DISPLAY_NAME),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp).mirrorInRtl()
            )
        }
    }
}

/**
 * Standing note that a quiet-hours window is in force.
 *
 * Informational rather than alarming - this is a setting working as asked, not a fault - so it
 * uses the neutral surface rather than the error colour the battery warning claims.
 */
@Composable
private fun QuietHoursBanner(
    window: QuietHoursWindow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val start = remember(window) { formatWallClock(window.startHour, window.startMinute) }
    val end = remember(window) { formatWallClock(window.endHour, window.endMinute) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.home_quiet_banner, start, end),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp).mirrorInRtl()
            )
        }
    }
}

/** Renders an hour/minute in the user's chosen 12- or 24-hour form. */
private fun formatWallClock(hour: Int, minute: Int): String {
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
    }
    val pattern = com.ghostgramlabs.speakalert.util.TimeFormat.timePattern
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(calendar.time)
}

/**
 * Standing note that the user has put reminders on hold, with the way out beside it.
 *
 * Carries the accent colour rather than the error red of the battery warning or the grey of quiet
 * hours: this is neither a fault nor a background schedule, it is a deliberate choice that is
 * still in force, and the end time is stated so it is never a mystery why nothing is firing.
 */
@Composable
private fun PausedBanner(
    until: Long,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    val endLabel = remember(until) { DateUtils.formatSmartDate(until) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.PauseCircleOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.home_pause_banner, endLabel),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onResume) {
                Text(stringResource(R.string.home_pause_resume))
            }
        }
    }
}

/**
 * How long to hold reminders for.
 *
 * Presets rather than two pickers: the cases people actually have are "this meeting" and "the rest
 * of today". Every option carries an end, because a pause with no end is just a way to turn the
 * app off and forget you did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseRemindersSheet(
    quietHours: QuietHoursWindow?,
    onDismiss: () -> Unit,
    onPause: (Long) -> Unit
) {
    var showEndTimePicker by remember { mutableStateOf(false) }

    if (showEndTimePicker) {
        val now = remember { java.util.Calendar.getInstance() }
        SystemTimePickerDialog(
            initialHour = now.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = now.get(java.util.Calendar.MINUTE),
            is24Hour = com.ghostgramlabs.speakalert.util.TimeFormat.use24Hour,
            onDismiss = { showEndTimePicker = false },
            onConfirm = { hour, minute ->
                showEndTimePicker = false
                val end = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                    set(java.util.Calendar.MINUTE, minute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    // A time that has already passed today means tomorrow.
                    if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
                onPause(end.timeInMillis)
            }
        )
        return
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.home_pause_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(R.string.home_pause_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Said here rather than discovered at 10pm, when a second silence would otherwise
            // look like the pause failing to end.
            quietHours?.let { window ->
                Text(
                    text = stringResource(
                        R.string.home_pause_also_quiet,
                        formatWallClock(window.startHour, window.startMinute),
                        formatWallClock(window.endHour, window.endMinute)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            PauseChoice(stringResource(R.string.home_pause_1h)) {
                onPause(System.currentTimeMillis() + 60 * 60_000L)
            }
            PauseChoice(stringResource(R.string.home_pause_4h)) {
                onPause(System.currentTimeMillis() + 4 * 60 * 60_000L)
            }
            PauseChoice(stringResource(R.string.home_pause_today)) {
                val endOfDay = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                onPause(endOfDay.timeInMillis)
            }
            PauseChoice(stringResource(R.string.home_pause_pick)) { showEndTimePicker = true }
        }
    }
}

@Composable
private fun PauseChoice(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
