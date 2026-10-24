package com.ghostgramlabs.speakalert.ui.addedit

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghostgramlabs.speakalert.alarm.AlarmScheduler
import com.ghostgramlabs.speakalert.audio.AudioEnhancer
import com.ghostgramlabs.speakalert.audio.AudioRecorder
import com.ghostgramlabs.speakalert.audio.AudioPlayer
import com.ghostgramlabs.speakalert.audio.Mp4AudioEnhancer
import com.ghostgramlabs.speakalert.audio.RecordingOutcome
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.repository.ReminderRepository
import com.ghostgramlabs.speakalert.domain.RecurrenceUtils
import com.ghostgramlabs.speakalert.domain.models.MissedPolicy
import com.ghostgramlabs.speakalert.domain.models.RecurrenceModel
import com.ghostgramlabs.speakalert.domain.models.RecurrenceType
import com.ghostgramlabs.speakalert.domain.models.EndRuleType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import java.util.UUID
import com.ghostgramlabs.speakalert.data.repository.SettingsRepository
import com.ghostgramlabs.speakalert.util.ReminderAudioSource
import com.ghostgramlabs.speakalert.util.sanitizeUnitFloat

/**
 * One-shot notice about the take that just finished. Only raised when there is something the
 * user can actually do about it - a recording that merely came out quiet is left alone.
 */
enum class RecordingIssue {
    /** Released the button before the encoder had a frame to work with. */
    TOO_SHORT,
    /** The device refused to record at all. */
    CAPTURE_FAILED,
    IMPORT_FAILED,
    /** A file was written but the microphone delivered nothing for the whole take. */
    SILENT
}

data class AddEditUiState(
    val initialReminderId: Long = -1L,
    val title: String = "",
    val reminderText: String = "",
    val isRecording: Boolean = false,
    val isImporting: Boolean = false,
    val isPlaying: Boolean = false,
    val recordedAudioPath: String? = null,
    val isCustomAudioFile: Boolean = false,
    val customAudioFileName: String? = null,
    val triggerTime: Long = com.ghostgramlabs.speakalert.util.DateUtils.normalizeToMinute(System.currentTimeMillis() + 10 * 60 * 1000),
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val recurrenceJson: String? = null,
    val loopPlayback: Boolean = false,
    val followUpCheckMinutes: Int = 0,
    val showError: Boolean = false,
    val playbackProgress: Float = 0f,
    val isSaving: Boolean = false,
    val saveCompleted: Boolean = false,
    val recordingElapsedSeconds: Int = 0, // For displaying recording time
    val currentAmplitude: Int = 0, // For waveform visualization
    val recordingIssue: RecordingIssue? = null,
    val isTextToSpeechEnabled: Boolean = true,
    val showVoiceRecordingSection: Boolean = true,
    val showAudioFileSection: Boolean = true,
    val showTypedReminderSection: Boolean = true,
    val showShortLabelSection: Boolean = true,
    val showPastTimeError: Boolean = false,
    val hasUnsavedChanges: Boolean = false
)

