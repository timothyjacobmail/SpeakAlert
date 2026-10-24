package com.ghostgramlabs.speakalert.ui.settings

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.media.RingtoneManager
import android.provider.Settings
import android.widget.Toast
import com.ghostgramlabs.speakalert.util.launchOrToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.ui.components.AppTimePickerDialog
import com.ghostgramlabs.speakalert.ui.components.mirrorInRtl
import com.ghostgramlabs.speakalert.openPlayStoreListing
import com.ghostgramlabs.speakalert.util.openSupportEmail
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.app.NotificationManagerCompat
import com.ghostgramlabs.speakalert.alarm.NotificationHelper
import com.ghostgramlabs.speakalert.util.APP_DISPLAY_NAME
import com.ghostgramlabs.speakalert.util.BatteryOptimizationSupport
import com.ghostgramlabs.speakalert.util.FullScreenIntentSupport
import com.ghostgramlabs.speakalert.util.WearOsConnectionInfo
import com.ghostgramlabs.speakalert.util.WearOsSupport
import com.ghostgramlabs.speakalert.util.normalizeLocalizedDigitsOrNull
import com.ghostgramlabs.speakalert.util.toLocalizedIntOrNull
import com.ghostgramlabs.speakalert.ui.components.PremiumScreenBackground
import com.ghostgramlabs.speakalert.ui.components.SystemTimePickerDialog
import com.ghostgramlabs.speakalert.ui.components.shouldUseSystemDateTimePickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateUp: () -> Unit,
    onOpenBatteryOptimizationGuide: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()
    var fullScreenAccessGranted by remember { mutableStateOf(FullScreenIntentSupport.canUseFullScreenIntent(context)) }
    var dndPolicyAccessGranted by remember { mutableStateOf(readDndPolicyAccessGranted(context)) }
    var batteryOptimizationEnabled by remember {
        mutableStateOf(BatteryOptimizationSupport.isBatteryOptimizationEnabled(context))
    }
    var wearConnectionInfo by remember { mutableStateOf(WearOsConnectionInfo(isConnected = false)) }
    var isCheckingWearStatus by remember { mutableStateOf(true) }
    var wearRefreshTick by remember { mutableIntStateOf(0) }
    var appNotificationStatus by remember { mutableStateOf(readAppNotificationStatus(context)) }

    LaunchedEffect(context, wearRefreshTick) {
        isCheckingWearStatus = true
        appNotificationStatus = readAppNotificationStatus(context)
        dndPolicyAccessGranted = readDndPolicyAccessGranted(context)
        wearConnectionInfo = withContext(Dispatchers.IO) {
            WearOsSupport.getConnectionInfo(context)
        }
        isCheckingWearStatus = false
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    fullScreenAccessGranted = FullScreenIntentSupport.canUseFullScreenIntent(context)
                    dndPolicyAccessGranted = readDndPolicyAccessGranted(context)
                    batteryOptimizationEnabled = BatteryOptimizationSupport.isBatteryOptimizationEnabled(context)
                wearRefreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    val use24HourTime by viewModel.use24HourTime.collectAsState()
    val autoPlayEnabled by viewModel.autoPlayEnabled.collectAsState()
    val autoPlayOnUnlockOnly by viewModel.autoPlayOnUnlockOnly.collectAsState()
    val defaultSnoozeDuration by viewModel.defaultSnoozeDuration.collectAsState()
    val defaultFollowUpMinutes by viewModel.defaultFollowUpMinutes.collectAsState()
    val loopTimeoutMinutes by viewModel.loopTimeoutMinutes.collectAsState()
    
    // Quiet Time state
    val quietTimeEnabled by viewModel.quietTimeEnabled.collectAsState()
    val startHour by viewModel.quietTimeStartHour.collectAsState()
    val startMinute by viewModel.quietTimeStartMinute.collectAsState()
    val endHour by viewModel.quietTimeEndHour.collectAsState()
    val endMinute by viewModel.quietTimeEndMinute.collectAsState()

    val speakTextIfNoVoice by viewModel.speakTextIfNoVoice.collectAsState()
    val privatePlaybackEnabled by viewModel.privatePlaybackEnabled.collectAsState()
    val dndBypassEnabled by viewModel.dndBypassEnabled.collectAsState()
    val toneOnlyMode by viewModel.toneOnlyMode.collectAsState()
    val toneOnlyAlertToneUri by viewModel.toneOnlyAlertToneUri.collectAsState()
    val fullScreenAlertEnabled by viewModel.fullScreenAlertEnabled.collectAsState()
    val debugLoggingEnabled by viewModel.debugLoggingEnabled.collectAsState()
    val appVolume by viewModel.appVolume.collectAsState()
    val showVoiceRecordingSection by viewModel.showVoiceRecordingSection.collectAsState()
    val experimentalVoiceEnhancementEnabled by
        viewModel.experimentalVoiceEnhancementEnabled.collectAsState()
    val showAudioFileSection by viewModel.showAudioFileSection.collectAsState()
    val showTypedReminderSection by viewModel.showTypedReminderSection.collectAsState()
    val showShortLabelSection by viewModel.showShortLabelSection.collectAsState()
    val toneAutoStopLabel = if (loopTimeoutMinutes == 0) stringResource(R.string.set_infinite) else stringResource(R.string.set_minutes_short, loopTimeoutMinutes)
    val toneOnlySubtitle = stringResource(R.string.set_tone_only_desc, toneAutoStopLabel, defaultSnoozeDuration)
    val tonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pickedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        viewModel.setToneOnlyAlertToneUri(pickedUri?.toString())
    }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setPrivatePlaybackEnabled(true)
        if (!granted) {
            // Worth a toast: without the permission, routing to Bluetooth is limited and
            // nothing on screen would explain that.
            Toast.makeText(context, context.getString(R.string.set_private_bt), Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_cd_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier.mirrorInRtl()
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                )
            )
        }
    ) { paddingValues ->
        PremiumScreenBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.shake_title), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        context.startActivity(Intent(context, com.ghostgramlabs.speakalert.shake.ShakeRecordingsActivity::class.java))
                    }) {
                        Text(stringResource(R.string.shake_open))
                    }
                }
            }
            // ============================================================
            // SECTION 0: APPEARANCE
            // ============================================================
            val themeMode by viewModel.themeMode.collectAsState()
            CollapsibleSettingsSection(
                title = stringResource(R.string.set_appearance),
                icon = "UI",
                initiallyExpanded = true
            ) {
                Text(stringResource(R.string.set_app_theme), style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val themes = listOf(
                        stringResource(R.string.theme_system),
                        stringResource(R.string.theme_light),
                        stringResource(R.string.theme_dark)
                    )
                    themes.forEachIndexed { index, name ->
                        SnoozeOptionChip(
                            text = name,
                            isSelected = themeMode == index,
                            onClick = {
                                viewModel.setThemeMode(index)
                                // Mirror synchronously so the next cold start picks the right
                                // window background before DataStore loads.
                                com.ghostgramlabs.speakalert.util.ThemePrefs.cache(context, index)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.settings_language_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val currentLangTag = com.ghostgramlabs.speakalert.util.AppLocale.currentTag(context)
                // FlowRow so every language stays visible (wraps instead of scrolling offscreen).
                // Chips size to their label, so wide scripts (हिन्दी, العربية) never clip, and
                // the flow direction follows the layout direction for RTL locales.
                FlowRow(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    com.ghostgramlabs.speakalert.util.AppLocale.supported.forEach { (tag, label) ->
                        LanguageChip(
                            // Language names stay in their own script; only the "follow the
                            // system" option localizes with the UI language.
                            text = if (tag.isEmpty()) stringResource(R.string.settings_language_system) else label,
                            isSelected = currentLangTag == tag,
                            onClick = {
                                com.ghostgramlabs.speakalert.util.AppLocale
                                    .activityFrom(context)?.let { activity ->
                                        com.ghostgramlabs.speakalert.util.AppLocale.set(activity, tag)
                                    }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                SwitchRow(
                    text = stringResource(R.string.settings_24_hour_time),
                    description = stringResource(R.string.settings_24_hour_time_desc),
                    checked = use24HourTime,
                    onCheckedChange = viewModel::setUse24HourTime
                )
                Spacer(Modifier.height(16.dp))
                UnnamedReminderTitleSetting()
            }

            CollapsibleSettingsSection(
                title = stringResource(R.string.set_reminder_form),
                icon = "Form",
                initiallyExpanded = true
            ) {
                Text(
                    text = stringResource(R.string.set_reminder_form_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    text = stringResource(R.string.set_show_voice_recording),
                    description = stringResource(R.string.set_show_voice_recording_desc),
                    checked = showVoiceRecordingSection,
                    onCheckedChange = viewModel::setShowVoiceRecordingSection
                )
                if (showVoiceRecordingSection) {
                    SwitchRow(
                        text = stringResource(R.string.set_experimental_voice_enhancement),
                        description = stringResource(R.string.set_experimental_voice_enhancement_desc),
                        checked = experimentalVoiceEnhancementEnabled,
                        onCheckedChange = viewModel::setExperimentalVoiceEnhancementEnabled
                    )
                    if (experimentalVoiceEnhancementEnabled) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Science,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.set_experimental_voice_enhancement_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
                SwitchRow(
                    text = stringResource(R.string.set_show_audio_file),
                    description = stringResource(R.string.set_show_audio_file_desc),
                    checked = showAudioFileSection,
                    onCheckedChange = viewModel::setShowAudioFileSection
                )
                SwitchRow(
                    text = stringResource(R.string.set_show_typed_reminder),
                    description = stringResource(R.string.set_show_typed_reminder_desc),
                    checked = showTypedReminderSection,
                    onCheckedChange = viewModel::setShowTypedReminderSection
                )
                SwitchRow(
                    text = stringResource(R.string.set_show_short_label),
                    description = stringResource(R.string.set_show_short_label_desc),
                    checked = showShortLabelSection,
                    onCheckedChange = viewModel::setShowShortLabelSection
                )
            }

            // ============================================================
            // SECTION 1: PLAYBACK (includes Test Reminder at bottom)
            // ============================================================
            CollapsibleSettingsSection(
                title = stringResource(R.string.set_playback),
                icon = "Play",
                initiallyExpanded = true
            ) {
                SwitchRow(
                    text = stringResource(R.string.set_autoplay),
                    description = if (toneOnlyMode) {
                        stringResource(R.string.set_disabled_tone)
                    } else {
                        stringResource(R.string.set_autoplay_desc)
                    },
                    checked = autoPlayEnabled,
                    onCheckedChange = { viewModel.setAutoPlayEnabled(it) },
                    enabled = !toneOnlyMode
                )

                if (autoPlayEnabled && !toneOnlyMode) {
                    SwitchRow(
                        text = stringResource(R.string.set_unlock_only),
                        description = stringResource(R.string.set_unlock_only_desc),
                        checked = autoPlayOnUnlockOnly,
                        onCheckedChange = { viewModel.setAutoPlayOnUnlockOnly(it) }
                    )
                }
                
                SwitchRow(
                    text = stringResource(R.string.set_speak_text),
                    description = if (toneOnlyMode) {
                        stringResource(R.string.set_disabled_tone)
                    } else {
                        stringResource(R.string.set_speak_text_desc)
                    },
                    checked = speakTextIfNoVoice,
                    onCheckedChange = { viewModel.setSpeakTextIfNoVoice(it) },
                    enabled = !toneOnlyMode
                )

                val ttsLanguageMode by viewModel.ttsLanguageMode.collectAsState()
                Text(
                    text = stringResource(R.string.set_spoken_language),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = stringResource(R.string.set_spoken_language_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val ttsOptions = listOf(
                        stringResource(R.string.tts_auto),
                        stringResource(R.string.tts_device),
                        stringResource(R.string.tts_english)
                    )
                    ttsOptions.forEachIndexed { index, name ->
                        SnoozeOptionChip(
                            text = name,
                            isSelected = ttsLanguageMode == index,
                            onClick = { viewModel.setTtsLanguageMode(index) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                SwitchRow(
                    text = stringResource(R.string.set_private),
                    description = if (toneOnlyMode) {
                        stringResource(R.string.set_disabled_tone)
                    } else {
                        stringResource(R.string.set_private_desc)
                    },
                    checked = privatePlaybackEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && needsBluetoothConnectPermission(context)) {
                            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            viewModel.setPrivatePlaybackEnabled(enabled)
                        }
                    },
                    enabled = !toneOnlyMode
                )

                SwitchRow(
                    text = stringResource(R.string.set_tone_only),
                    description = toneOnlySubtitle,
                    checked = toneOnlyMode,
                    onCheckedChange = { viewModel.setToneOnlyMode(it) }
                )

                ToneSelectionRow(
                    currentToneLabel = resolveToneOnlyToneLabel(context, toneOnlyAlertToneUri),
                    hasCustomTone = !toneOnlyAlertToneUri.isNullOrBlank(),
                    enabled = toneOnlyMode,
                    onChooseTone = {
                        val existingUri = toneOnlyAlertToneUri
                            ?.takeIf { it.isNotBlank() }
                            ?.let(Uri::parse)
                            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        tonePickerLauncher.launchOrToast(
                            context,
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.set_select_tone))
                            },
                            R.string.tone_picker_unavailable
                        )
                    },
                    onUseDefault = { viewModel.setToneOnlyAlertToneUri(null) }
                )

                SwitchRow(
                    text = stringResource(R.string.set_fullscreen),
                    description = if (fullScreenAccessGranted) {
                        stringResource(R.string.set_fullscreen_desc_granted)
                    } else {
                        stringResource(R.string.set_fullscreen_desc_denied)
                    },
                    checked = fullScreenAlertEnabled,
                    onCheckedChange = {
                        viewModel.setFullScreenAlertEnabled(it)
                        if (it && !fullScreenAccessGranted) {
                            // Keep this toast: it explains why the system settings screen opens.
                            Toast.makeText(
                                context,
                                context.getString(R.string.set_allow_fullscreen),
                                Toast.LENGTH_SHORT
                            ).show()
                            FullScreenIntentSupport.openSettings(context)
                        }
                    }
                )

                if (fullScreenAlertEnabled && !fullScreenAccessGranted) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.set_fs_off_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.set_fs_off_msg),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { FullScreenIntentSupport.openSettings(context) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(stringResource(R.string.set_open_perm))
                            }
                        }
                    }
                }

                if (toneOnlyMode) {
                    Text(
                        text = stringResource(R.string.set_fs_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                // Volume Slider
                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.set_volume), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(60.dp))
                    val volumeCd = stringResource(R.string.set_cd_volume, (appVolume * 100).toInt())
                    Slider(
                        value = appVolume,
                        onValueChange = { viewModel.setAppVolume(it) },
                        valueRange = 0f..1f,
                        steps = 9,
                        enabled = !toneOnlyMode,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = volumeCd },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    Text(
                         "${(appVolume * 100).toInt()}%",
                         style = MaterialTheme.typography.bodyMedium,
                         color = if (toneOnlyMode) {
                             MaterialTheme.colorScheme.onSurfaceVariant
                         } else {
                             MaterialTheme.colorScheme.onSurface
                         },
                         modifier = Modifier.padding(start = 8.dp).width(40.dp)
                    )
                }
                if (toneOnlyMode) {
                    Text(
                        text = stringResource(R.string.set_tone_volume_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                 
                // Loop Auto-Stop (compact inline)
                Text(stringResource(R.string.set_loop_duration), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.set_loop_inline_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val loopPresets = listOf(5, 10, 15)
                val isCustomLoop = loopTimeoutMinutes != 0 && loopTimeoutMinutes !in loopPresets
                Row(
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    loopPresets.forEach { mins ->
                        SnoozeOptionChip(
                            text = stringResource(R.string.set_minutes_short, mins),
                            isSelected = loopTimeoutMinutes == mins,
                            onClick = { viewModel.setLoopTimeoutMinutes(mins) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Infinite option
                    SnoozeOptionChip(
                        text = stringResource(R.string.set_infinite),
                        isSelected = loopTimeoutMinutes == 0,
                        onClick = { viewModel.setLoopTimeoutMinutes(0) },
                        modifier = Modifier.weight(1f)
                    )

                }
                // Custom gets its own line, sized to its label. As a fifth equal-width chip it
                // broke mid-word ("Custo / m") even in English.
                Row(modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
                    var showCustomLoopDialog by remember { mutableStateOf(false) }
                    SnoozeOptionChip(
                        text = if (isCustomLoop) stringResource(R.string.set_minutes_short, loopTimeoutMinutes) else stringResource(R.string.set_custom),
                        isSelected = isCustomLoop,
                        onClick = { showCustomLoopDialog = true },
                        modifier = Modifier.widthIn(min = 96.dp)
                    )

                    if (showCustomLoopDialog) {
                        CustomDurationDialog(
                            title = stringResource(R.string.set_loop_duration),
                            initialValue = if (isCustomLoop) loopTimeoutMinutes else 20,
                            maxMinutes = 1440,
                            description = stringResource(R.string.set_loop_duration_desc),
                            onDismiss = { showCustomLoopDialog = false },
                            onSave = {
                                viewModel.setLoopTimeoutMinutes(it)
                                showCustomLoopDialog = false
                            }
                        )
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // Test Reminder Button
                OutlinedButton(
                    onClick = {
                        viewModel.scheduleTestReminder(context)
                        Toast.makeText(context, context.getString(R.string.set_toast_test), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.set_test_reminder))
                }
            }

            // ============================================================
            // SECTION 2: SNOOZE, FOLLOW-UP & QUIET TIME
            // ============================================================
            CollapsibleSettingsSection(
                title = stringResource(R.string.set_section_timing),
                icon = "Time",
                initiallyExpanded = true
            ) {
                // Snooze Duration
                Text(stringResource(R.string.set_default_snooze), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.set_snooze_inline_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val snoozePresets = listOf(2, 5, 10, 15)
                val isCustomSnooze = defaultSnoozeDuration !in snoozePresets
                Row(
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    snoozePresets.forEach { mins ->
                        SnoozeOptionChip(
                            text = stringResource(R.string.set_minutes_short, mins),
                            isSelected = !isCustomSnooze && defaultSnoozeDuration == mins,
                            onClick = { viewModel.setDefaultSnoozeDuration(mins) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                }
                Row(modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
                    var showCustomSnoozeDialog by remember { mutableStateOf(false) }
                    SnoozeOptionChip(
                        text = if (isCustomSnooze) stringResource(R.string.set_minutes_short, defaultSnoozeDuration) else stringResource(R.string.set_custom),
                        isSelected = isCustomSnooze,
                        onClick = { showCustomSnoozeDialog = true },
                        modifier = Modifier.widthIn(min = 96.dp)
                    )

                    if (showCustomSnoozeDialog) {
                        CustomDurationDialog(
                            title = stringResource(R.string.set_custom_snooze),
                            initialValue = if (isCustomSnooze) defaultSnoozeDuration else 20,
                            maxMinutes = 240,
                            description = stringResource(R.string.set_snooze_desc),
                            onDismiss = { showCustomSnoozeDialog = false },
                            onSave = {
                                viewModel.setDefaultSnoozeDuration(it)
                                showCustomSnoozeDialog = false
                            }
                        )
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // Default follow-up check
                Text(stringResource(R.string.set_default_followup), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (defaultFollowUpMinutes == 0) {
                        stringResource(R.string.set_followup_off_desc)
                    } else {
                        stringResource(R.string.set_followup_on_desc, defaultFollowUpMinutes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.ghostgramlabs.speakalert.ui.components.FollowUpDurationPicker(
                    currentMinutes = defaultFollowUpMinutes,
                    onChange = { minutes -> viewModel.setDefaultFollowUpMinutes(minutes) },
                    modifier = Modifier.padding(top = 4.dp)
                )

                // How many times a follow-up check repeats. 0 (∞) = until marked done, the
                // default — it matches the behavior existing users had before the limit existed.
                val followUpMaxRepeats by viewModel.followUpMaxRepeats.collectAsState()
                Text(
                    text = stringResource(R.string.set_followup_repeat),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = stringResource(R.string.set_followup_repeat_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 1, 3, 5, 10).forEach { count ->
                        SnoozeOptionChip(
                            text = if (count == 0) "∞" else "$count×",
                            isSelected = followUpMaxRepeats == count,
                            onClick = { viewModel.setFollowUpMaxRepeats(count) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // Quiet Time (moved from Timing)
                SwitchRow(
                    text = stringResource(R.string.set_quiet),
                    description = if (quietTimeEnabled) "${formatTime(startHour, startMinute)} - ${formatTime(endHour, endMinute)}" else stringResource(R.string.set_quiet_desc),
                    checked = quietTimeEnabled,
                    onCheckedChange = { viewModel.setQuietTimeEnabled(it) }
                )
                
                if (quietTimeEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TimePickerButton(
                            label = stringResource(R.string.set_start),
                            hour = startHour,
                            minute = startMinute,
                            onTimeSelected = { h, m -> viewModel.setQuietTimeStart(h, m) }
                        )

                        TimePickerButton(
                            label = stringResource(R.string.set_end),
                            hour = endHour,
                            minute = endMinute,
                            onTimeSelected = { h, m -> viewModel.setQuietTimeEnd(h, m) }
                        )
                    }
                }
                
            }



            // ============================================================
            // SECTION 3: PERMISSIONS (compact - show details only if issues)
            // ============================================================
            val hasMicPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            val hasNotifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            
            val canScheduleAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            } else true
            
            val allGranted = hasMicPerm && hasNotifPerm && canScheduleAlarms
            
            // Show Permissions section only if there are issues, or as a compact card if all good
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (allGranted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                                     else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                if (allGranted) {
                    // Compact success state
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.set_cd_granted),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.set_all_granted), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    // Expanded error state with fix buttons
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = stringResource(R.string.set_cd_action_required),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(stringResource(R.string.set_some_needed), style = MaterialTheme.typography.titleSmall)
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        if (!hasNotifPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PermissionRow(
                                name = stringResource(R.string.set_notifications),
                                granted = false,
                                onFix = {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                        
                        if (!canScheduleAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            PermissionRow(
                                name = stringResource(R.string.set_exact_alarms),
                                granted = false,
                                onFix = {
                                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                    context.startActivity(intent)
                                }
                            )
                        }
                        
                        if (!hasMicPerm) {
                            PermissionRow(
                                name = stringResource(R.string.set_microphone),
                                granted = false,
                                onFix = {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
            }

            // ============================================================
            // SECTION 4: RELIABILITY
            // ============================================================
            CollapsibleSettingsSection(
                title = stringResource(R.string.set_reliability),
                icon = "Safe",
                initiallyExpanded = true
            ) {
                val persistUntilDone by viewModel.persistUntilDone.collectAsState()
                SwitchRow(
                    text = stringResource(R.string.set_persist),
                    description = stringResource(R.string.set_persist_desc),
                    checked = persistUntilDone,
                    onCheckedChange = { viewModel.setPersistUntilDone(it) }
                )

                SwitchRow(
                    text = stringResource(R.string.set_dnd),
                    description = when {
                        !dndBypassEnabled -> stringResource(R.string.set_dnd_desc_off)
                        dndPolicyAccessGranted -> stringResource(R.string.set_dnd_desc_active)
                        else -> stringResource(R.string.set_dnd_desc_needed)
                    },
                    checked = dndBypassEnabled,
                    onCheckedChange = {
                        viewModel.setDndBypassEnabled(it)
                        NotificationHelper(context).refreshChannels(dndBypassEnabled = it)
                    }
                )

                if (dndBypassEnabled && !dndPolicyAccessGranted) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.set_dnd_unavailable),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.set_grant_dnd))
                    }
                    Text(
                        stringResource(R.string.set_dnd_note, APP_DISPLAY_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBatteryOptimizationGuide() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.set_battery_guide),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (batteryOptimizationEnabled) {
                                    stringResource(R.string.set_battery_desc)
                                } else {
                                    stringResource(R.string.set_battery_desc_ok, APP_DISPLAY_NAME)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.mirrorInRtl()
                    )
                    }
                }
            }

            // ============================================================
            // SECTION 5: WEAR OS
            // ============================================================
            CollapsibleSettingsSection(
                title = stringResource(R.string.set_wear),
                icon = "Wear",
                initiallyExpanded = true
            ) {
                val notificationsReady =
                    appNotificationStatus.appNotificationsEnabled && appNotificationStatus.reminderChannelEnabled
                val statusText = when {
                    isCheckingWearStatus -> stringResource(R.string.set_wear_checking)
                    wearConnectionInfo.isConnected && wearConnectionInfo.connectedNodeCount > 1 -> {
                        stringResource(R.string.set_wear_watches, wearConnectionInfo.connectedNodeCount)
                    }
                    wearConnectionInfo.isConnected -> stringResource(R.string.set_wear_connected)
                    else -> stringResource(R.string.set_wear_none)
                }

                val statusColor = if (wearConnectionInfo.isConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Watch,
                                contentDescription = null,
                                tint = statusColor
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            if (isCheckingWearStatus) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = statusColor
                            )
                        }

                        Text(
                            text = stringResource(R.string.set_wear_supported, APP_DISPLAY_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (wearConnectionInfo.isConnected && !notificationsReady) {
                            Text(
                                text = if (!appNotificationStatus.appNotificationsEnabled) {
                                    stringResource(R.string.set_wear_notif_off)
                                } else {
                                    stringResource(R.string.set_wear_channel_muted, APP_DISPLAY_NAME)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )

                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.set_open_notif))
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            putExtra(Settings.EXTRA_CHANNEL_ID, NotificationHelper.CHANNEL_ID)
                                        }
                                        context.startActivity(intent)
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.set_open_channel))
                                }
                            }
                        } else if (wearConnectionInfo.isConnected) {
                            Text(
                                text = stringResource(R.string.set_wear_enabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedButton(
                            onClick = { wearRefreshTick++ },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.set_refresh))
                        }
                    }
                }
            }

            // SECTION 6: HELP & ABOUT (guide, support, rating, more apps)
            // ============================================================
            CollapsibleSettingsSection(
                title = stringResource(R.string.set_section_about),
                icon = "Help",
                initiallyExpanded = true
            ) {
                var showHelpDialog by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showHelpDialog = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Help,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.set_app_guide),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.mirrorInRtl()
                    )
                }

                if (showHelpDialog) {
                    HelpDialog(onDismiss = { showHelpDialog = false })
                }

                Divider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!openSupportEmail(context)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.set_toast_no_email),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.set_contact_support),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.set_contact_support_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                stringResource(R.string.support_email_address),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    textDirection = androidx.compose.ui.text.style.TextDirection.Ltr
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.mirrorInRtl()
                    )
                }

                // The "Rate SpeakAlert" row is deliberately absent. Rating is now only ever
                // offered by the automatic prompt, which waits for a week of delivered reminders
                // and steps aside when one was just missed - so the people it reaches have
                // actually had the app work for them. A permanent entry point in Settings has no
                // such timing: it is equally available to someone who came to Settings because
                // something was wrong.

                Divider()

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.set_more_apps_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.set_more_apps_desc, APP_DISPLAY_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                MoreAppRow(
                    name = stringResource(R.string.app_pettibox_name),
                    description = stringResource(R.string.app_pettibox_desc),
                    packageName = "com.ghostgramlabs.pettibox",
                    context = context
                )
                MoreAppRow(
                    name = stringResource(R.string.app_directserve_name),
                    description = stringResource(R.string.app_directserve_desc),
                    packageName = "com.ghostgramlabs.directserve",
                    context = context
                )
            }

            // ============================================================
            // BACKUP & RESTORE
            // ============================================================
            val backupManager = com.ghostgramlabs.speakalert.data.backup.ReminderBackupManager
            val exportBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(backupManager.MIME_TYPE)
            ) { uri -> uri?.let { viewModel.exportBackup(context, it) } }
            val importBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { viewModel.importBackup(context, it) } }
            CollapsibleSettingsSection(
                title = stringResource(R.string.set_section_backup),
                icon = "Backup",
                initiallyExpanded = true
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.requestBackup(context) {
                                exportBackupLauncher.launchOrToast(context, backupManager.suggestedFileName(), R.string.file_picker_unavailable)
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.set_backup_export),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.set_backup_export_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.mirrorInRtl()
                    )
                }
                Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            importBackupLauncher.launchOrToast(
                                context,
                                arrayOf(backupManager.MIME_TYPE, "application/octet-stream"),
                                R.string.file_picker_unavailable
                            )
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.set_backup_import),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            stringResource(R.string.set_backup_import_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.mirrorInRtl()
                    )
                }
            }

            // Debug Section - ONLY for Debug builds (hidden from most users)
            if (com.ghostgramlabs.speakalert.BuildConfig.DEBUG) {
                CollapsibleSettingsSection(
                    title = stringResource(R.string.set_developer),
                    icon = "Dev",
                    initiallyExpanded = true
                ) {
                    SwitchRow(
                        text = stringResource(R.string.set_debug_logging),
                        description = stringResource(R.string.set_debug_desc),
                        checked = debugLoggingEnabled,
                        onCheckedChange = { viewModel.setDebugLoggingEnabled(it) }
                    )

                    if (debugLoggingEnabled) {
                        OutlinedButton(
                            onClick = { viewModel.sendLogs(context) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).mirrorInRtl()
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.set_send_logs))
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
        }
    }
}

private data class AppNotificationStatus(
    val appNotificationsEnabled: Boolean,
    val reminderChannelEnabled: Boolean
)

private fun readAppNotificationStatus(context: android.content.Context): AppNotificationStatus {
    val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val reminderChannel = manager?.getNotificationChannel(NotificationHelper.CHANNEL_ID)
        reminderChannel?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    } else {
        true
    }
    return AppNotificationStatus(
        appNotificationsEnabled = appEnabled,
        reminderChannelEnabled = channelEnabled
    )
}

private fun readDndPolicyAccessGranted(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    return manager?.isNotificationPolicyAccessGranted == true
}

@Composable
private fun PermissionRow(
    name: String,
    granted: Boolean,
    onFix: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (granted) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onFix) {
                    Text(stringResource(R.string.set_fix))
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSettingsSection(
    title: String,
    icon: String,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "rotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
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
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = settingsSectionIcon(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) stringResource(R.string.set_cd_collapse) else stringResource(R.string.set_cd_expand),
                    modifier = Modifier.rotate(rotationAngle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

private fun settingsSectionIcon(icon: String): ImageVector {
    return when (icon) {
        "UI" -> Icons.Default.Palette
        "Play" -> Icons.Default.PlayArrow
        "Time" -> Icons.Default.Schedule
        "Safe" -> Icons.Default.VerifiedUser
        "Backup" -> Icons.Default.CloudUpload
        "Wear" -> Icons.Default.Watch
        "Help" -> Icons.Default.Help
        "Dev" -> Icons.Default.Build
        else -> Icons.Default.Settings
    }
}

@Composable
private fun MoreAppRow(
    name: String,
    description: String,
    packageName: String,
    context: android.content.Context
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!openPlayStoreListing(context, packageName)) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.set_toast_no_store),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.mirrorInRtl()
                    )
    }
}

private fun needsBluetoothConnectPermission(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
private fun SwitchRow(
    text: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        val onLabel = stringResource(R.string.state_on)
        val offLabel = stringResource(R.string.state_off)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    enabled = enabled,
                    onValueChange = onCheckedChange
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) {
                    stateDescription = if (checked) onLabel else offLabel
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val contentColor = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(text, style = MaterialTheme.typography.bodyLarge, color = contentColor)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
private fun ToneSelectionRow(
    currentToneLabel: String,
    hasCustomTone: Boolean,
    enabled: Boolean,
    onChooseTone: () -> Unit,
    onUseDefault: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.set_tone_sound),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = if (enabled) {
                    stringResource(R.string.set_tone_sound_active, currentToneLabel)
                } else {
                    stringResource(R.string.set_tone_sound_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onChooseTone,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.set_choose_tone))
                }
                if (hasCustomTone) {
                    OutlinedButton(
                        onClick = onUseDefault,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.set_use_default))
                    }
                }
            }
        }
    }
}

