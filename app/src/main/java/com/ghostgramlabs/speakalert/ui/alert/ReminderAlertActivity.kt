package com.ghostgramlabs.speakalert.ui.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.alarm.ToneAlertPlayer
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.service.ReminderPlaybackService
import com.ghostgramlabs.speakalert.ui.theme.VoiceReminderTheme
import com.ghostgramlabs.speakalert.util.APP_DISPLAY_NAME
import com.ghostgramlabs.speakalert.util.DateUtils
import com.ghostgramlabs.speakalert.util.sanitizeUnitFloat

class ReminderAlertActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.ghostgramlabs.speakalert.util.AppLocale.wrapContext(newBase))
    }

    companion object {
        const val EXTRA_ALERT_TITLE = "alertTitle"
        const val EXTRA_ALERT_MESSAGE = "alertMessage"
        const val EXTRA_PLAYBACK_AUDIO_PATH = "playbackAudioPath"
        const val EXTRA_PLAYBACK_TEXT = "playbackText"
        const val EXTRA_IS_FOLLOW_UP_ALERT = "isFollowUpAlert"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply before Activity restores and installs its window so a forced Light/Dark theme
        // cannot flash the manifest theme when the alarm wakes the screen.
        com.ghostgramlabs.speakalert.util.ThemePrefs.applyWindowTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val reminderId = intent.getLongExtra("reminderId", -1L)
        val alertTitleOverride = intent.getStringExtra(EXTRA_ALERT_TITLE)
        val alertMessageOverride = intent.getStringExtra(EXTRA_ALERT_MESSAGE)
        val playbackAudioPathOverride = intent.getStringExtra(EXTRA_PLAYBACK_AUDIO_PATH)
        val playbackTextOverride = intent.getStringExtra(EXTRA_PLAYBACK_TEXT)
        val isFollowUpAlert = intent.getBooleanExtra(EXTRA_IS_FOLLOW_UP_ALERT, false)
        if (reminderId == -1L) {
            finish()
            return
        }

        setContent {
            val app = applicationContext as VoiceReminderApp
            val themeMode by app.container.settingsRepository.themeMode.collectAsState(
                initial = com.ghostgramlabs.speakalert.util.ThemePrefs.cached(
                    this@ReminderAlertActivity
                )
            )
            val isDarkTheme = when (themeMode) {
                1 -> false // Light
                2 -> true  // Dark
                else -> isSystemInDarkTheme() // System
            }

            LaunchedEffect(themeMode) {
                com.ghostgramlabs.speakalert.util.ThemePrefs.cache(
                    this@ReminderAlertActivity,
                    themeMode
                )
            }

            VoiceReminderTheme(darkTheme = isDarkTheme) {
                BackHandler(enabled = true) {
                    // Keep the reminder visible until the user explicitly chooses an action.
                }
                val reminder by produceState<ReminderEntity?>(initialValue = null, reminderId) {
                    value = app.container.reminderRepository.getReminder(reminderId)
                    if (value == null) finish()
                }
                reminder?.let {
                    ReminderAlertContent(
                        reminder = it,
                        onPlayAgain = {
                            sendPlayAction(
                                reminderId = it.id,
                                title = alertTitleOverride ?: it.title ?: APP_DISPLAY_NAME,
                                audioPath = playbackAudioPathOverride,
                                reminderText = playbackTextOverride
                            )
                        },
                        onStopPlayback = {
                            stopPlayback()
                        },
                        onDone = {
                            sendReminderAction("ACTION_DONE", it.id)
                            finish()
                        },
                        onSnoozeFive = {
                            sendReminderAction("ACTION_SNOOZE", it.id, 5)
                            finish()
                        },
                        onSnoozeTen = {
                            sendReminderAction("ACTION_SNOOZE", it.id, 10)
                            finish()
                        },
                        titleOverride = alertTitleOverride,
                        messageOverride = alertMessageOverride,
                        playbackAudioPath = playbackAudioPathOverride,
                        playbackText = playbackTextOverride,
                        isFollowUpAlert = isFollowUpAlert
                    )
                }
            }
        }
    }

    private fun sendReminderAction(action: String, reminderId: Long, snoozeMinutesOverride: Int? = null) {
        sendBroadcast(
            Intent(this, com.ghostgramlabs.speakalert.alarm.ReminderActionReceiver::class.java).apply {
                this.action = action
                putExtra("reminderId", reminderId)
                if (snoozeMinutesOverride != null) {
                    putExtra("snoozeMinutesOverride", snoozeMinutesOverride)
                }
            }
        )
    }

    private fun sendPlayAction(
        reminderId: Long,
        title: String,
        audioPath: String?,
        reminderText: String?
    ) {
        sendBroadcast(
            Intent(this, com.ghostgramlabs.speakalert.alarm.ReminderActionReceiver::class.java).apply {
                action = "ACTION_PLAY"
                putExtra("reminderId", reminderId)
                putExtra("title", title)
                audioPath?.let { putExtra("audioPath", it) }
                reminderText?.let { putExtra("reminderText", it) }
            }
        )
    }

    private fun stopPlayback() {
        ReminderPlaybackService.stop(this)
        ToneAlertPlayer.stop()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderAlertContent(
    reminder: ReminderEntity,
    onPlayAgain: () -> Unit,
    onStopPlayback: () -> Unit,
    onDone: () -> Unit,
    onSnoozeFive: () -> Unit,
    onSnoozeTen: () -> Unit,
    titleOverride: String?,
    messageOverride: String?,
    playbackAudioPath: String?,
    playbackText: String?,
    isFollowUpAlert: Boolean
) {
    val context = LocalContext.current
    var isPlaying by remember(reminder.id) { mutableStateOf(false) }

    DisposableEffect(context, reminder.id) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != "ACTION_PLAYBACK_STATUS") return
                val activeReminderId = intent.getLongExtra("reminderId", -1L)
                val playing = intent.getBooleanExtra("isPlaying", false)
                isPlaying = activeReminderId == reminder.id && playing
            }
        }
        val filter = IntentFilter("ACTION_PLAYBACK_STATUS")
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    val isFollowUp = isFollowUpAlert || (reminder.followUpCheckMinutes > 0 && reminder.pendingFollowUpAt != null)
    val headline = titleOverride
        ?: reminder.title
        ?: com.ghostgramlabs.speakalert.transit.BusAlert.display(reminder.reminderText)
        ?: APP_DISPLAY_NAME
    val bodyText = messageOverride
        ?: com.ghostgramlabs.speakalert.transit.BusAlert.display(reminder.reminderText)
        ?: if (isFollowUp) {
            stringResource(R.string.alert_did_you_complete)
        } else {
            stringResource(R.string.alert_reminder_active)
        }
    val scheduledText = DateUtils.formatSmartDate(reminder.nextTriggerAt)
    val canPlayAgain = !playbackAudioPath.isNullOrBlank() || !playbackText.isNullOrBlank()
    val sourceLabel = when {
        isFollowUp -> stringResource(R.string.alert_followup_check)
        !playbackAudioPath.isNullOrBlank() -> stringResource(R.string.alert_voice_reminder)
        !playbackText.isNullOrBlank() -> stringResource(R.string.alert_text_reminder)
        else -> stringResource(R.string.alert_generic)
    }
    // This screen is read at arm's length, in the dark, by someone who was doing something else a
    // second ago. It is built like an alarm rather than a page: the reminder's own words carry
    // it, and every piece of motion is tied to something real - the arrival of the alert, and
    // whether sound is currently coming out of the phone. Nothing loops for decoration.

    // The alert does not fade up like a page load; it arrives. One staggered entrance, then it
    // settles and stays still so it can be read.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }
    // A slow breath behind the bell. It is the one ambient loop, and it earns its place: it is
    // what makes a waiting alert look live rather than like a screenshot.
    val ambient = rememberInfiniteTransition(label = "ambient")
    val glow by ambient.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.26f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    fun Modifier.entering(order: Int): Modifier = this.graphicsLayer {
        val shifted = ((entrance.value * 1.35f) - (order * 0.12f)).coerceIn(0f, 1f)
        alpha = shifted
        translationY = (1f - shifted) * 42f
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Depth, from the accent colour rather than from a grey. Strongest behind the
            // headline and gone by the time it reaches the buttons, so the eye starts high.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            0.45f to MaterialTheme.colorScheme.primary.copy(alpha = 0.03f),
                            1f to MaterialTheme.colorScheme.background
                        )
                    )
            )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
        ) {
            // The alarm face. It takes whatever height the words and buttons do not, so the
            // screen is never a block of content stranded above an empty half - on a tall phone
            // the rings simply breathe wider.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                AlertRings(modifier = Modifier.entering(0))
                AlertBell(
                    glow = glow,
                    isFollowUp = isFollowUp,
                    modifier = Modifier.entering(0)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                // Who and when, in that order, both quiet. The reminder is the headline; this is
                // the dateline above it.
                Text(
                    text = if (isFollowUp) {
                        stringResource(R.string.alert_followup_check)
                    } else {
                        sourceLabel
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.entering(1)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = scheduledText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.entering(1)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Left-aligned and large. Centring is what makes a wall of text hard to read, and
                // a reminder can be a full sentence.
                Text(
                    text = headline,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = MaterialTheme.typography.displaySmall.fontSize * 1.15f,
                    modifier = Modifier.entering(2)
                )

                if (!bodyText.equals(headline, ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = bodyText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.headlineSmall.fontSize * 1.35f,
                        modifier = Modifier.entering(3)
                    )
                }

                // Real information, kept: how insistent this reminder is going to be.
                if (reminder.followUpCheckMinutes > 0) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.alert_followup_repeats,
                            reminder.followUpCheckMinutes,
                            reminder.followUpCheckMinutes
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.entering(3)
                    )
                }

                // A readout rather than decoration: on exactly while sound is coming out.
                if (isPlaying) {
                    Spacer(modifier = Modifier.height(16.dp))
                    SpeakingIndicator()
                }
            }

            AlertActionDock(
                canPlayAgain = canPlayAgain,
                isPlaying = isPlaying,
                onDone = onDone,
                onStopPlayback = {
                    isPlaying = false
                    onStopPlayback()
                },
                onPlayAgain = onPlayAgain,
                onSnoozeFive = onSnoozeFive,
                onSnoozeTen = onSnoozeTen,
                modifier = Modifier.entering(4)
            )
        }
        }
    }
}

