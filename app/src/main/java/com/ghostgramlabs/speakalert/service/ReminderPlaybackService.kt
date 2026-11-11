package com.ghostgramlabs.speakalert.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import com.ghostgramlabs.speakalert.MainActivity
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.alarm.ToneAlertPlayer
import com.ghostgramlabs.speakalert.alarm.ReminderActionReceiver
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.util.APP_DISPLAY_NAME
import com.ghostgramlabs.speakalert.util.FileLogger
import com.ghostgramlabs.speakalert.util.PrivateAudioRoute
import com.ghostgramlabs.speakalert.util.ReminderAudioSource
import com.ghostgramlabs.speakalert.util.TtsLanguageSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@androidx.annotation.OptIn(UnstableApi::class)
class ReminderPlaybackService : Service(), TextToSpeech.OnInitListener, SensorEventListener {

    // ExoPlayer
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private lateinit var playerNotificationManager: PlayerNotificationManager
    
    // TTS
    private var tts: TextToSpeech? = null
    private var pendingTtsText: String? = null
    private var isTtsInitialized = false
    private var ttsInitializationFailed = false
    private var isTtsMode = false
    // Every TextToSpeech call takes the engine's internal lock and most make a synchronous binder
    // call into the engine process, which can stall for seconds on a slow or cold engine (and
    // before onInit the setup task holds that lock the whole time). Doing any of that on the main
    // thread ANRs, so all engine calls go through this single thread, which also keeps them in
    // the order they were issued.
    private val ttsExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "tts-engine") }
    // Only touch the engine once it's ready — onInit applies the current audio attributes itself.
    private val readyTts: TextToSpeech?
        get() = if (isTtsInitialized) tts else null

    private fun onTtsThread(label: String, block: (TextToSpeech) -> Unit) {
        val engine = readyTts ?: return
        try {
            ttsExecutor.execute {
                try {
                    block(engine)
                } catch (e: Exception) {
                    FileLogger.logError("SERVICE", "TTS $label failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Service already destroyed.
        }
    }
    // Spoken-language preference: 0 = Auto-detect, 1 = Device language, 2 = English.
    @Volatile
    private var ttsLanguageMode = 0
    
    // For TTS replay
    private var currentTtsText: String? = null
    private var currentTtsTitle: String? = null
    private var currentTtsId: Long = -1L
    
    // Loop mode
    private var loopEnabled: Boolean = false

    // Audio Focus
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var pendingSpeakAfterFocusGain: String? = null
    private var privatePlaybackEnabled: Boolean = false
    private var privateRouteNearEar: Boolean = false
    private var dndBypassEnabled: Boolean = true
    private var originalAudioMode: Int? = null
    private var originalSpeakerphoneOn: Boolean? = null
    private var communicationDeviceSelected: Boolean = false
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityListening: Boolean = false
    
    // Volume Control
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playbackRequestJob: Job? = null
    private val ttsCallbacks = TtsCallbackGuard(scope,
        onDone = {
            if (loopEnabled && isTtsMode && currentTtsText != null) {
                performSpeak(currentTtsText!!)
            } else {
                broadcastPlaybackStatus(isPlaying = false)
                stopSelf()
            }
        },
        onError = {
            broadcastPlaybackStatus(isPlaying = false)
            stopSelf()
        }
    )
    private val playbackTimeout = PlaybackTimeout(
        scope = scope,
        readTimeoutMinutes = {
            try {
                (application as VoiceReminderApp).container.settingsRepository.loopTimeoutMinutes.first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.logError("SERVICE", "Failed to read loop timeout", e)
                0
            }
        },
        onTimeout = { minutes ->
            FileLogger.log("SERVICE: Loop timeout reached ($minutes minutes), auto-stopping")
            loopEnabled = false
            stopSelf()
        }
    )
    private var currentVolume: Float = 1.0f
    private var progressJob: Job? = null
    private var foregroundStarted = false
    private var startupPromotionFailed = false
    private val routeWarmupHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var routeWarmupRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastPlaybackStatus(isPlaying: Boolean) {
        val positionMs = if (!isTtsMode && ::player.isInitialized) {
            runCatching { player.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
        } else {
            0L
        }
        val durationMs = if (!isTtsMode && ::player.isInitialized) {
            runCatching { player.duration.takeIf { it > 0L } ?: 0L }.getOrDefault(0L)
        } else {
            0L
        }
        val statusIntent = Intent("ACTION_PLAYBACK_STATUS").apply {
            setPackage(packageName)
            putExtra("reminderId", currentReminderId)
            putExtra("isPlaying", isPlaying)
            putExtra("positionMs", positionMs)
            putExtra("durationMs", durationMs)
        }
        sendBroadcast(statusIntent)
    }

    private fun startProgressUpdates() {
        if (isTtsMode) return
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && ::player.isInitialized && player.isPlaying) {
                broadcastPlaybackStatus(isPlaying = true)
                delay(250)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    override fun onCreate() {
        super.onCreate()

        // The foreground-service deadline starts before onStartCommand(). Promote before
        // initializing ExoPlayer, MediaSession, TTS, sensors, or settings observers because
        // those can be slow on heavily customized Android devices.
        if (!promoteToForeground(createStartupNotification())) {
            startupPromotionFailed = true
            stopSelf()
            return
        }

        FileLogger.log("SERVICE: onCreate started")
        
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

            // Initialize ExoPlayer. Audio attributes are set when playback starts
            // because private playback uses phone-call style routing when enabled.
            player = ExoPlayer.Builder(this)
                .build()
                
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isTtsMode) return
                    if (currentReminderId != -1L) {
                        broadcastPlaybackStatus(isPlaying = isPlaying)
                    }
                    if (isPlaying) {
                        startProgressUpdates()
                    } else {
                        stopProgressUpdates()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (isTtsMode) return
                    if (playbackState == Player.STATE_ENDED) {
                        stopProgressUpdates()
                        FileLogger.log("SERVICE: ExoPlayer playback ended, loop=$loopEnabled")
                        if (loopEnabled) {
                            // Restart playback from beginning
                            player.seekTo(0)
                            player.play()
                        } else {

                            // User request: Playback from home is PREVIEW ONLY.
                            // Do NOT mark as done automatically. Just stop.
                            if (currentReminderId != -1L) {
                                FileLogger.log("SERVICE: Playback finished for $currentReminderId. Stopping service (no auto-complete).")
                                broadcastPlaybackStatus(isPlaying = false)
                            }
                            stopSelf()
                        }
                    }
                }
            })

            // MediaSession
            mediaSession = MediaSession.Builder(this, player).build()

            initPlayerNotificationManager()
            
            // Initialize TTS
            tts = TextToSpeech(this, this)
            
            // Observe Volume Setting
            try {
                val repository = (application as VoiceReminderApp).container.settingsRepository
                scope.launch {
                    repository.appVolume.collect { volume ->
                        currentVolume = volume
                        player.volume = volume
                        FileLogger.log("SERVICE: Volume updated to $volume")
                    }
                }
                scope.launch {
                    repository.ttsLanguageMode.collect { mode ->
                        ttsLanguageMode = mode
                    }
                }
            } catch (e: Exception) {
                 FileLogger.logError("SERVICE", "Failed to init settings observer", e)
            }
            
            FileLogger.log("SERVICE: onCreate completed")
        } catch (e: Exception) {
            FileLogger.logError("SERVICE", "Error in onCreate", e)
            throw e
        }
    }

    private fun initPlayerNotificationManager() {
        FileLogger.log("SERVICE: Initializing PlayerNotificationManager")
        playerNotificationManager = PlayerNotificationManager.Builder(
            this,
            NOTIFICATION_ID,
            CHANNEL_ID
        )
        .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): CharSequence {
                return player.currentMediaItem?.mediaMetadata?.title ?: "Reminder"
            }

            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                val intent = Intent(this@ReminderPlaybackService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                return PendingIntent.getActivity(
                    this@ReminderPlaybackService,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            override fun getCurrentContentText(player: Player): CharSequence? {
                val position = player.currentPosition / 1000
                val duration = player.duration / 1000
                return if (duration > 0) {
                    String.format("%d:%02d / %d:%02d", position / 60, position % 60, duration / 60, duration % 60)
                } else {
                    "Playing..."
                }
            }

            override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? = null
        })
        .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
            override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
                FileLogger.log("SERVICE: Notification posted (audio mode), ongoing=$ongoing")
                if (!isTtsMode && ongoing) {
                    if (!promoteToForeground(notification)) {
                        stopSelf()
                    }
                }
            }
            override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                FileLogger.log("SERVICE: Notification cancelled")
                if (!isTtsMode) stopSelf()
            }
        })
        .setSmallIconResourceId(R.drawable.ic_notification)
        .setCustomActionReceiver(object : PlayerNotificationManager.CustomActionReceiver {
            override fun createCustomActions(
                context: Context,
                instanceId: Int
            ): MutableMap<String, NotificationCompat.Action> {
                // Create Stop action
                val stopIntent = Intent(context, ReminderPlaybackService::class.java).apply {
                    action = ACTION_STOP
                }
                val stopPendingIntent = PendingIntent.getService(
                    context,
                    instanceId,
                    stopIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val stopAction = NotificationCompat.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Stop",
                    stopPendingIntent
                ).build()
                
                return mutableMapOf("stop" to stopAction)
            }
            
            override fun getCustomActions(player: Player): MutableList<String> {
                return mutableListOf("stop")
            }
            
            override fun onCustomAction(player: Player, action: String, intent: Intent) {
                if (action == "stop") {
                    FileLogger.log("SERVICE: Stop action from notification")
                    stopSelf()
                }
            }
        })
        .build()
        
        // Show play/pause and enable rewind/forward for seek
        playerNotificationManager.setUsePlayPauseActions(true)
        playerNotificationManager.setUseStopAction(true)
        
        playerNotificationManager.setPlayer(player)
        FileLogger.log("SERVICE: PlayerNotificationManager initialized")
    }

    private fun createStartupNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(APP_DISPLAY_NAME)
            .setContentText(getString(R.string.notif_playback_processing))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setDeleteIntent(stopPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Stops playback when the user swipes the playback notification away. From Android 14 a
     * foreground service's notification can be dismissed despite setOngoing, and without this the
     * reminder kept speaking with nothing left on screen to stop it.
     */
    private fun stopPendingIntent(): PendingIntent {
        val stopIntent = Intent(this, ReminderPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun promoteToForeground(notification: Notification): Boolean {
        return try {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            foregroundStarted = true
            true
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to promote playback service to foreground", exception)
            FileLogger.logError("SERVICE", "startForeground failed", exception)
            false
        }
    }

    private var currentReminderId: Long = -1L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.log("SERVICE: onStartCommand started")

        if (startupPromotionFailed) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        try {
            // Defensive retry for unusual lifecycle restoration paths. Normal starts are already
            // promoted at the beginning of onCreate().
            if (!foregroundStarted && !promoteToForeground(createStartupNotification())) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            // Capture reminderId for completion logic
            val reminderId = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
            if (intent?.action == ACTION_STOP_IF_MATCHING) {
                if (currentReminderId == -1L || currentReminderId == reminderId) {
                    stopProgressUpdates()
                    broadcastPlaybackStatus(isPlaying = false)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            if (reminderId != -1L) {
                currentReminderId = reminderId
                activeReminderId = reminderId
            }

            if (intent?.action == "ACTION_STOP_SERVICE" || intent?.action == ACTION_STOP) {
                FileLogger.log("SERVICE: Received STOP action")
                stopProgressUpdates()
                broadcastPlaybackStatus(isPlaying = false)
                stopSelf()
                return START_NOT_STICKY
            }

            if (intent?.action == ACTION_SEEK) {
                val seekPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, -1L)
                if (!isTtsMode && seekPositionMs >= 0L && ::player.isInitialized) {
                    player.seekTo(seekPositionMs)
                    broadcastPlaybackStatus(isPlaying = player.isPlaying)
                }
                return START_NOT_STICKY
            }

            if (reminderId != -1L) {
                broadcastPlaybackStatus(isPlaying = true)
            }
            
            if (intent?.action == ACTION_REPLAY) {
                FileLogger.log("SERVICE: Received REPLAY action")
                // Re-speak the current TTS text
                scope.launch {
                    privatePlaybackEnabled = readLatestPrivatePlaybackSetting(privatePlaybackEnabled)
                    configureAudioRoute(privatePlaybackEnabled)
                    currentTtsText?.let { text ->
                        if (isTtsInitialized) {
                            performSpeak(text)
                        }
                    }
                }
                return START_NOT_STICKY
            }

            val audioPath = intent?.getStringExtra(EXTRA_AUDIO_PATH)
            val ttsText = intent?.getStringExtra(EXTRA_TTS_TEXT)
            val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Reminder"
            val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
            playbackRequestJob?.cancel()
            ttsCallbacks.invalidate()
            pendingTtsText = null
            pendingSpeakAfterFocusGain = null
            routeWarmupRunnable?.let { routeWarmupHandler.removeCallbacks(it) }
            routeWarmupRunnable = null
            isTtsMode = audioPath == null && ttsText != null
            loopEnabled = intent?.getBooleanExtra(EXTRA_LOOP, false) ?: false
            playbackTimeout.restart(loopEnabled)
            val requestedPrivatePlayback = intent?.getBooleanExtra(EXTRA_PRIVATE_PLAYBACK, false) ?: false
            dndBypassEnabled = intent?.getBooleanExtra(EXTRA_DND_BYPASS, true) ?: true

            playbackRequestJob = scope.launch {
                // Recheck at playback time: an old notification may outlive its recording.
                if (id != -1L && (application as VoiceReminderApp).container.reminderRepository.getReminder(id) == null) {
                    broadcastPlaybackStatus(isPlaying = false)
                    stopSelf()
                    return@launch
                }
                privatePlaybackEnabled = readLatestPrivatePlaybackSetting(requestedPrivatePlayback)
                configureAudioRoute(privatePlaybackEnabled)

                FileLogger.log("SERVICE: Params - audio=$audioPath, tts=${ttsText?.take(20)}, title=$title, id=$id, loop=$loopEnabled, privatePlayback=$privatePlaybackEnabled")

                if (audioPath != null) {
                    isTtsMode = false
                    FileLogger.log("SERVICE: Playing audio")
                    playAudio(audioPath, title)
                } else if (ttsText != null) {
                    isTtsMode = true
                    FileLogger.log("SERVICE: Speaking TTS")
                    speakTts(ttsText, title, id)
                } else {
                    FileLogger.log("SERVICE: No audio or text provided, stopping")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            FileLogger.logError("SERVICE", "Error in onStartCommand", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private suspend fun readLatestPrivatePlaybackSetting(fallback: Boolean): Boolean {
        return try {
            (application as VoiceReminderApp).container.settingsRepository.privatePlaybackEnabled.first()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.logError("SERVICE", "Failed to read latest private playback setting", e)
            fallback
        }
    }

    private fun playAudio(path: String, title: String) {
        FileLogger.log("SERVICE: playAudio called with path=$path")
        try {
            ttsCallbacks.invalidate()
            pendingTtsText = null
            pendingSpeakAfterFocusGain = null
            onTtsThread("stop") { it.stop() }
            player.setAudioAttributes(buildPlayerAudioAttributes(), false)

            if (!ReminderAudioSource.isPlayable(this, path)) {
                FileLogger.log("SERVICE: Audio source unavailable: $path")
                stopSelf()
                return
            }

            val mediaItem = MediaItem.Builder()
                .setUri(ReminderAudioSource.toUri(path))
                .setMediaMetadata(
                     androidx.media3.common.MediaMetadata.Builder()
                         .setTitle(title)
                         .build()
                )
                .build()
            
            player.setMediaItem(mediaItem)
            player.prepare()
            runAfterPrivateRouteWarmup("audio") {
                if (::player.isInitialized) {
                    player.play()
                    FileLogger.log("SERVICE: ExoPlayer started")
                }
            }
        } catch (e: Exception) {
            FileLogger.logError("SERVICE", "Error playing audio", e)
            stopSelf()
        }
    }

    private fun speakTts(text: String, title: String, id: Long) {
        FileLogger.log("SERVICE: speakTts called with text='${text.take(30)}'")
        if (ttsInitializationFailed) {
            FileLogger.log("SERVICE: Cannot speak: TTS initialization failed")
            stopSelf()
            return
        }
        
        // Store for replay
        currentTtsText = text
        currentTtsTitle = title
        currentTtsId = id
        
        try {
            // A speech request replaces recorded playback in this single-player service.
            stopProgressUpdates()
            if (::player.isInitialized) player.stop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attributes = buildPlatformAudioAttributes()
                onTtsThread("setAudioAttributes") { it.setAudioAttributes(attributes) }
            }
            // Show TTS Notification
            FileLogger.log("SERVICE: Creating TTS notification")
            val notification = createTtsNotification(title, text, id)
            FileLogger.log("SERVICE: Calling startForeground for TTS")
            if (!promoteToForeground(notification)) {
                stopSelf()
                return
            }
            FileLogger.log("SERVICE: TTS foreground started")

            if (isTtsInitialized) {
                FileLogger.log("SERVICE: TTS already initialized, speaking")
                performSpeak(text)
            } else {
                FileLogger.log("SERVICE: TTS not initialized, queuing text")
                pendingTtsText = text
            }
        } catch (e: Exception) {
            FileLogger.logError("SERVICE", "Error in speakTts", e)
            stopSelf()
        }
    }

    private fun performSpeak(text: String) {
        FileLogger.log("SERVICE: performSpeak called")
        if (tts == null) {
            FileLogger.log("SERVICE: TTS is null!")
            stopSelf()
            return
        }
        
        val focusRequestResult = requestAudioFocus()
        if (focusRequestResult == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            FileLogger.log("SERVICE: Audio focus delayed, waiting for focus gain")
            pendingSpeakAfterFocusGain = text
            return
        }
        if (focusRequestResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Some Android 14 devices can reject focus transiently while still allowing TTS.
            // Do not kill playback flow; attempt speak as fallback.
            FileLogger.log("SERVICE: Audio focus not granted ($focusRequestResult), attempting TTS fallback")
        }

        speakAfterPrivateRouteWarmup(text)
    }

    private fun speakAfterPrivateRouteWarmup(text: String) {
        runAfterPrivateRouteWarmup("tts") {
            speakNow(text)
        }
    }

    private fun runAfterPrivateRouteWarmup(label: String, block: () -> Unit) {
        routeWarmupRunnable?.let { routeWarmupHandler.removeCallbacks(it) }
        routeWarmupRunnable = null

        if (!privatePlaybackEnabled || !privateRouteNearEar) {
            block()
            return
        }

        val runnable = Runnable {
            routeWarmupRunnable = null
            block()
        }
        routeWarmupRunnable = runnable
        FileLogger.log("SERVICE: Waiting ${PRIVATE_ROUTE_WARMUP_MS}ms for private $label route to settle")
        routeWarmupHandler.postDelayed(runnable, PRIVATE_ROUTE_WARMUP_MS)
    }

    private fun speakNow(text: String) {
        if (readyTts == null) {
            FileLogger.log("SERVICE: TTS not ready in speakNow")
            stopSelf()
            return
        }
        val params = android.os.Bundle()
        params.putInt(
            TextToSpeech.Engine.KEY_PARAM_STREAM,
            when {
                privatePlaybackEnabled && privateRouteNearEar -> AudioManager.STREAM_VOICE_CALL
                privatePlaybackEnabled -> AudioManager.STREAM_MUSIC
                else -> AudioManager.STREAM_ALARM
            }
        )
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume)
        
        val utteranceId = ttsCallbacks.begin()
        
        onTtsThread("speak") { engine ->
            // Pick the voice that matches the reminder's own language (with fallback) right before
            // speaking, so e.g. a Hindi reminder is read correctly even on an English phone.
            applyTtsLanguageForText(engine, text)
            FileLogger.log("SERVICE: Calling tts.speak()")
            val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            FileLogger.log("SERVICE: tts.speak() called, result=$speakResult")
            if (speakResult == TextToSpeech.ERROR) {
                scope.launch {
                    // A newer request may have replaced this utterance while it was queued.
                    if (!ttsCallbacks.isActive(utteranceId)) return@launch
                    FileLogger.log("SERVICE: tts.speak() failed with ERROR")
                    stopSelf()
                }
            }
        }
    }

    /**
     * Selects the TTS voice for [text]. Tries, in order: the language auto-detected from the text,
     * the device default, then English. If none has voice data installed, the engine keeps its own
     * default and a one-time hint is shown (no longer fails silently).
     */
    private fun applyTtsLanguageForText(engine: TextToSpeech, text: String) {
        // Honor the user's "Spoken language" preference; Auto-detect also keeps device/English as
        // graceful fallbacks.
        val detected = if (ttsLanguageMode == 0) TtsLanguageSupport.detectLocale(text) else null
        val candidates = LinkedHashSet<Locale>().apply {
            when (ttsLanguageMode) {
                1 -> { add(Locale.getDefault()); add(Locale.ENGLISH) }          // Device language
                2 -> { add(Locale.ENGLISH); add(Locale.getDefault()) }          // English
                else -> {                                                       // Auto-detect
                    detected?.let { add(it) }
                    add(Locale.getDefault())
                    add(Locale.ENGLISH)
                }
            }
        }
        for (locale in candidates) {
            val res = engine.setLanguage(locale)
            if (res >= TextToSpeech.LANG_AVAILABLE) {
                FileLogger.log("SERVICE: TTS language set to ${locale.toLanguageTag()} (res=$res)")
                return
            }
        }
        FileLogger.log("SERVICE: No TTS voice data for text language; using engine default")
        maybeShowVoiceDataHint(detected ?: Locale.getDefault())
    }

    /** Shows a single, non-blocking hint that voice data for [locale] should be installed. */
    private fun maybeShowVoiceDataHint(locale: Locale) {
        val prefs = getSharedPreferences("tts_hints", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("voice_hint_shown", false)) return
        prefs.edit().putBoolean("voice_hint_shown", true).apply()
        val strings = com.ghostgramlabs.speakalert.util.AppLocale.localizedContext(applicationContext)
        val language = locale.getDisplayLanguage(Locale.getDefault())
            .ifBlank { strings.getString(R.string.tts_voice_hint_this_language) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                applicationContext,
                strings.getString(R.string.tts_voice_hint, language),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestAudioFocus(): Int {
        FileLogger.log("SERVICE: Requesting audio focus")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = buildPlatformAudioAttributes()
                
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            pendingSpeakAfterFocusGain?.let { pendingText ->
                                FileLogger.log("SERVICE: Audio focus gained, resuming pending TTS")
                                pendingSpeakAfterFocusGain = null
                                speakAfterPrivateRouteWarmup(pendingText)
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            FileLogger.log("SERVICE: Audio focus lost")
                            onTtsThread("stop") { it.stop() }
                            stopSelf()
                        }
                    }
                }
                .build()
            
            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            FileLogger.log("SERVICE: Audio focus request result code: $result")
            return result
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { focusChange -> if (focusChange == AudioManager.AUDIOFOCUS_LOSS) stopSelf() },
                when {
                    privatePlaybackEnabled && privateRouteNearEar -> AudioManager.STREAM_VOICE_CALL
                    privatePlaybackEnabled -> AudioManager.STREAM_MUSIC
                    else -> AudioManager.STREAM_ALARM
                },
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            return result
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun configureAudioRoute(privatePlayback: Boolean) {
        if (!::audioManager.isInitialized) return
        if (privatePlayback) {
            if (originalAudioMode == null) {
                originalAudioMode = audioManager.mode
            }
            if (originalSpeakerphoneOn == null) {
                @Suppress("DEPRECATION")
                originalSpeakerphoneOn = audioManager.isSpeakerphoneOn
            }
            if (PrivateAudioRoute.hasExternalPrivateRoute(this)) {
                applyExternalPrivateRoute()
            } else {
                applyPrivatePlaybackRoute(useEarpiece = true)
                FileLogger.log("SERVICE: Private playback using phone earpiece fallback")
            }
        } else {
            applyPublicPlaybackRoute()
        }
    }

    private fun applyExternalPrivateRoute() {
        if (!::audioManager.isInitialized) return
        routeWarmupRunnable?.let { routeWarmupHandler.removeCallbacks(it) }
        routeWarmupRunnable = null
        stopProximityRouting()
        // Stay in MODE_NORMAL and use media attributes so Android's auto-routing
        // picks the connected external device (wired, BT A2DP, BLE Audio, hearing
        // aid). Works on all supported APIs and keeps music-quality routing for
        // A2DP instead of being forced down to SCO via setCommunicationDevice.
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        privateRouteNearEar = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceSelected) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        communicationDeviceSelected = false
        updatePlaybackAudioAttributes()
        FileLogger.log("SERVICE: Private playback routed to connected external device via media stream")
    }

    private fun applyPublicPlaybackRoute() {
        if (!::audioManager.isInitialized) return
        routeWarmupRunnable?.let { routeWarmupHandler.removeCallbacks(it) }
        routeWarmupRunnable = null
        stopProximityRouting()
        val playbackActive = (::player.isInitialized && player.isPlaying) || isTtsMode
        if (!playbackActive) {
            abandonAudioFocus()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        communicationDeviceSelected = false
        privateRouteNearEar = false
        originalAudioMode = null
        originalSpeakerphoneOn = null
        audioManager.mode = AudioManager.MODE_NORMAL
        val hasExternalMediaRoute = PrivateAudioRoute.hasExternalPrivateRoute(this)
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = !hasExternalMediaRoute
        updatePlaybackAudioAttributes()
        FileLogger.log("SERVICE: Public playback route restored to normal media routing; externalMediaRoute=$hasExternalMediaRoute")
    }

    private fun restoreAudioRoute() {
        if (!::audioManager.isInitialized) return
        stopProximityRouting()
        originalAudioMode?.let { mode ->
            audioManager.mode = mode
            originalAudioMode = null
        }
        originalSpeakerphoneOn?.let { wasSpeakerphoneOn ->
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = wasSpeakerphoneOn
            originalSpeakerphoneOn = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceSelected) {
            runCatching { audioManager.clearCommunicationDevice() }
            communicationDeviceSelected = false
        }
        privateRouteNearEar = false
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        updatePlaybackAudioAttributes()
    }

    private fun startProximityRouting() {
        if (proximityListening) return
        val sensor = proximitySensor
        if (sensor == null) {
            applyPrivatePlaybackRoute(useEarpiece = false)
            FileLogger.log("SERVICE: No proximity sensor; private playback stays on speaker")
            return
        }
        proximityListening = sensorManager?.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL
        ) == true
        FileLogger.log("SERVICE: Proximity routing listener registered=$proximityListening")
    }

    private fun stopProximityRouting() {
        if (!proximityListening) return
        sensorManager?.unregisterListener(this)
        proximityListening = false
    }

    private fun applyPrivatePlaybackRoute(useEarpiece: Boolean) {
        if (!::audioManager.isInitialized) return
        privateRouteNearEar = useEarpiece
        if (useEarpiece) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            audioManager.mode = originalAudioMode ?: AudioManager.MODE_NORMAL
        }
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = !useEarpiece
        updatePlaybackAudioAttributes()
        FileLogger.log("SERVICE: Private playback route=${if (useEarpiece) "earpiece" else "speaker"}")
    }


    override fun onSensorChanged(event: SensorEvent?) {
        if (!privatePlaybackEnabled || event?.sensor?.type != Sensor.TYPE_PROXIMITY) return
        val maxRange = event.sensor.maximumRange
        val distance = event.values.firstOrNull() ?: maxRange
        val nearEar = distance < maxRange
        if (nearEar != privateRouteNearEar) {
            applyPrivatePlaybackRoute(nearEar)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun buildPlayerAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(
                when {
                    privatePlaybackEnabled && privateRouteNearEar -> C.USAGE_VOICE_COMMUNICATION
                    privatePlaybackEnabled -> C.USAGE_MEDIA
                    else -> C.USAGE_ALARM
                }
            )
            .build()
    }

    private fun buildPlatformAudioAttributes(): android.media.AudioAttributes {
        return android.media.AudioAttributes.Builder()
            .setUsage(
                when {
                    privatePlaybackEnabled && privateRouteNearEar -> {
                        android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                    }
                    privatePlaybackEnabled -> {
                        android.media.AudioAttributes.USAGE_MEDIA
                    }
                    else -> {
                        android.media.AudioAttributes.USAGE_ALARM
                    }
                }
            )
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private fun updatePlaybackAudioAttributes() {
        if (::player.isInitialized) {
            runCatching {
                player.setAudioAttributes(buildPlayerAudioAttributes(), false)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val attributes = buildPlatformAudioAttributes()
            onTtsThread("setAudioAttributes") { it.setAudioAttributes(attributes) }
        }
    }
    
    private fun createTtsNotification(title: String, text: String, id: Long): Notification {
        FileLogger.log("SERVICE: Creating TTS notification")
        
        val stopPendingIntent = stopPendingIntent()

        // Replay action
        val replayIntent = Intent(this, ReminderPlaybackService::class.java).apply {
            action = ACTION_REPLAY
        }
        val replayPendingIntent = PendingIntent.getService(this, 2, replayIntent, PendingIntent.FLAG_IMMUTABLE)

        // Snooze action
        val snoozeIntent = Intent(this, ReminderActionReceiver::class.java).apply {
            action = "ACTION_SNOOZE"
            putExtra("reminderId", id)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            this, 1, snoozeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val strings = com.ghostgramlabs.speakalert.util.AppLocale.localizedContext(this)
        val spokenPreview = text.take(50) + if (text.length > 50) "…" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(strings.getString(R.string.notif_playback_speaking, spokenPreview))
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(android.R.drawable.ic_media_play, strings.getString(R.string.notif_action_replay), replayPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, strings.getString(R.string.notif_action_stop), stopPendingIntent)
            .addAction(android.R.drawable.ic_lock_idle_alarm, strings.getString(R.string.notif_action_snooze), snoozePendingIntent)
            .setOngoing(true)
            .setDeleteIntent(stopPendingIntent)
            .build()
    }

    override fun onInit(status: Int) {
        FileLogger.log("SERVICE: TTS onInit called with status=$status")
        if (status == TextToSpeech.SUCCESS) {
            ttsInitializationFailed = false
            isTtsInitialized = true
            val attributes = buildPlatformAudioAttributes()
            onTtsThread("init") { engine ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    engine.setAudioAttributes(attributes)
                }
                // Best-effort initial language. The actual TTS voice is chosen per reminder text at
                // speak time (with a fallback chain), so an unavailable device-default language must
                // NOT abort playback here — that was the old "fails silently" behaviour.
                engine.setLanguage(Locale.getDefault())
            }
            FileLogger.log("SERVICE: TTS initialized successfully")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    FileLogger.log("SERVICE: TTS utterance started")
                }
                override fun onDone(utteranceId: String?) {
                    FileLogger.log("SERVICE: TTS utterance done, loop=$loopEnabled")
                    ttsCallbacks.complete(utteranceId)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    FileLogger.log("SERVICE: TTS utterance error")
                    ttsCallbacks.complete(utteranceId, failed = true)
                }
            })

            pendingTtsText?.let {
                FileLogger.log("SERVICE: Speaking pending TTS text")
                performSpeak(it)
                pendingTtsText = null
            }
        } else {
            FileLogger.log("SERVICE: TTS initialization failed with status=$status")
            isTtsInitialized = false
            ttsInitializationFailed = true
            // Recorded audio has no dependency on the speech engine. An initialization
            // failure must only terminate a request that is actually using TTS.
            if (isTtsMode) {
                pendingTtsText = null
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        FileLogger.log("SERVICE: onDestroy called")
        ttsCallbacks.invalidate()
        try {
            stopProgressUpdates()
            // Ensure UI always gets a terminal "not playing" signal.
            broadcastPlaybackStatus(isPlaying = false)

            // Cancel loop timeout if active
            playbackTimeout.cancel()
            pendingSpeakAfterFocusGain = null
            routeWarmupRunnable?.let { routeWarmupHandler.removeCallbacks(it) }
            routeWarmupRunnable = null
            
            mediaSession?.release()
            mediaSession = null
            playerNotificationManager.setPlayer(null)
            player.release()
            
            pendingTtsText = null
            val engine = tts
            val engineReady = isTtsInitialized
            tts = null
            isTtsInitialized = false
            if (engine != null) {
                // Off the main thread: both calls wait on the engine (and, while it is still
                // connecting, on the setup task's lock). Queued after any pending engine work.
                runCatching {
                    ttsExecutor.execute {
                        runCatching {
                            if (engineReady) engine.stop()
                            engine.shutdown()
                        }
                    }
                }
            }
            ttsExecutor.shutdown()
            abandonAudioFocus()
            restoreAudioRoute()
        } catch (e: Exception) {
            FileLogger.logError("SERVICE", "Error in onDestroy", e)
        }
        
        super.onDestroy()
        scope.cancel()
        FileLogger.log("SERVICE: onDestroy completed")
        activeReminderId = -1L
    }

    companion object {
        private const val TAG = "ReminderPlaybackSvc"
        const val CHANNEL_ID = "playback_channel"
        // Reserve a high fixed ID so it never collides with reminder alert IDs (which use reminderId.toInt()).
        const val NOTIFICATION_ID = 2_000_001
        
        const val EXTRA_ID = "extra_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_AUDIO_PATH = "extra_audio_path"
        const val EXTRA_TTS_TEXT = "extra_tts_text"
        
        const val ACTION_STOP = "action_stop"
        private const val ACTION_STOP_IF_MATCHING = "stop_if_matching_reminder"
        @Volatile private var activeReminderId = -1L
        const val ACTION_REPLAY = "action_replay"
        const val ACTION_SEEK = "action_seek"
        const val EXTRA_LOOP = "extra_loop"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_PRIVATE_PLAYBACK = "extra_private_playback"
        const val EXTRA_DND_BYPASS = "extra_dnd_bypass"
        private const val PRIVATE_ROUTE_WARMUP_MS = 350L
        
        /** Returns launch acceptance only; decoding and speech initialization happen asynchronously. */
        fun start(
            context: Context,
            id: Long,
            title: String?,
            audioPath: String?,
            ttsText: String?,
            loop: Boolean = false,
            isFromBootContext: Boolean = false,
            privatePlayback: Boolean = false,
            dndBypass: Boolean = true
        ): Boolean {
            FileLogger.log("SERVICE.start() called - id=$id, audio=$audioPath, tts=${ttsText?.take(20)}, loop=$loop, bootContext=$isFromBootContext")
            ToneAlertPlayer.stop()

            // ANDROID 15+ GUARD: BOOT_COMPLETED receivers CANNOT start
            // mediaPlayback foreground services. If this call originates from
            // a boot-rescheduled alarm, unconditionally skip on Android 15+.
            if (isFromBootContext && Build.VERSION.SDK_INT >= 35) {
                FileLogger.log("SERVICE.start() - SKIPPED: Android 15+ boot context restriction. Notification-only fallback.")
                return false
            }

            val intent = Intent(context, ReminderPlaybackService::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_LOOP, loop)
                putExtra(EXTRA_PRIVATE_PLAYBACK, privatePlayback)
                putExtra(EXTRA_DND_BYPASS, dndBypass)
                if (audioPath != null) putExtra(EXTRA_AUDIO_PATH, audioPath)
                if (ttsText != null) putExtra(EXTRA_TTS_TEXT, ttsText)
            }
            return try {
                // minSdk is 26, so every supported device uses a foreground service launch.
                val component = context.startForegroundService(intent)
                val accepted = component != null
                FileLogger.log("SERVICE.start() - playback launch accepted=$accepted")
                accepted
            } catch (e: Exception) {
                // Catch ForegroundServiceStartNotAllowedException (Android 12+) and others
                FileLogger.logError("SERVICE.start()", "Failed to start service", e)
                // We do NOT rethrow here, because that crashes the app.
                // The caller falls through to show a notification-only experience.
                false
            }
        }
        
        fun stopIfPlaying(context: Context, reminderId: Long) {
            if (activeReminderId != reminderId) return
            runCatching {
                context.startService(Intent(context, ReminderPlaybackService::class.java)
                    .setAction(ACTION_STOP_IF_MATCHING).putExtra(EXTRA_ID, reminderId))
            }.onFailure { FileLogger.logError("SERVICE", "Could not stop expired reminder", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ReminderPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                FileLogger.logError("SERVICE.stop()", "Failed to request service stop", e)
            }
        }

        fun seek(context: Context, positionMs: Long) {
            val intent = Intent(context, ReminderPlaybackService::class.java).apply {
                action = ACTION_SEEK
                putExtra(EXTRA_POSITION_MS, positionMs)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                FileLogger.logError("SERVICE.seek()", "Failed to request seek", e)
            }
        }
    }
}