private fun resolveToneOnlyToneLabel(
    context: android.content.Context,
    uriString: String?
): String {
    if (uriString.isNullOrBlank()) return context.getString(R.string.set_default_alarm_tone)
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return context.getString(R.string.set_default_alarm_tone)
    val ringtone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull()
    return ringtone?.getTitle(context) ?: context.getString(R.string.set_tone_unavailable)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerButton(
    label: String,
    hour: Int,
    minute: Int,
    onTimeSelected: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showPicker = true }
    ) {
        Text(stringResource(R.string.set_label_time, label, formatTime(hour, minute)))
    }

    if (showPicker) {
        if (shouldUseSystemDateTimePickers()) {
            SystemTimePickerDialog(
                initialHour = hour,
                initialMinute = minute,
                is24Hour = com.ghostgramlabs.speakalert.util.TimeFormat.use24Hour,
                onDismiss = { showPicker = false },
                onConfirm = { h, m ->
                    onTimeSelected(h, m)
                    showPicker = false
                },
            )
        } else {
            AppTimePickerDialog(
                initialHour = hour,
                initialMinute = minute,
                is24Hour = com.ghostgramlabs.speakalert.util.TimeFormat.use24Hour,
                title = stringResource(R.string.set_select_label_time, label),
                onDismiss = { showPicker = false },
                onConfirm = { pickedHour, pickedMinute -> onTimeSelected(pickedHour, pickedMinute)
                            showPicker = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDurationDialog(
    title: String,
    initialValue: Int,
    maxMinutes: Int,
    description: String,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var value by remember { mutableStateOf(initialValue.toString()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.set_desc_max, description, maxMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = value,
                onValueChange = { newValue ->
                    val normalized = newValue.normalizeLocalizedDigitsOrNull()
                    if (normalized != null && normalized.length <= maxMinutes.toString().length) {
                        value = normalized
                    }
                },
                label = { Text(stringResource(R.string.set_minutes)) },
                supportingText = { Text(stringResource(R.string.set_max_allowed, maxMinutes)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val mins = value.toLocalizedIntOrNull() ?: 10
                    onSave(mins.coerceIn(1, maxMinutes))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_save))
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

private fun formatTime(hour: Int, minute: Int): String {
    val h = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
    val amPm = if (hour < 12) "AM" else "PM"
    return String.format("%d:%02d %s", h, minute, amPm)
}