/**
 * Rings travelling outward from the bell, the way an alarm or an incoming call announces itself.
 *
 * They run for as long as the reminder is unanswered, which is the point: a reminder waiting for
 * you should not look like a screenshot. Each ring fades as it widens, so the motion reads as
 * sound leaving the phone rather than as a spinner.
 */
@Composable
private fun AlertRings(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "rings")
    val waves = listOf(0, 1200, 2400).map { delayMs ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(delayMs)
            ),
            label = "wave_$delayMs"
        )
    }

    Box(modifier = modifier.size(300.dp), contentAlignment = Alignment.Center) {
        waves.forEach { wave ->
            val progress = wave.value.sanitizeUnitFloat()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 0.28f + (progress * 0.72f)
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - progress).coerceIn(0f, 1f) * 0.55f
                    }
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
            )
        }
    }
}

/**
 * The mark at the top of the alert: a bell that rings once as the screen arrives, sitting in a
 * halo that breathes for as long as the reminder is unanswered.
 *
 * The ring is a single gesture, not a loop - a bell that never stops shaking reads as an
 * ornament, while one that strikes and settles reads as something that just happened.
 */
@Composable
private fun AlertBell(
    glow: Float,
    isFollowUp: Boolean,
    modifier: Modifier = Modifier
) {
    val swing = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        swing.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 900
                0f at 0
                -14f at 120
                12f at 260
                -8f at 400
                5f at 540
                -2f at 680
                0f at 900
            }
        )
    }

    Box(
        modifier = modifier.size(132.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = glow * 0.45f),
                    shape = CircleShape
                )
        )
        Surface(
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer { rotationZ = swing.value }
                )
            }
        }
    }
}

