package com.ghostgramlabs.speakalert

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.ghostgramlabs.speakalert.ui.theme.VoiceReminderTheme
import com.ghostgramlabs.speakalert.ui.navigation.VoiceReminderNavGraph
import com.ghostgramlabs.speakalert.ui.navigation.NavigationDestination
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.res.stringResource
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.data.repository.AlertNotification
import com.ghostgramlabs.speakalert.data.repository.hasActiveAlert
import com.ghostgramlabs.speakalert.data.repository.hasRecentMiss
import com.ghostgramlabs.speakalert.util.APP_DISPLAY_NAME
import com.ghostgramlabs.speakalert.util.BatteryOptimizationSupport
import com.ghostgramlabs.speakalert.util.FullScreenIntentSupport
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        (application as VoiceReminderApp).applicationScope.launch {
            runCatching { com.ghostgramlabs.speakalert.data.retention.RecordedReminderExpiryWorker.enqueueSweep(this@MainActivity) }
                .onFailure { android.util.Log.w("RecordingExpiry", "Could not refresh recording retention", it) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("reminderId") || intent.getBooleanExtra("openAddEdit", false)) {
            // Existing notification and widget routes still initialize their requested screen
            // when singleTop delivers an intent to a running MainActivity.
            recreate()
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.ghostgramlabs.speakalert.util.AppLocale.wrapContext(newBase))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply before Activity restores and installs its window so a forced Light/Dark theme
        // cannot expose the manifest theme's background during cold start.
        com.ghostgramlabs.speakalert.util.ThemePrefs.applyWindowTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Read intent extras for notification tap handling
        val reminderId = intent.getLongExtra("reminderId", -1L)
        val autoplay = intent.getBooleanExtra("autoplay", false)
        val openAddEdit = intent.getBooleanExtra("openAddEdit", false)
        
        setContent {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val isHomeDestination = backStackEntry?.destination?.route == NavigationDestination.Home.route
            val app = applicationContext as VoiceReminderApp
            val settingsRepository = app.container.settingsRepository
            val currentVersionName = BuildConfig.VERSION_NAME
            val themeMode by app.container.settingsRepository.themeMode.collectAsState(
                initial = com.ghostgramlabs.speakalert.util.ThemePrefs.cached(this@MainActivity)
            )
            val fullScreenAlertEnabled by settingsRepository.fullScreenAlertEnabled.collectAsState(initial = false)
            val startupPromptState by produceState<StartupPromptState?>(
                initialValue = null,
                key1 = settingsRepository
            ) {
                settingsRepository.batteryOptimizationPromptShown
                    .combine(settingsRepository.lastWhatsNewVersionShown) { batteryPromptShown, lastVersionShown ->
                        StartupPromptState(
                            batteryOptimizationPromptShown = batteryPromptShown,
                            lastWhatsNewVersionShown = lastVersionShown
                        )
                    }
                    .collect { value = it }
            }
            val coroutineScope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current
            var showBatteryOptimizationDialog by rememberSaveable { mutableStateOf(false) }
            // Latched once the battery prompt has had its one chance this launch, so returning to
            // Home later in the session cannot raise it again. Saveable so a rotation does not
            // count as a fresh launch.
            var batteryPromptEvaluated by rememberSaveable { mutableStateOf(false) }
            var showWhatsNewSheet by rememberSaveable { mutableStateOf(false) }
            var showFullScreenRecoveryDialog by rememberSaveable { mutableStateOf(false) }
            var showRatingPrompt by rememberSaveable { mutableStateOf(false) }
            var ratingEvaluated by rememberSaveable { mutableStateOf(false) }
            // Set while the system battery-exemption request is on screen. Closing the battery sheet
            // unblocks the notification permission prompt, and launching that in the same moment
            // replaced the battery request before the user ever saw it; it waits for our return.
            var awaitingBatteryRequest by rememberSaveable { mutableStateOf(false) }
            var activityResumed by androidx.compose.runtime.remember {
                mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            }
            var fullScreenAccessGranted by rememberSaveable {
                mutableStateOf(FullScreenIntentSupport.canUseFullScreenIntent(this@MainActivity))
            }
            val startupPromptsLoaded = startupPromptState != null
            val batteryOptimizationPromptShown = startupPromptState?.batteryOptimizationPromptShown ?: false
            val lastWhatsNewVersionShown = startupPromptState?.lastWhatsNewVersionShown
            val shouldOfferWhatsNew = reminderId == -1L && !autoplay && !openAddEdit
            val startupIntro = startupIntroFor(
                lastWhatsNewVersionShown, currentVersionName, shouldOfferWhatsNew,
                preferencesLoaded = startupPromptsLoaded
            )
            val needsWhatsNew =
                startupPromptsLoaded &&
                    startupIntro != null
            val allowHomeStartupOverlays =
                startupPromptsLoaded &&
                    !needsWhatsNew &&
                    !showWhatsNewSheet &&
                    !showBatteryOptimizationDialog &&
                    batteryOptimizationPromptShown
            
            val isDarkTheme = when (themeMode) {
                1 -> false // Light
                2 -> true  // Dark
                else -> isSystemInDarkTheme() // System
            }

            // Migrates existing DataStore-only preferences into the synchronous startup mirror.
            LaunchedEffect(themeMode) {
                com.ghostgramlabs.speakalert.util.ThemePrefs.cache(this@MainActivity, themeMode)
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    activityResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (event == Lifecycle.Event.ON_RESUME) {
                        awaitingBatteryRequest = false
                        fullScreenAccessGranted =
                            FullScreenIntentSupport.canUseFullScreenIntent(this@MainActivity)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            LaunchedEffect(startupPromptsLoaded, needsWhatsNew) {
                if (!startupPromptsLoaded || !needsWhatsNew) return@LaunchedEffect
                ratingEvaluated = true // Never ask during an introduction or release-notes session.
                showWhatsNewSheet = true
            }

            // This is a startup prompt, so it may only interrupt a launch. Its inputs all change
            // during ordinary use - isHomeDestination flips every time the user comes back from
            // the editor or Settings - and without a latch the effect re-ran on each of those and
            // put the dialog up in the middle of whatever the user was doing. Evaluated once per
            // launch, and only with the window actually in front of the user, so it arrives in
            // its place in the sequence (release notes, then this, then anything else) or not at
            // all until the next launch.
            LaunchedEffect(
                startupPromptsLoaded,
                batteryOptimizationPromptShown,
                needsWhatsNew,
                showWhatsNewSheet,
                shouldOfferWhatsNew,
                isHomeDestination,
                activityResumed
            ) {
                if (batteryPromptEvaluated) return@LaunchedEffect
                if (!startupPromptsLoaded) return@LaunchedEffect
                if (!isHomeDestination || !activityResumed) return@LaunchedEffect
                if (!shouldOfferWhatsNew) return@LaunchedEffect
                // Wait for the release notes rather than latching past them: they are shown first
                // and this evaluates again once they close.
                if (needsWhatsNew || showWhatsNewSheet) return@LaunchedEffect
                if (batteryOptimizationPromptShown) {
                    batteryPromptEvaluated = true
                    return@LaunchedEffect
                }
                // Deliberately no window-focus check. One here returned without latching, and
                // focus is not one of this effect's keys, so a cold launch that evaluated before
                // the window was focused skipped the prompt and never came back to it - which on
                // a second launch, with no release notes in the way to delay things, was every
                // time. Being resumed on Home with no sheet up is already the right moment.
                batteryPromptEvaluated = true
                if (!BatteryOptimizationSupport.isBatteryOptimizationEnabled(this@MainActivity)) {
                    settingsRepository.setBatteryOptimizationPromptShown(true)
                    return@LaunchedEffect
                }
                showBatteryOptimizationDialog = true
            }

            // Ask after sustained reminder use, only at an unobstructed home launch.
            LaunchedEffect(allowHomeStartupOverlays, isHomeDestination, activityResumed) {
                if (ratingEvaluated) return@LaunchedEffect
                if (!allowHomeStartupOverlays) return@LaunchedEffect
                if (!shouldOfferWhatsNew || !isHomeDestination || !activityResumed) return@LaunchedEffect
                try {
                    if (fullScreenAlertEnabled && !fullScreenAccessGranted) return@LaunchedEffect
                    if (!androidx.core.app.NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled()) return@LaunchedEffect
                    val checkedAt = System.currentTimeMillis()
                    // Only a recent miss suggests the user is currently let down; stale entries in
                    // the Missed tab would otherwise disqualify them forever.
                    val missedDetectedAt = app.container.missedReminderRepository
                        .allMissedReminders.first().map { it.detectedTime }
                    if (hasRecentMiss(missedDetectedAt, checkedAt)) return@LaunchedEffect
                    val notifications = getSystemService(android.app.NotificationManager::class.java)
                    // Reminder alerts stay posted until acted on, so an un-swiped one from
                    // yesterday is normal. Only something still sounding, pinned, or just posted
                    // means the user is mid-alert.
                    val alerts = notifications.activeNotifications.map {
                        AlertNotification(
                            postedAt = it.postTime,
                            ongoing = it.isOngoing
                        )
                    }
                    if (hasActiveAlert(alerts, checkedAt)) return@LaunchedEffect
                    if (!window.decorView.hasWindowFocus()) return@LaunchedEffect
                    // Latch only after the transient checks pass, so one unfocused evaluation
                    // does not suppress the prompt for the whole session.
                    ratingEvaluated = true
                    showRatingPrompt = settingsRepository.claimRatingPrompt(System.currentTimeMillis())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.ghostgramlabs.speakalert.util.FileLogger.logError("REVIEW", "Could not check review timing", e)
                }
            }

            LaunchedEffect(
                allowHomeStartupOverlays,
                shouldOfferWhatsNew,
                fullScreenAlertEnabled,
                fullScreenAccessGranted
            ) {
                if (!allowHomeStartupOverlays || !shouldOfferWhatsNew) {
                    showFullScreenRecoveryDialog = false
                    return@LaunchedEffect
                }
                // Auto Backup can restore the in-app toggle after reinstall while Android
                // resets the special full-screen access. Prompt for recovery instead of
                // leaving the feature silently broken until the user toggles it manually.
                showFullScreenRecoveryDialog =
                    fullScreenAlertEnabled && !fullScreenAccessGranted
            }

            VoiceReminderTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VoiceReminderNavGraph(
                        navController = navController,
                        startReminderId = if (reminderId != -1L) reminderId else null,
                        autoplay = autoplay,
                        startAddEdit = openAddEdit,
                        allowHomeStartupOverlays = allowHomeStartupOverlays,
                        // Direct widget launches skip introductions, but still need notification access.
                        allowNotificationPrompt = startupPromptsLoaded && !needsWhatsNew &&
                            !showWhatsNewSheet && !showBatteryOptimizationDialog &&
                            !awaitingBatteryRequest &&
                            (batteryOptimizationPromptShown || !shouldOfferWhatsNew)
                    )

                    if (showBatteryOptimizationDialog) {
                        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        ModalBottomSheet(
                            // A tap outside the sheet or a stray swipe is not an answer. This is
                            // the setting that decides whether reminders arrive at all, and it
                            // used to get one chance ever - spent by the accident. Closing it
                            // this way leaves it to be asked again on a later launch; only the
                            // explicit "Later" below counts as having been asked.
                            onDismissRequest = { showBatteryOptimizationDialog = false },
                            sheetState = sheetState,
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
                                    text = stringResource(R.string.batt_prompt_title, APP_DISPLAY_NAME),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.batt_prompt_message, APP_DISPLAY_NAME),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        showBatteryOptimizationDialog = false
                                        coroutineScope.launch {
                                            settingsRepository.setBatteryOptimizationPromptShown(true)
                                        }
                                        val opened = BatteryOptimizationSupport.requestIgnoreBatteryOptimizations(this@MainActivity)
                                        awaitingBatteryRequest = opened
                                        if (!opened) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                getString(R.string.batt_toast_unavailable),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(stringResource(R.string.action_allow))
                                }
                                OutlinedButton(
                                    onClick = {
                                        showBatteryOptimizationDialog = false
                                        coroutineScope.launch {
                                            settingsRepository.setBatteryOptimizationPromptShown(true)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(stringResource(R.string.action_later))
                                }
                            }
                        }
                    }

                    if (showWhatsNewSheet && startupIntro == StartupIntro.QUICK_START) {
                        com.ghostgramlabs.speakalert.ui.settings.QuickStartGuide(
                            onDismiss = {
                                showWhatsNewSheet = false
                                coroutineScope.launch {
                                    settingsRepository.setLastWhatsNewVersionShown(currentVersionName)
                                }
                            },
                            onOpenSettings = {
                                showWhatsNewSheet = false
                                navController.navigate(NavigationDestination.Settings.route) {
                                    launchSingleTop = true
                                }
                                coroutineScope.launch {
                                    settingsRepository.setLastWhatsNewVersionShown(currentVersionName)
                                }
                            }
                        )
                    } else if (showWhatsNewSheet && startupIntro == StartupIntro.RELEASE_NOTES) {
                        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        ModalBottomSheet(
                            onDismissRequest = {
                                showWhatsNewSheet = false
                                coroutineScope.launch {
                                    settingsRepository.setLastWhatsNewVersionShown(currentVersionName)
                                }
                            },
                            sheetState = sheetState,
                            dragHandle = { BottomSheetDefaults.DragHandle() }
                        ) {
                            val whatsNewScrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .navigationBarsPadding()
                                    .padding(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .verticalScroll(whatsNewScrollState),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.wn_title, currentVersionName),
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.wn_intro),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    // Ordered by how often the reader will meet it.
                                    WhatsNewFeatureCard(
                                        title = stringResource(R.string.wn_lang_title),
                                        description = stringResource(R.string.wn_lang_desc)
                                    )
                                    WhatsNewFeatureCard(
                                        title = stringResource(R.string.wn_missed_title),
                                        description = stringResource(R.string.wn_missed_desc)
                                    )
                                    WhatsNewFeatureCard(
                                        title = stringResource(R.string.wn_quiet_title),
                                        description = stringResource(R.string.wn_quiet_desc)
                                    )
                                    WhatsNewFeatureCard(
                                        title = stringResource(R.string.wn_edit_title),
                                        description = stringResource(R.string.wn_edit_desc)
                                    )
                                }
                                Button(
                                    onClick = {
                                        showWhatsNewSheet = false
                                        coroutineScope.launch {
                                            settingsRepository.setLastWhatsNewVersionShown(currentVersionName)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(stringResource(R.string.action_continue))
                                }
                            }
                        }
                    }

                    if (showRatingPrompt) {
                        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        ModalBottomSheet(
                            onDismissRequest = { showRatingPrompt = false },
                            sheetState = sheetState,
                            dragHandle = { BottomSheetDefaults.DragHandle() }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .navigationBarsPadding()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.rate_prompt_title, APP_DISPLAY_NAME),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.rate_prompt_message, APP_DISPLAY_NAME),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        showRatingPrompt = false
                                        coroutineScope.launch {
                                            // Opening the listing is an intention, not a posted
                                            // review: pause for months instead of forever.
                                            settingsRepository.setRatingPromptRated(
                                                System.currentTimeMillis()
                                            )
                                        }
                                        openPlayStoreListing(
                                            this@MainActivity,
                                            this@MainActivity.packageName
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(stringResource(R.string.rate_prompt_positive))
                                }
                                OutlinedButton(
                                    onClick = { showRatingPrompt = false },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(stringResource(R.string.rate_prompt_later))
                                }
                                TextButton(
                                    onClick = {
                                        showRatingPrompt = false
                                        coroutineScope.launch {
                                            settingsRepository.setRatingPromptDecided(true)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.rate_prompt_never))
                                }
                                TextButton(
                                    onClick = {
                                        if (com.ghostgramlabs.speakalert.util.openSupportEmail(
                                                this@MainActivity, featureRequest = true
                                            )) {
                                            showRatingPrompt = false
                                        } else {
                                            Toast.makeText(
                                                this@MainActivity,
                                                getString(R.string.set_toast_no_email),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.suggest_feature))
                                }
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        stringResource(R.string.support_email_address),
                                        modifier = Modifier.fillMaxWidth(),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            textDirection = androidx.compose.ui.text.style.TextDirection.Ltr,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (showFullScreenRecoveryDialog) {
                        AlertDialog(
                            onDismissRequest = { showFullScreenRecoveryDialog = false },
                            title = {
                                Text(stringResource(R.string.fsr_title))
                            },
                            text = {
                                Text(
                                    stringResource(R.string.fsr_message, APP_DISPLAY_NAME)
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showFullScreenRecoveryDialog = false
                                        FullScreenIntentSupport.openSettings(this@MainActivity)
                                    }
                                ) {
                                    Text(stringResource(R.string.action_open_settings))
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { showFullScreenRecoveryDialog = false }
                                ) {
                                    Text(stringResource(R.string.action_later))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private data class StartupPromptState(
    val batteryOptimizationPromptShown: Boolean,
    val lastWhatsNewVersionShown: String?
)

/** Opens a Google Play listing, preferring the Play Store app and falling back to the browser. */
internal fun openPlayStoreListing(context: android.content.Context, packageName: String): Boolean {
    val marketIntent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("market://details?id=$packageName")
    ).apply {
        setPackage("com.android.vending")
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
    ).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return when {
        marketIntent.resolveActivity(context.packageManager) != null -> {
            context.startActivity(marketIntent); true
        }
        webIntent.resolveActivity(context.packageManager) != null -> {
            context.startActivity(webIntent); true
        }
        else -> false
    }
}

@Composable
private fun WhatsNewFeatureCard(
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