class AddEditViewModel(
    private val repository: ReminderRepository,
    private val scheduler: AlarmScheduler,
    private val settingsRepository: SettingsRepository,
    context: Context,
    private val recorder: AudioRecorder,
    private val player: AudioPlayer,
    private val enhancer: AudioEnhancer = Mp4AudioEnhancer(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Outlives the editor. Null in tests, where the view model's own scope is enough.
    private val backgroundScope: kotlinx.coroutines.CoroutineScope? = null,
    private val missedRepository: com.ghostgramlabs.speakalert.data.repository.MissedReminderRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddEditUiState())
    val uiState = _uiState.asStateFlow()
    private val experimentalVoiceEnhancementEnabled =
        settingsRepository.experimentalVoiceEnhancementEnabled.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            false
        )
    private val appContext = context
    
    // Audio
    private val audioDir = File(context.filesDir, "reminders").apply { mkdirs() }
    private val tempDir = File(context.cacheDir, "temp_recordings").apply { mkdirs() }
    // recorder and player are now injected properties
    private var tempAudioFile: File? = null
    private var playbackJob: kotlinx.coroutines.Job? = null
    private var recordingTimerJob: kotlinx.coroutines.Job? = null
    private var enhancementJob: Job? = null
    private var currentRecordingUsesExperimentalBehavior = false
    private val detachedTempFiles = java.util.Collections.synchronizedSet(mutableSetOf<File>())
    private var savedDraft: ReminderDraft? = null
    private var importedShakeDraftId: String? = null
    private var shakeImportAttempted = false
    
    companion object {
        const val MAX_RECORDING_SECONDS = 5 * 60 // 5 minutes
    }

    init {
        player.onCompletion = {
            stopPlayback()
        }
        
        // Observe Volume
        viewModelScope.launch {
            settingsRepository.appVolume.collect { volume ->
                player.setVolume(volume)
            }
        }
        
        // Observe TTS Setting
        viewModelScope.launch {
            settingsRepository.speakTextIfNoVoice.collect { enabled ->
                _uiState.value = _uiState.value.copy(isTextToSpeechEnabled = enabled)
            }
        }

        viewModelScope.launch {
            combine(
                settingsRepository.showVoiceRecordingSection,
                settingsRepository.showAudioFileSection,
                settingsRepository.showTypedReminderSection,
                settingsRepository.showShortLabelSection
            ) { voice, audioFile, typed, shortLabel ->
                AddEditSectionVisibility(
                    voiceRecording = voice,
                    audioFile = audioFile,
                    typedReminder = typed,
                    shortLabel = shortLabel
                )
            }.collect { visibility ->
                _uiState.value = _uiState.value.copy(
                    showVoiceRecordingSection = visibility.voiceRecording,
                    showAudioFileSection = visibility.audioFile,
                    showTypedReminderSection = visibility.typedReminder,
                    showShortLabelSection = visibility.shortLabel
                )
            }
        }
    }


    /**
     * Pre-populate fields for a brand-new reminder from user-level defaults.
     * Should be called only when reminderId == -1L. Has no effect on existing reminders.
     */
    fun applyDefaultsForNewReminder(shakeDraftId: String? = null) {
        // Configuration changes must not replace an edited take with the original again.
        if (shakeDraftId != null && shakeImportAttempted) return
        if (shakeDraftId != null) shakeImportAttempted = true
        if (shakeDraftId != null) _uiState.value = _uiState.value.copy(isImporting = true)
        viewModelScope.launch {
            val defaultFollowUp = settingsRepository.defaultFollowUpMinutes.first()
            // Only apply if state hasn't already been loaded into edit mode
            if (_uiState.value.initialReminderId == -1L) {
                setSavedDraft(_uiState.value.copy(followUpCheckMinutes = defaultFollowUp))
                if (shakeDraftId != null) {
                    val copy = File(tempDir, "temp_${UUID.randomUUID()}.m4a")
                    try {
                        withContext(ioDispatcher) {
                            val original = com.ghostgramlabs.speakalert.shake.ShakeDraftStore(appContext.filesDir)
                                .find(shakeDraftId) ?: error("Recording no longer exists")
                            original.copyTo(copy)
                        }
                        tempAudioFile = copy
                        importedShakeDraftId = shakeDraftId
                        setDraftState(_uiState.value.copy(
                            recordedAudioPath = copy.absolutePath,
                            triggerTime = System.currentTimeMillis() + com.ghostgramlabs.speakalert.shake.ShakeReminderCreator.DEFAULT_DELAY_MS
                        ))
                    } catch (cancelled: CancellationException) {
                        copy.delete()
                        throw cancelled
                    } catch (_: Exception) {
                        copy.delete()
                        _uiState.value = _uiState.value.copy(recordingIssue = RecordingIssue.IMPORT_FAILED)
                    } finally {
                        _uiState.value = _uiState.value.copy(isImporting = false)
                    }
                }
            }
        }
    }

    fun loadReminder(id: Long) {
        viewModelScope.launch {
            val reminder = repository.getReminder(id)
            if (reminder != null) {
                val isCustomAudio = ReminderAudioSource.isContentUri(reminder.audioPath)
                setSavedDraft(_uiState.value.copy(
                    initialReminderId = reminder.id,
                    title = reminder.title ?: "",
                    reminderText = reminder.reminderText ?: "",
                    recordedAudioPath = reminder.audioPath,
                    isCustomAudioFile = isCustomAudio,
                    customAudioFileName = if (isCustomAudio) {
                        ReminderAudioSource.resolveDisplayName(appContext, reminder.audioPath)
                    } else {
                        null
                    },
                    triggerTime = reminder.nextTriggerAt,
                    recurrenceType = reminder.recurrenceType,
                    recurrenceJson = reminder.recurrenceJson,
                    loopPlayback = reminder.loopPlayback,
                    followUpCheckMinutes = reminder.followUpCheckMinutes
                ))
            }
        }
    }

    fun updateTitle(newTitle: String) {
        setDraftState(_uiState.value.copy(title = newTitle))
    }

    fun updateReminderText(newText: String) {
        setDraftState(_uiState.value.copy(reminderText = newText, showError = false))
    }

    fun startRecording() {
        if (_uiState.value.isImporting) return
        if (_uiState.value.isRecording) return

        // Stop any playback first
        stopPlayback()

        // Record to TEMP file first (not final location)
        val fileName = "temp_${UUID.randomUUID()}.m4a"
        val file = File(tempDir, fileName)
        currentRecordingUsesExperimentalBehavior = experimentalVoiceEnhancementEnabled.value
        
        try {
            recorder.start(file, currentRecordingUsesExperimentalBehavior)
            // Keep the existing take if the shake service owns the microphone or capture fails.
            // Only discard it once its replacement has actually started.
            detachAndDeleteTempRecording()
            tempAudioFile = file
            setDraftState(_uiState.value.copy(
                isRecording = true, 
                recordedAudioPath = null, 
                isCustomAudioFile = false,
                customAudioFileName = null,
                showError = false,
                recordingElapsedSeconds = 0,
                currentAmplitude = 0,
                recordingIssue = null
            ))
            
            // Start timer to track elapsed time and poll amplitude
            recordingTimerJob?.cancel()
            recordingTimerJob = viewModelScope.launch {
                var elapsedMs = 0L
                while (_uiState.value.isRecording && elapsedMs < MAX_RECORDING_SECONDS * 1000L) {
                    kotlinx.coroutines.delay(100) // Poll every 100ms for smoother wave
                    elapsedMs += 100

                    val amplitude = recorder.getMaxAmplitude()
                    val seconds = (elapsedMs / 1000).toInt()

                    _uiState.value = _uiState.value.copy(
                        recordingElapsedSeconds = seconds,
                        currentAmplitude = amplitude
                    )
                }
                // Auto-stop if max reached
                if (elapsedMs >= MAX_RECORDING_SECONDS * 1000L && _uiState.value.isRecording) {
                    stopRecording()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            file.delete()
            _uiState.value = _uiState.value.copy(
                isRecording = false,
                recordingIssue = RecordingIssue.CAPTURE_FAILED
            )
            currentRecordingUsesExperimentalBehavior = false
        }
    }

    /**
     * Stop recording and transition to Preview mode.
     * The app stays on this screen - does NOT navigate away.
     */
    fun stopRecording() {
        recordingTimerJob?.cancel()
        val useExperimentalBehavior = currentRecordingUsesExperimentalBehavior
        currentRecordingUsesExperimentalBehavior = false
        val outcome = try {
            recorder.stop()
        } catch (e: Exception) {
            e.printStackTrace()
            RecordingOutcome.NOTHING
        }

        if (!useExperimentalBehavior) {
            // Exact audio behavior from 300df35: accept the raw recorder file without silence
            // classification, warnings, normalization, or codec post-processing.
            setDraftState(_uiState.value.copy(
                isRecording = false,
                recordedAudioPath = tempAudioFile?.absolutePath,
                isCustomAudioFile = false,
                customAudioFileName = null,
                recordingIssue = null
            ))
            return
        }

        // Nothing usable on disk: stay in the pre-record state instead of handing the player an
        // empty file. A quick tap is the user's own doing, not a malfunction, so say so
        // differently from a device that genuinely refused to record.
        if (outcome.file == null) {
            val releasedEarly = _uiState.value.recordingElapsedSeconds < 1
            tempAudioFile?.delete()
            tempAudioFile = null
            setDraftState(_uiState.value.copy(
                isRecording = false,
                recordedAudioPath = null,
                isCustomAudioFile = false,
                customAudioFileName = null,
                recordingIssue = if (releasedEarly) {
                    RecordingIssue.TOO_SHORT
                } else {
                    RecordingIssue.CAPTURE_FAILED
                }
            ))
            return
        }

        // A successfully finalized file belongs to the user, even when the device reports no
        // measurable level. Keep it available for Preview and Save; the warning is informational.
        setDraftState(_uiState.value.copy(
            isRecording = false,
            recordedAudioPath = tempAudioFile?.absolutePath,
            isCustomAudioFile = false,
            customAudioFileName = null,
            recordingIssue = if (outcome.isSilent) RecordingIssue.SILENT else null
        ))

        // Clean up the take in the background. It swaps the file in place under the same path,
        // so the preview is usable straight away and nothing on screen has to wait or move.
        // Do not normalize a silent take: preserving the exact captured file is safer than
        // turning its noise floor into audible sound.
        if (!outcome.isSilent) enhanceRecording(outcome.file)
    }

    /**
     * Applies the high-pass and loudness pass off the main thread. Failure is not worth telling
     * the user about: the original recording is still there and still plays.
     */
    private fun enhanceRecording(file: File) {
        enhancementJob = viewModelScope.launch(ioDispatcher) {
            // MIC capture, validation, and short-tap protection stay enabled on every device.
            // Only the codec/DSP pass is experimental and therefore explicitly opt-in.
            runCatching { enhancer.enhance(file) }
        }
    }

    /**
     * Cleans up a recording that was saved before its enhancement finished. Runs outside the
     * editor, which closes straight after saving. If the reminder is deleted or its audio
     * replaced first, the enhancer finds the file gone and discards its work.
     */
    private fun enhanceSavedRecording(file: File) {
        val path = file.absolutePath
        com.ghostgramlabs.speakalert.audio.AudioCleanupTracker.started(path)
        (backgroundScope ?: viewModelScope).launch(ioDispatcher) {
            try {
                runCatching { enhancer.enhance(file) }
            } finally {
                com.ghostgramlabs.speakalert.audio.AudioCleanupTracker.finished(path)
            }
        }
    }

    /**
     * Detach the current temporary take immediately, then delete it after any codec work that
     * already owns it has returned. This keeps Cancel/Retake responsive without leaving an
     * enhancer able to recreate an abandoned file.
     */
    private fun detachAndDeleteTempRecording() {
        // Replacing the imported take must not consume the original inbox recording.
        importedShakeDraftId = null
        val file = tempAudioFile ?: return
        val pendingEnhancement = enhancementJob
        tempAudioFile = null
        enhancementJob = null
        detachedTempFiles.add(file)
        viewModelScope.launch(ioDispatcher) {
            try {
                pendingEnhancement?.join()
            } finally {
                file.delete()
                File(file.parentFile, file.name + ".enhanced").delete()
                detachedTempFiles.remove(file)
            }
        }
    }

    /** Clears a one-shot recording warning after the UI has shown it. */
    fun clearRecordingIssue() {
        if (_uiState.value.recordingIssue != null) {
            _uiState.value = _uiState.value.copy(recordingIssue = null)
        }
    }
    
    /**
     * Cancel/delete the current recording.
     */
    fun cancelRecording() {
        stopPlayback()
        detachAndDeleteTempRecording()
        setDraftState(_uiState.value.copy(
            isRecording = false,
            recordedAudioPath = null,
            isCustomAudioFile = false,
            customAudioFileName = null
        ))
    }

    /**
     * Use a persisted content URI selected from system picker as reminder audio.
     */
    fun setCustomAudio(uriString: String, displayName: String?) {
        if (_uiState.value.isRecording) {
            recordingTimerJob?.cancel()
            recorder.stop()
        }
        stopPlayback()
        detachAndDeleteTempRecording()
        setDraftState(_uiState.value.copy(
            recordedAudioPath = uriString,
            isCustomAudioFile = true,
            customAudioFileName = displayName ?: ReminderAudioSource.resolveDisplayName(appContext, uriString),
            isRecording = false,
            showError = false
        ))
    }

    fun removeCustomAudio() {
        if (!_uiState.value.isCustomAudioFile) return
        stopPlayback()
        setDraftState(_uiState.value.copy(
            recordedAudioPath = null,
            isCustomAudioFile = false,
            customAudioFileName = null
        ))
    }
    
    fun playRecording() {
        val path = _uiState.value.recordedAudioPath ?: return
        if (!ReminderAudioSource.isPlayable(appContext, path)) {
            _uiState.value = _uiState.value.copy(isPlaying = false)
            return
        }
        stopPlayback()
        _uiState.value = _uiState.value.copy(isPlaying = true)
        if (_uiState.value.isCustomAudioFile || ReminderAudioSource.isContentUri(path)) {
            player.playUri(Uri.parse(path))
        } else {
            player.playFile(File(path))
        }
        startPlaybackTracking()
    }
    
    fun stopPlayback() {
        playbackJob?.cancel()
        player.stop()
        _uiState.value = _uiState.value.copy(isPlaying = false, playbackProgress = 0f)
    }

    fun seekTo(progress: Float) {
        if (_uiState.value.isPlaying) {
            val duration = player.getDuration()
            val position = (duration * progress.sanitizeUnitFloat()).toInt()
            player.seekTo(position)
        }
    }

    private fun startPlaybackTracking() {
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            while (isActive) {
                if (player.isPlaying()) {
                    val duration = player.getDuration()
                    val position = player.getCurrentPosition()
                    if (duration > 0) {
                        val progress = (position.toFloat() / duration.toFloat()).sanitizeUnitFloat()
                        _uiState.value = _uiState.value.copy(playbackProgress = progress)
                    }
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }
    
    fun setTriggerTime(time: Long) {
        val state = _uiState.value
        val clampedJson = clampRecurrenceEndRuleJson(
            type = state.recurrenceType,
            json = state.recurrenceJson,
            minEndDateTimeMillis = time
        )
        setDraftState(state.copy(triggerTime = time, recurrenceJson = clampedJson, showPastTimeError = false))
    }
    
    fun setRecurrence(type: RecurrenceType, json: String? = null) {
        setDraftState(_uiState.value.copy(recurrenceType = type, recurrenceJson = json))
    }

    fun setRecurrence(model: RecurrenceModel?) {
        if (model == null) {
            setRecurrence(RecurrenceType.NONE, null)
            return
        }
        val type = when (model) {
            is RecurrenceModel.Daily -> RecurrenceType.DAILY
            is RecurrenceModel.Weekly -> RecurrenceType.WEEKLY
            is RecurrenceModel.Monthly -> RecurrenceType.MONTHLY
            is RecurrenceModel.Yearly -> RecurrenceType.YEARLY
            is RecurrenceModel.Custom -> RecurrenceType.CUSTOM
        }
        val clampedModel = clampRecurrenceEndRule(model, _uiState.value.triggerTime)
        val json = RecurrenceUtils.toJson(clampedModel)
        setRecurrence(type, json)
    }
    
    fun setLoopPlayback(enabled: Boolean) {
        setDraftState(_uiState.value.copy(loopPlayback = enabled))
    }

    fun setFollowUpCheckMinutes(minutes: Int) {
        setDraftState(_uiState.value.copy(followUpCheckMinutes = minutes.coerceAtLeast(0)))
    }

    /**
     * Save the reminder. This is async - observe saveCompleted to know when done.
     * On success: moves temp file to final location, saves to DB, sets saveCompleted=true
     * On validation error: sets showError=true, stays on screen
     */
    fun saveReminder() {
        if (_uiState.value.isImporting) return
        // Saving during a take means "I am done speaking", not "discard this". Finish the
        // recording first so the take becomes the reminder's audio; otherwise the in-progress
        // recording has no path yet and the save fails the audio-or-text check, which reads as
        // being asked for a message while visibly recording one.
        if (_uiState.value.isRecording) stopRecording()

        val state = _uiState.value

        // The screen stays up briefly after a successful save, so a save in flight or already
        // completed must not write the reminder again. Checked and marked synchronously so two
        // quick taps cannot both get past it.
        if (state.isSaving || state.saveCompleted) return

        // VALIDATION: Must have Audio OR Text
        val hasAudio = !state.recordedAudioPath.isNullOrBlank()
        val hasText = state.reminderText.isNotBlank()

        if (!hasAudio && !hasText) {
            _uiState.value = _uiState.value.copy(showError = true)
            return
        }

        // Start saving - clear errors first
        _uiState.value = _uiState.value.copy(showError = false, showPastTimeError = false)

        // VALIDATION: Time must be in future for non-recurring
        if (state.recurrenceType == RecurrenceType.NONE && state.triggerTime < System.currentTimeMillis()) {
            _uiState.value = _uiState.value.copy(showPastTimeError = true)
            return
        }

        _uiState.value = _uiState.value.copy(isSaving = true)

        viewModelScope.launch {
            // Final copy of a new take that no stored reminder references yet.
            var unreferencedAudioFile: File? = null
            // Set once the reminder row is written, so a retry updates it instead of inserting.
            var savedId: Long? = null
            var finalAudioPath = state.recordedAudioPath
            try {
                // Enhancement decodes and re-encodes the whole take, so it takes longer the longer
                // the recording, and Save used to wait for it. Now Save stores the take as it is
                // and, if the cleanup had not finished, runs it again on the saved copy in the
                // background. The enhancer swaps files atomically, so the copy below reads either
                // the raw or the finished take, never a partial one, and a reminder that fires
                // before the cleanup finishes simply plays the raw take.
                val enhancementPending = enhancementJob?.isActive == true

                // Copy the temp take to its final location. The temp file is kept until the
                // database write succeeds, so a failed save can be retried with the same audio.
                val sourceAudioFile = tempAudioFile
                if (sourceAudioFile != null) {
                    val finalFile = withContext(ioDispatcher) {
                        File(audioDir, "${UUID.randomUUID()}.m4a").also {
                            sourceAudioFile.copyTo(it, overwrite = true)
                        }
                    }
                    unreferencedAudioFile = finalFile
                    finalAudioPath = finalFile.absolutePath
                }

                // Auto-align recurring reminders to their rule
                var finalTriggerTime = state.triggerTime
                if (state.recurrenceType != RecurrenceType.NONE) {
                    val tempReminder = ReminderEntity(
                        id = 0,
                        title = "",
                        nextTriggerAt = finalTriggerTime,
                        recurrenceType = state.recurrenceType,
                        recurrenceJson = state.recurrenceJson
                    )
                    // Preserve selected first day when valid:
                    // - if selected time is future, align from selected-1ms (so exact selected is allowed)
                    // - if selected time is past, align from now
                    val now = System.currentTimeMillis()
                    val alignmentBase = if (finalTriggerTime > now) finalTriggerTime - 1L else now
                    val alignedTime = RecurrenceUtils.computeNextTrigger(tempReminder, alignmentBase)
                    if (alignedTime != null) {
                        finalTriggerTime = alignedTime
                        com.ghostgramlabs.speakalert.util.FileLogger.log(
                            "AddEdit: Aligned recurring reminder to $finalTriggerTime (base=$alignmentBase)"
                        )
                    }
                }

                // Only save user-provided title; display layer handles fallbacks
                val smartLabel = state.title.ifBlank { null }
                val settingsDefaultMissedPolicy = parseMissedPolicy(settingsRepository.defaultMissedPolicy.first())
                val recurrenceJson = clampRecurrenceEndRuleJson(
                    type = state.recurrenceType,
                    json = state.recurrenceJson,
                    minEndDateTimeMillis = finalTriggerTime
                )
                val recurrenceModel = RecurrenceUtils.fromJson(state.recurrenceType, recurrenceJson)
                val resolvedMissedPolicy = recurrenceModel?.missedPolicy?.takeIf {
                    it != MissedPolicy.SKIP_TO_NEXT || settingsDefaultMissedPolicy == MissedPolicy.SKIP_TO_NEXT
                } ?: settingsDefaultMissedPolicy

                val reminder = ReminderEntity(
                    id = if (state.initialReminderId != -1L) state.initialReminderId else 0L,
                    title = smartLabel,
                    reminderText = if (state.reminderText.isBlank()) null else state.reminderText,
                    audioPath = finalAudioPath,
                    nextTriggerAt = finalTriggerTime,
                    recurrenceType = state.recurrenceType,
                    recurrenceJson = recurrenceJson,
                    missedPolicy = resolvedMissedPolicy,
                    loopPlayback = state.loopPlayback,
                    followUpCheckMinutes = state.followUpCheckMinutes
                )
                
                val id = if (state.initialReminderId != -1L) {
                    repository.updateReminder(reminder)
                    reminder.id
                } else {
                    repository.insertReminder(reminder)
                }
                savedId = id

                // The stored reminder now references the final copy; the temp take can go.
                unreferencedAudioFile = null
                if (sourceAudioFile != null) {
                    withContext(ioDispatcher) { sourceAudioFile.delete() }
                    if (tempAudioFile == sourceAudioFile) {
                        tempAudioFile = null
                        enhancementJob = null
                    }
                }

                scheduler.schedule(reminder.copy(id = id))
                // Saving gives the reminder a fresh schedule, so any missed occurrence it had is
                // settled; left behind, it would keep a one-time reminder hidden under Missed.
                missedRepository?.deleteMissedReminderByReminderId(id)

                // Keep the inbox original on cancel or failed save; consume it only once the
                // reminder and schedule exist. Never remove it merely because it was opened.
                importedShakeDraftId?.let { draftId ->
                    withContext(ioDispatcher) {
                        com.ghostgramlabs.speakalert.shake.ShakeDraftStore(appContext.filesDir).delete(draftId)
                    }
                    importedShakeDraftId = null
                }

                if (enhancementPending && sourceAudioFile != null) {
                    finalAudioPath?.let { enhanceSavedRecording(File(it)) }
                }

                // Signal success - screen should now navigate back
                val savedState = state.copy(
                    recordedAudioPath = finalAudioPath,
                    isSaving = false,
                    saveCompleted = true
                )
                setSavedDraft(savedState)

            } catch (e: CancellationException) {
                // The editor was cleared mid-save. A cancelled database call may still have
                // committed, so never delete the final copy here.
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                unreferencedAudioFile?.delete()
                val failed = _uiState.value.copy(isSaving = false, showError = true)
                // Written but not scheduled: point the retry at the stored row and its audio.
                _uiState.value = savedId?.let {
                    failed.copy(initialReminderId = it, recordedAudioPath = finalAudioPath)
                } ?: failed
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Leaving the editor mid-take (e.g. its navigation entry is removed) must release the
        // microphone; otherwise the recorder keeps capturing into a file deleted below.
        recordingTimerJob?.cancel()
        if (_uiState.value.isRecording) {
            runCatching { recorder.stop() }
        }
        player.stop()
        playbackJob?.cancel()
        // Clean up temp file if not saved
        enhancementJob?.cancel()
        tempAudioFile?.let { file ->
            file.delete()
            File(file.parentFile, file.name + ".enhanced").delete()
        }
        synchronized(detachedTempFiles) {
            detachedTempFiles.forEach { file ->
                file.delete()
                File(file.parentFile, file.name + ".enhanced").delete()
            }
            detachedTempFiles.clear()
        }
        tempAudioFile = null
    }

    private fun parseMissedPolicy(raw: String): MissedPolicy {
        return when (raw) {
            "FIRE_ON_RESUME", "FIRE" -> MissedPolicy.FIRE_ON_RESUME
            "SKIP_TO_NEXT", "SKIP" -> MissedPolicy.SKIP_TO_NEXT
            else -> MissedPolicy.SKIP_TO_NEXT
        }
    }

    private fun clampRecurrenceEndRuleJson(
        type: RecurrenceType,
        json: String?,
        minEndDateTimeMillis: Long
    ): String? {
        val model = RecurrenceUtils.fromJson(type, json) ?: return json
        val clampedModel = clampRecurrenceEndRule(model, minEndDateTimeMillis)
        return if (clampedModel == model) json else RecurrenceUtils.toJson(clampedModel)
    }

    private fun clampRecurrenceEndRule(
        model: RecurrenceModel,
        minEndDateTimeMillis: Long
    ): RecurrenceModel {
        val endRule = model.endRule
        if (endRule.type != EndRuleType.UNTIL_DATE) return model
        val endDateMillis = endRule.endDateMillis ?: return model
        if (endDateMillis >= minEndDateTimeMillis) return model

        val clampedEndRule = endRule.copy(endDateMillis = minEndDateTimeMillis)
        return when (model) {
            is RecurrenceModel.Daily -> model.copy(endRule = clampedEndRule)
            is RecurrenceModel.Weekly -> model.copy(endRule = clampedEndRule)
            is RecurrenceModel.Monthly -> model.copy(endRule = clampedEndRule)
            is RecurrenceModel.Yearly -> model.copy(endRule = clampedEndRule)
            is RecurrenceModel.Custom -> model.copy(endRule = clampedEndRule)
        }
    }

    fun discardDraft() {
        stopPlayback()
        if (_uiState.value.isRecording) {
            recordingTimerJob?.cancel()
            recorder.stop()
        }
        detachAndDeleteTempRecording()
    }

    private fun setSavedDraft(state: AddEditUiState) {
        savedDraft = state.toDraft()
        _uiState.value = state.copy(hasUnsavedChanges = false)
    }

    private fun setDraftState(state: AddEditUiState) {
        _uiState.value = state.copy(hasUnsavedChanges = isDraftDirty(state))
    }

    private fun isDraftDirty(state: AddEditUiState): Boolean {
        val base = savedDraft ?: return state.toDraft().hasMeaningfulContent()
        return state.toDraft() != base
    }

    private fun AddEditUiState.toDraft(): ReminderDraft {
        return ReminderDraft(
            title = title,
            reminderText = reminderText,
            recordedAudioPath = recordedAudioPath,
            isCustomAudioFile = isCustomAudioFile,
            triggerTime = triggerTime,
            recurrenceType = recurrenceType,
            recurrenceJson = recurrenceJson,
            loopPlayback = loopPlayback,
            followUpCheckMinutes = followUpCheckMinutes
        )
    }

    private data class ReminderDraft(
        val title: String,
        val reminderText: String,
        val recordedAudioPath: String?,
        val isCustomAudioFile: Boolean,
        val triggerTime: Long,
        val recurrenceType: RecurrenceType,
        val recurrenceJson: String?,
        val loopPlayback: Boolean,
        val followUpCheckMinutes: Int
    ) {
        fun hasMeaningfulContent(): Boolean {
            return title.isNotBlank() ||
                reminderText.isNotBlank() ||
                !recordedAudioPath.isNullOrBlank() ||
                recurrenceType != RecurrenceType.NONE ||
                loopPlayback ||
                followUpCheckMinutes > 0
        }
    }

    private data class AddEditSectionVisibility(
        val voiceRecording: Boolean,
        val audioFile: Boolean,
        val typedReminder: Boolean,
        val shortLabel: Boolean
    )
}