/**
 * Three bars keeping time with the audio. Shown only while something is actually playing, so its
 * presence is the status - there is no label to read and nothing moves once the phone is quiet.
 */
@Composable
private fun SpeakingIndicator() {
    val transition = rememberInfiniteTransition(label = "speaking")
    val heights = listOf(0, 180, 360).map { delayMs ->
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 620, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(delayMs)
            ),
            label = "bar_$delayMs"
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.height(18.dp)
        ) {
            heights.forEach { bar ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight(bar.value)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        }
        Text(
            text = stringResource(R.string.alert_playing_now),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun AlertActionDock(
    canPlayAgain: Boolean,
    isPlaying: Boolean,
    onDone: () -> Unit,
    onStopPlayback: () -> Unit,
    onPlayAgain: () -> Unit,
    onSnoozeFive: () -> Unit,
    onSnoozeTen: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Done is the only thing that ends the alert, so it is the only filled button and the
    // tallest target on the screen. Silence appears only while there is sound to silence.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp)
            .padding(top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (isPlaying) {
            OutlinedButton(
                onClick = onStopPlayback,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Icon(imageVector = Icons.Filled.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.alert_silence_now),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Replay gets its own row rather than a third of one: three buttons across wraps
        // "Snooze 5m" onto two lines on an ordinary phone.
        if (canPlayAgain && !isPlaying) {
            OutlinedButton(
                onClick = onPlayAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.alert_play_again),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SnoozeActionButton(
                label = stringResource(R.string.alert_snooze_minutes, 5),
                onClick = onSnoozeFive,
                modifier = Modifier.weight(1f)
            )
            SnoozeActionButton(
                label = stringResource(R.string.alert_snooze_minutes, 10),
                onClick = onSnoozeTen,
                modifier = Modifier.weight(1f)
            )
        }

        val donePressed = remember { MutableInteractionSource() }
        val isDonePressed by donePressed.collectIsPressedAsState()
        val doneScale by animateFloatAsState(
            targetValue = if (isDonePressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "done_scale"
        )
        Button(
            onClick = onDone,
            interactionSource = donePressed,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .graphicsLayer {
                    scaleX = doneScale
                    scaleY = doneScale
                },
            shape = RoundedCornerShape(20.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 3.dp,
                pressedElevation = 0.dp
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Done,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.alert_mark_done),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SnoozeActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}
