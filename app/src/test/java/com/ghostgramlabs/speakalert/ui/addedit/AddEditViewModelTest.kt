package com.ghostgramlabs.speakalert.ui.addedit

import android.content.Context
import com.ghostgramlabs.speakalert.MainDispatcherRule
import com.ghostgramlabs.speakalert.alarm.AlarmScheduler
import com.ghostgramlabs.speakalert.audio.AudioEnhancer
import com.ghostgramlabs.speakalert.audio.AudioPlayer
import com.ghostgramlabs.speakalert.audio.AudioRecorder
import com.ghostgramlabs.speakalert.audio.RecordingOutcome
import com.ghostgramlabs.speakalert.data.model.ReminderEntity
import com.ghostgramlabs.speakalert.data.repository.ReminderRepository
import com.ghostgramlabs.speakalert.data.repository.SettingsRepository
import com.ghostgramlabs.speakalert.domain.RecurrenceUtils
import com.ghostgramlabs.speakalert.domain.models.MissedPolicy
import com.ghostgramlabs.speakalert.domain.models.EndRuleType
import com.ghostgramlabs.speakalert.domain.models.RecurrenceEndRule
import com.ghostgramlabs.speakalert.domain.models.RecurrenceModel
import com.ghostgramlabs.speakalert.domain.models.RecurrenceType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.util.Calendar

@OptIn(ExperimentalCoroutinesApi::class)
class AddEditViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: ReminderRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var context: Context
    private lateinit var recorder: AudioRecorder
    private lateinit var player: AudioPlayer
    private lateinit var enhancer: AudioEnhancer
    private lateinit var experimentalVoiceEnhancementEnabled: MutableStateFlow<Boolean>

    private lateinit var viewModel: AddEditViewModel
    
    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        
        repository = mock()
        scheduler = mock()
        settingsRepository = mock()
        context = mock()
        recorder = mock()
        player = mock()
        enhancer = mock()
        experimentalVoiceEnhancementEnabled = MutableStateFlow(true)

        // Default: the recorder returns a healthy, audible take.
        whenever(recorder.stop()).thenReturn(
            RecordingOutcome(
                file = tempFolder.newFile("take.m4a"),
                peakAmplitude = 12_000,
                sizeBytes = 4_096L
            )
        )

        // Mock Context filesDir and cacheDir
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        whenever(context.filesDir).thenReturn(filesDir)
        whenever(context.cacheDir).thenReturn(cacheDir)
        
        // Mock Settings
        whenever(settingsRepository.appVolume).thenReturn(MutableStateFlow(1.0f))
        whenever(settingsRepository.speakTextIfNoVoice).thenReturn(MutableStateFlow(true))
        whenever(settingsRepository.showVoiceRecordingSection).thenReturn(MutableStateFlow(true))
        whenever(settingsRepository.experimentalVoiceEnhancementEnabled)
            .thenReturn(experimentalVoiceEnhancementEnabled)
        whenever(settingsRepository.showAudioFileSection).thenReturn(MutableStateFlow(true))
        whenever(settingsRepository.showTypedReminderSection).thenReturn(MutableStateFlow(true))
        whenever(settingsRepository.showShortLabelSection).thenReturn(MutableStateFlow(true))
        whenever(settingsRepository.defaultMissedPolicy).thenReturn(MutableStateFlow("SKIP_TO_NEXT"))
        whenever(settingsRepository.defaultFollowUpMinutes).thenReturn(MutableStateFlow(0))
        
        viewModel = AddEditViewModel(
            repository,
            scheduler,
            settingsRepository,
            context,
            recorder,
            player,
            enhancer,
            UnconfinedTestDispatcher()
        )
    }

    @Test
    fun `quick recording schedules each preset from selection time with its audio`() = runTest {
        whenever(repository.insertReminder(any())).thenReturn(42L)
        org.mockito.kotlin.doAnswer { invocation ->
            invocation.getArgument<File>(0).writeText("recorded voice")
            null
        }.whenever(recorder).start(any(), any())
        val chosenAt = System.currentTimeMillis() + 17_123L
        val presets = listOf(5, 10, 15, 30, 60)
        presets.forEach { minutes ->
            val editor = AddEditViewModel(repository, scheduler, settingsRepository, context,
                recorder, player, enhancer, UnconfinedTestDispatcher())
            editor.startRecording()
            editor.stopRecording()
            editor.saveQuickRecordedReminder(minutes, chosenAt)
            advanceUntilIdle()
            assertTrue(editor.uiState.value.saveCompleted)
        }
        val scheduled = argumentCaptor<ReminderEntity>()
        verify(scheduler, times(5)).schedule(scheduled.capture(), org.mockito.kotlin.eq(false))
        assertEquals(presets.map { chosenAt + it * 60_000L }, scheduled.allValues.map { it.nextTriggerAt })
        scheduled.allValues.forEach { reminder ->
            assertEquals("recorded voice", File(reminder.audioPath!!).readText())
            assertEquals(RecurrenceType.NONE, reminder.recurrenceType)
        }
    }

    @Test
    fun `quick recording ignores repeated delay taps while saving`() = runTest {
        whenever(repository.insertReminder(any())).thenReturn(42L)
        org.mockito.kotlin.doAnswer { invocation ->
            invocation.getArgument<File>(0).writeText("voice")
            null
        }.whenever(recorder).start(any(), any())
        viewModel.startRecording()
        viewModel.stopRecording()
        viewModel.saveQuickRecordedReminder(5)
        viewModel.saveQuickRecordedReminder(60)
        advanceUntilIdle()
        viewModel.saveQuickRecordedReminder(10)
        verify(repository, times(1)).insertReminder(any())
        verify(scheduler, times(1)).schedule(any(), org.mockito.kotlin.eq(false))
    }

    @Test
    fun `quick recording refuses an empty take and allows recording again`() = runTest {
        viewModel.startRecording()
        viewModel.stopRecording()
        viewModel.saveQuickRecordedReminder(5)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.recordedAudioPath)
        assertEquals(RecordingIssue.TOO_SHORT, viewModel.uiState.value.recordingIssue)
        verify(repository, never()).insertReminder(any())
        viewModel.startRecording()
        assertTrue(viewModel.uiState.value.isRecording)
        viewModel.stopRecording()
    }

    @Test
    fun `canceling an imported shake take preserves the inbox original`() = runTest {
        val store = com.ghostgramlabs.speakalert.shake.ShakeDraftStore(context.filesDir)
        val original = store.finish(store.newRecording().apply { writeText("voice") })
        viewModel.applyDefaultsForNewReminder(original.nameWithoutExtension)
        advanceUntilIdle()
        val imported = File(viewModel.uiState.value.recordedAudioPath!!)
        assertNotEquals(original, imported)
        assertEquals("voice", imported.readText())
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)
        viewModel.discardDraft()
        advanceUntilIdle()
        assertTrue(original.exists())
        assertFalse(imported.exists())
    }

    @Test
    fun `saved shake take becomes reminder audio and consumes the inbox original`() = runTest {
        val store = com.ghostgramlabs.speakalert.shake.ShakeDraftStore(context.filesDir)
        val original = store.finish(store.newRecording().apply { writeText("voice") })
        whenever(repository.insertReminder(any())).thenReturn(42L)
        viewModel.applyDefaultsForNewReminder(original.nameWithoutExtension)
        advanceUntilIdle()
        viewModel.saveReminder()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.saveCompleted)
        assertFalse(original.exists())
        assertEquals("voice", File(viewModel.uiState.value.recordedAudioPath!!).readText())
        verify(scheduler).schedule(any(), org.mockito.kotlin.eq(false))
    }

    @Test
    fun `failed save retains original shake take and editor copy for retry`() = runTest {
        val store = com.ghostgramlabs.speakalert.shake.ShakeDraftStore(context.filesDir)
        val original = store.finish(store.newRecording().apply { writeText("voice") })
        whenever(repository.insertReminder(any())).thenThrow(IllegalStateException("disk full"))
        viewModel.applyDefaultsForNewReminder(original.nameWithoutExtension)
        advanceUntilIdle()
        viewModel.saveReminder()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.saveCompleted)
        assertTrue(original.exists())
        assertEquals("voice", File(viewModel.uiState.value.recordedAudioPath!!).readText())
    }

    @Test
    fun `replacing imported shake audio leaves its original in the inbox`() = runTest {
        val store = com.ghostgramlabs.speakalert.shake.ShakeDraftStore(context.filesDir)
        val original = store.finish(store.newRecording().apply { writeText("original voice") })
        whenever(repository.insertReminder(any())).thenReturn(42L)
        viewModel.applyDefaultsForNewReminder(original.nameWithoutExtension)
        advanceUntilIdle()
        viewModel.setCustomAudio("content://audio/new", "Replacement")
        viewModel.saveReminder()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.saveCompleted)
        assertTrue(original.exists())
    }

    @Test
    fun `busy microphone preserves imported audio even with enhancement disabled`() = runTest {
        experimentalVoiceEnhancementEnabled.value = false
        val store = com.ghostgramlabs.speakalert.shake.ShakeDraftStore(context.filesDir)
        val original = store.finish(store.newRecording().apply { writeText("voice") })
        viewModel.applyDefaultsForNewReminder(original.nameWithoutExtension)
        advanceUntilIdle()
        val path = viewModel.uiState.value.recordedAudioPath!!
        org.mockito.kotlin.doThrow(IllegalStateException("Microphone busy"))
            .whenever(recorder).start(any(), any())
        viewModel.startRecording()
        assertFalse(viewModel.uiState.value.isRecording)
        assertEquals(RecordingIssue.CAPTURE_FAILED, viewModel.uiState.value.recordingIssue)
        assertEquals(path, viewModel.uiState.value.recordedAudioPath)
        assertEquals("voice", File(path).readText())
        assertTrue(original.exists())
    }

    @Test
    fun `recomposing imported editor does not replace edits and missing takes fail clearly`() = runTest {
        viewModel.applyDefaultsForNewReminder("../private")
        advanceUntilIdle()
        assertEquals(RecordingIssue.IMPORT_FAILED, viewModel.uiState.value.recordingIssue)
        assertFalse(viewModel.uiState.value.isImporting)
        viewModel.updateTitle("Keep this")
        viewModel.applyDefaultsForNewReminder("../private")
        advanceUntilIdle()
        assertEquals("Keep this", viewModel.uiState.value.title)
    }

    @Test
    fun `saveReminder with valid text saves to repository and shedules alarm`() = runTest {
        // Arrange
        val title = "Buy milk"
        val text = "Don't forget the almond milk"
        viewModel.updateTitle(title)
        viewModel.updateReminderText(text)
        
        // Mock insert to return ID 1
        whenever(repository.insertReminder(any())).thenReturn(1L)

        // Act
        viewModel.saveReminder()
        advanceUntilIdle()

        // Assert
        assertTrue(viewModel.uiState.value.saveCompleted)
        assertFalse(viewModel.uiState.value.showError)
        
        // Verify Repository Insert
        val captor = argumentCaptor<ReminderEntity>()
        verify(repository).insertReminder(captor.capture())
        assertEquals(title, captor.firstValue.title)
        assertEquals(text, captor.firstValue.reminderText)
        
        // Verify Scheduler
        verify(scheduler).schedule(any(), any())
    }

    @Test
    fun `saveReminder with empty title generates smart title from text`() = runTest {
        // Arrange
        val text = "Call Mom"
        viewModel.updateReminderText(text) // Title is empty
        whenever(repository.insertReminder(any())).thenReturn(1L)

        // Act
        viewModel.saveReminder()
        advanceUntilIdle()

        // Assert
        val captor = argumentCaptor<ReminderEntity>()
        verify(repository).insertReminder(captor.capture())
        // New behavior: VM saves null title if blank, UI layer handles fallback to "Reminder at..." or time
        assertNull(captor.firstValue.title)
    }

    @Test
    fun `saveReminder with no text and no audio shows error`() = runTest {
        // Act
        viewModel.saveReminder() // Initial state is empty
        advanceUntilIdle()

        // Assert
        assertTrue(viewModel.uiState.value.showError)
        assertFalse(viewModel.uiState.value.saveCompleted)
        verify(repository, Mockito.never()).insertReminder(any())
    }
    
    @Test
    fun `startRecording updates state and starts recorder`() = runTest {
        // Act
        viewModel.startRecording()
        // Do NOT advanceUntilIdle, as that finishes the 5-minute timer instantly
        // Just advance a little to let the launch start
        advanceTimeBy(100) 
        
        // Assert
        assertTrue(viewModel.uiState.value.isRecording)
        verify(recorder).start(any(), any())
    }
    
    @Test
    fun `stopRecording updates state and stops recorder`() = runTest {
        // Arrange
        viewModel.startRecording()
        advanceTimeBy(100) 
        
        // Act
        viewModel.stopRecording()
        advanceTimeBy(100)
        
        // Assert
        assertFalse(viewModel.uiState.value.isRecording)
        verify(recorder).stop()
        assertNotNull(viewModel.uiState.value.recordedAudioPath)
        assertNull(viewModel.uiState.value.recordingIssue)
    }

    @Test
    fun `a quick tap reports too-short rather than a failure`() = runTest {
        // Arrange: released before the encoder had a frame
        whenever(recorder.stop()).thenReturn(RecordingOutcome.NOTHING)
        viewModel.startRecording()
        advanceTimeBy(100)

        // Act
        viewModel.stopRecording()
        advanceTimeBy(100)

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isRecording)
        assertNull(state.recordedAudioPath)
        assertEquals(RecordingIssue.TOO_SHORT, state.recordingIssue)
    }

    @Test
    fun `nothing captured after a real attempt reports a device failure`() = runTest {
        // Arrange: recorded well past the quick-tap window, still nothing on disk
        whenever(recorder.stop()).thenReturn(RecordingOutcome.NOTHING)
        whenever(recorder.getMaxAmplitude()).thenReturn(1_500)
        viewModel.startRecording()
        advanceTimeBy(3_000)

        // Act
        viewModel.stopRecording()
        advanceTimeBy(100)

        // Assert
        assertEquals(RecordingIssue.CAPTURE_FAILED, viewModel.uiState.value.recordingIssue)
    }

    @Test
    fun `a usable take is handed to the enhancer`() = runTest {
        experimentalVoiceEnhancementEnabled.value = true

        // Act
        viewModel.startRecording()
        advanceTimeBy(100)
        viewModel.stopRecording()
        advanceUntilIdle()

        // Assert
        verify(enhancer).enhance(any())
    }

    @Test
    fun `a usable take skips enhancement when experiment is disabled`() = runTest {
        experimentalVoiceEnhancementEnabled.value = false
        viewModel.startRecording()
        advanceTimeBy(100)
        viewModel.stopRecording()
        advanceUntilIdle()

        verify(enhancer, never()).enhance(any())
    }

    @Test
    fun `disabled experiment keeps baseline raw take without outcome classification`() = runTest {
        experimentalVoiceEnhancementEnabled.value = false
        whenever(recorder.stop()).thenReturn(RecordingOutcome.NOTHING)

        viewModel.startRecording()
        advanceTimeBy(100)
        viewModel.stopRecording()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.recordedAudioPath)
        assertNull(state.recordingIssue)
        verify(recorder).start(any(), org.mockito.kotlin.eq(false))
        verify(enhancer, never()).enhance(any())
    }

    @Test
    fun `a silent take is not enhanced`() = runTest {
        // Arrange: no signal at all, so there is nothing to lift
        experimentalVoiceEnhancementEnabled.value = true
        whenever(recorder.stop()).thenReturn(
            RecordingOutcome(
                file = tempFolder.newFile("dead.m4a"),
                peakAmplitude = 0,
                sizeBytes = 2_048L
            )
        )

        // Act
        viewModel.startRecording()
        advanceTimeBy(100)
        viewModel.stopRecording()
        advanceUntilIdle()

        // Assert
        verify(enhancer, never()).enhance(any())
    }

    @Test
    fun `a quiet but real take is left alone`() = runTest {
        // Arrange: a whisper in a quiet room - low peak, but genuine audio
        whenever(recorder.stop()).thenReturn(
            RecordingOutcome(
                file = tempFolder.newFile("whisper.m4a"),
                peakAmplitude = 300,
                sizeBytes = 3_072L
            )
        )
        whenever(recorder.getMaxAmplitude()).thenReturn(300)
        viewModel.startRecording()
        advanceTimeBy(2_000)

        // Act
        viewModel.stopRecording()
        advanceTimeBy(100)

        // Assert: kept, and no warning shown
        val state = viewModel.uiState.value
        assertNotNull(state.recordedAudioPath)
        assertNull(state.recordingIssue)
    }

    @Test
    fun `stopRecording with a silent take keeps the file and warns the user`() = runTest {
        // Arrange: a file was written, but the microphone never delivered any level
        whenever(recorder.stop()).thenReturn(
            RecordingOutcome(
                file = tempFolder.newFile("silent.m4a"),
                peakAmplitude = 0,
                sizeBytes = 2_048L
            )
        )
        viewModel.startRecording()
        advanceTimeBy(100)

        // Act
        viewModel.stopRecording()
        advanceTimeBy(100)

        // Assert
        val state = viewModel.uiState.value
        assertNotNull(state.recordedAudioPath)
        assertEquals(RecordingIssue.SILENT, state.recordingIssue)

        viewModel.clearRecordingIssue()
        assertNull(viewModel.uiState.value.recordingIssue)
    }

    @Test
    fun `loadReminder populates ui state`() = runTest {
        // Arrange
        val reminder = ReminderEntity(
            id = 1,
            title = "Existing Task",
            reminderText = "Notes",
            nextTriggerAt = 1000L,
            recurrenceType = RecurrenceType.DAILY
        )
        whenever(repository.getReminder(1)).thenReturn(reminder)
        
        // Act
        viewModel.loadReminder(1)
        advanceUntilIdle()
        
        // Assert
        assertEquals("Existing Task", viewModel.uiState.value.title)
        assertEquals("Notes", viewModel.uiState.value.reminderText)
        assertEquals(RecurrenceType.DAILY, viewModel.uiState.value.recurrenceType)
    }

    @Test
    fun `playRecording calls player play`() = runTest {
        // Arrange
        val audioFile = File.createTempFile("test", ".aac")
        val reminder = ReminderEntity(id = 1, audioPath = audioFile.absolutePath, nextTriggerAt = 1000L)
        whenever(repository.getReminder(1)).thenReturn(reminder)
        viewModel.loadReminder(1)
        advanceUntilIdle()

        // Act
        viewModel.playRecording()
        // IMPORTANT: Use advanceTimeBy, NOT advanceUntilIdle, because playRecording starts an infinite loop
        advanceTimeBy(100)

        // Assert
        verify(player).playFile(any())
        assertTrue(viewModel.uiState.value.isPlaying)
        
        // Cleanup loop
        viewModel.stopPlayback()
    }

    @Test
    fun `stopPlayback calls player stop`() = runTest {
        // Arrange
        val audioFile = File.createTempFile("test", ".aac")
        val reminder = ReminderEntity(id = 1, audioPath = audioFile.absolutePath, nextTriggerAt = 1000L)
        whenever(repository.getReminder(1)).thenReturn(reminder)
        viewModel.loadReminder(1)
        advanceUntilIdle()
        
        viewModel.playRecording()
        advanceTimeBy(100) // Start the loop

        // Act
        viewModel.stopPlayback()
        advanceUntilIdle() // Now it's safe to idle as loop should be cancelled

        // Assert
        // Stop is called twice: once in playRecording (to clear previous) and once manually
        verify(player, Mockito.times(2)).stop()
        assertFalse(viewModel.uiState.value.isPlaying)
    }
    
    @Test
    fun `seekTo calls player seekTo`() = runTest {
        // Arrange
        val audioFile = File.createTempFile("test", ".aac")
        val reminder = ReminderEntity(id = 1, audioPath = audioFile.absolutePath, nextTriggerAt = 1000L)
        whenever(repository.getReminder(1)).thenReturn(reminder)
        whenever(player.getDuration()).thenReturn(10000) // 10 seconds
        
        viewModel.loadReminder(1)
        advanceUntilIdle()
        viewModel.playRecording()
        advanceTimeBy(100) // Start the loop

        // Act
        viewModel.seekTo(0.5f) // 50%
        
        // Assert
        verify(player).seekTo(5000) // 50% of 10000 is 5000
        
        // Cleanup
        viewModel.stopPlayback()
    }

    @Test
    fun `saveReminder recurring keeps selected first trigger when it matches the rule`() = runTest {
        // Arrange
        val selected = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 5)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        viewModel.updateReminderText("Daily standup")
        viewModel.setTriggerTime(selected)
        viewModel.setRecurrence(
            RecurrenceType.DAILY,
            RecurrenceUtils.toJson(RecurrenceModel.Daily())
        )
        whenever(repository.insertReminder(any())).thenReturn(1L)

        // Act
        viewModel.saveReminder()
        advanceUntilIdle()

        // Assert
        val captor = argumentCaptor<ReminderEntity>()
        verify(repository).insertReminder(captor.capture())
        assertEquals(selected, captor.firstValue.nextTriggerAt)
    }

    @Test
    fun `setRecurrence clamps until date before trigger time`() = runTest {
        val triggerTime = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 2)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val earlierEndTime = triggerTime - 60_000L

        viewModel.setTriggerTime(triggerTime)
        viewModel.setRecurrence(
            RecurrenceModel.Daily(
                endRule = RecurrenceEndRule(
                    type = EndRuleType.UNTIL_DATE,
                    endDateMillis = earlierEndTime
                )
            )
        )

        val model = RecurrenceUtils.fromJson(
            viewModel.uiState.value.recurrenceType,
            viewModel.uiState.value.recurrenceJson
        ) as RecurrenceModel.Daily
        assertEquals(triggerTime, model.endRule.endDateMillis)
    }

    @Test
    fun `setTriggerTime clamps existing until date when start moves later`() = runTest {
        val originalTrigger = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val originalEnd = originalTrigger + 60_000L
        val movedLater = originalEnd + 60_000L

        viewModel.setTriggerTime(originalTrigger)
        viewModel.setRecurrence(
            RecurrenceModel.Daily(
                endRule = RecurrenceEndRule(
                    type = EndRuleType.UNTIL_DATE,
                    endDateMillis = originalEnd
                )
            )
        )

        viewModel.setTriggerTime(movedLater)

        val model = RecurrenceUtils.fromJson(
            viewModel.uiState.value.recurrenceType,
            viewModel.uiState.value.recurrenceJson
        ) as RecurrenceModel.Daily
        assertEquals(movedLater, model.endRule.endDateMillis)
    }

    @Test
    fun `setCustomAudio while recording stops recorder and switches to custom file mode`() = runTest {
        // Arrange
        viewModel.startRecording()
        advanceTimeBy(100)

        // Act
        viewModel.setCustomAudio("content://com.test.provider/audio/1", "morning_alarm.mp3")
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isRecording)
        assertTrue(state.isCustomAudioFile)
        assertEquals("content://com.test.provider/audio/1", state.recordedAudioPath)
        assertEquals("morning_alarm.mp3", state.customAudioFileName)
        verify(recorder).stop()
    }

    @Test
    fun `removeCustomAudio clears selected content uri`() = runTest {
        // Arrange
        viewModel.setCustomAudio("content://com.test.provider/audio/2", "note.wav")
        advanceUntilIdle()

        // Act
        viewModel.removeCustomAudio()
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isCustomAudioFile)
        assertNull(state.recordedAudioPath)
        assertNull(state.customAudioFileName)
    }

    @Test
    fun `setFollowUpCheckMinutes clamps negative to zero`() {
        viewModel.setFollowUpCheckMinutes(-5)
        assertEquals(0, viewModel.uiState.value.followUpCheckMinutes)

        viewModel.setFollowUpCheckMinutes(15)
        assertEquals(15, viewModel.uiState.value.followUpCheckMinutes)
    }

    @Test
    fun `saveReminder one-time past trigger shows past time error`() = runTest {
        // Arrange
        viewModel.updateReminderText("Pay electricity bill")
        viewModel.setRecurrence(RecurrenceType.NONE, null)
        viewModel.setTriggerTime(System.currentTimeMillis() - 60_000)

        // Act
        viewModel.saveReminder()
        advanceUntilIdle()

        // Assert
        assertTrue(viewModel.uiState.value.showPastTimeError)
        verify(repository, Mockito.never()).insertReminder(any())
        verify(repository, Mockito.never()).updateReminder(any())
    }

    @Test
    fun `saveReminder recurring past trigger auto aligns to future`() = runTest {
        // Arrange
        val now = System.currentTimeMillis()
        viewModel.updateReminderText("Daily check-in")
        viewModel.setTriggerTime(now - 60_000)
        viewModel.setRecurrence(RecurrenceType.DAILY, null)
        whenever(repository.insertReminder(any())).thenReturn(55L)

        // Act
        viewModel.saveReminder()
        advanceUntilIdle()

        // Assert
        val captor = argumentCaptor<ReminderEntity>()
        verify(repository).insertReminder(captor.capture())
        assertTrue(captor.firstValue.nextTriggerAt > now)
        assertEquals(RecurrenceType.DAILY, captor.firstValue.recurrenceType)
        verify(scheduler).schedule(any(), any())
        assertFalse(viewModel.uiState.value.showPastTimeError)
    }

    @Test
    fun `saveReminder existing reminder updates instead of insert`() = runTest {
        // Arrange
        val existing = ReminderEntity(
            id = 99,
            title = "Existing",
            reminderText = "Original text",
            nextTriggerAt = System.currentTimeMillis() + 3600_000,
            recurrenceType = RecurrenceType.NONE
        )
        whenever(repository.getReminder(99)).thenReturn(existing)
        viewModel.loadReminder(99)
        advanceUntilIdle()
        viewModel.updateReminderText("Updated text")

        // Act
        viewModel.saveReminder()
        advanceUntilIdle()

        // Assert
        val captor = argumentCaptor<ReminderEntity>()
        verify(repository).updateReminder(captor.capture())
        assertEquals(99L, captor.firstValue.id)
        assertEquals("Updated text", captor.firstValue.reminderText)
        verify(repository, Mockito.never()).insertReminder(any())
        verify(scheduler).schedule(any(), any())
    }

    @Test
    fun `saveReminder maps FIRE default missed policy to FIRE_ON_RESUME`() = runTest {
        // Arrange
        whenever(settingsRepository.defaultMissedPolicy).thenReturn(MutableStateFlow("FIRE"))
        viewModel.updateReminderText("Hydration reminder")
        whenever(repository.insertReminder(any())).thenReturn(77L)

        // Act
        viewModel.saveReminder()
        advanceUntilIdle()

        // Assert
        val captor = argumentCaptor<ReminderEntity>()
        verify(repository).insertReminder(captor.capture())
        assertEquals(MissedPolicy.FIRE_ON_RESUME, captor.firstValue.missedPolicy)
    }

    @Test
    fun `section visibility preferences remain independent`() = runTest {
        whenever(settingsRepository.showVoiceRecordingSection).thenReturn(MutableStateFlow(false))
        whenever(settingsRepository.showAudioFileSection).thenReturn(MutableStateFlow(false))
        whenever(settingsRepository.showTypedReminderSection).thenReturn(MutableStateFlow(false))
        whenever(settingsRepository.showShortLabelSection).thenReturn(MutableStateFlow(false))

        viewModel = AddEditViewModel(
            repository,
            scheduler,
            settingsRepository,
            context,
            recorder,
            player,
            enhancer,
            UnconfinedTestDispatcher()
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showVoiceRecordingSection)
        assertFalse(viewModel.uiState.value.showAudioFileSection)
        assertFalse(viewModel.uiState.value.showTypedReminderSection)
        assertFalse(viewModel.uiState.value.showShortLabelSection)
    }

    @Test
    fun `audit repeated save during success navigation inserts only once`() = runTest {
        viewModel.updateReminderText("One reminder only")
        whenever(repository.insertReminder(any())).thenReturn(10L)
        viewModel.saveReminder()
        advanceUntilIdle()
        // The editor stays visible for its 800 ms success animation.
        viewModel.saveReminder()
        advanceUntilIdle()
        verify(repository, times(1)).insertReminder(any())
    }

    @Test
    fun `audit clearing editor while recording releases microphone`() = runTest {
        viewModel.startRecording()
        val store = androidx.lifecycle.ViewModelStore()
        store.put("editor", viewModel)
        store.clear()
        verify(recorder).stop()
    }

    @Test
    fun `audit retry after database failure retains recorded audio`() = runTest {
        experimentalVoiceEnhancementEnabled.value = false
        advanceUntilIdle()
        viewModel.startRecording()
        val fileCaptor = argumentCaptor<File>()
        verify(recorder).start(fileCaptor.capture(), org.mockito.kotlin.eq(false))
        fileCaptor.firstValue.writeBytes(byteArrayOf(1, 2, 3, 4))
        viewModel.stopRecording()
        whenever(repository.insertReminder(any()))
            .thenThrow(IllegalStateException("Simulated database failure"))
            .thenReturn(11L)
        viewModel.saveReminder()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.saveCompleted)
        viewModel.saveReminder()
        advanceUntilIdle()
        val saved = argumentCaptor<ReminderEntity>()
        verify(repository, times(2)).insertReminder(saved.capture())
        assertTrue("Retried reminder must reference an existing audio file",
            File(saved.lastValue.audioPath!!).isFile)
    }

    @Test
    fun `saving during a take finishes the recording and saves it`() = runTest {
        experimentalVoiceEnhancementEnabled.value = false
        advanceUntilIdle()
        viewModel.startRecording()
        val fileCaptor = argumentCaptor<File>()
        verify(recorder).start(fileCaptor.capture(), org.mockito.kotlin.eq(false))
        fileCaptor.firstValue.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(viewModel.uiState.value.isRecording)
        whenever(repository.insertReminder(any())).thenReturn(12L)

        // Save without stopping first: the user has finished speaking and reached for Save.
        viewModel.saveReminder()
        advanceUntilIdle()

        assertFalse(
            "Saving must not ask for a message while a take is running",
            viewModel.uiState.value.showError
        )
        assertTrue(viewModel.uiState.value.saveCompleted)
        verify(recorder).stop()
        val saved = argumentCaptor<ReminderEntity>()
        verify(repository).insertReminder(saved.capture())
        assertTrue(
            "The in-progress take must become the reminder's audio",
            File(saved.firstValue.audioPath!!).isFile
        )
    }

    @Test
    fun `save does not wait for enhancement and cleans up the saved copy instead`() = runTest {
        // Queued rather than run inline, so the take's enhancement is still pending at Save.
        val io = StandardTestDispatcher(testScheduler)
        viewModel = AddEditViewModel(
            repository, scheduler, settingsRepository, context, recorder, player, enhancer, io
        )
        experimentalVoiceEnhancementEnabled.value = true
        advanceUntilIdle()
        viewModel.startRecording()
        val fileCaptor = argumentCaptor<File>()
        verify(recorder).start(fileCaptor.capture(), org.mockito.kotlin.eq(true))
        val take = fileCaptor.firstValue.apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        whenever(recorder.stop()).thenReturn(
            RecordingOutcome(file = take, peakAmplitude = 12_000, sizeBytes = 4L)
        )
        advanceTimeBy(1_100)
        viewModel.stopRecording()
        whenever(repository.insertReminder(any())).thenReturn(21L)
        // Record what the reminder list would show while each cleanup runs.
        val shownDuringCleanup = mutableListOf<Set<String>>()
        whenever(enhancer.enhance(any())).thenAnswer {
            shownDuringCleanup += com.ghostgramlabs.speakalert.audio.AudioCleanupTracker.inProgress.value
            true
        }

        viewModel.saveReminder()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.saveCompleted)
        val saved = argumentCaptor<ReminderEntity>()
        verify(repository).insertReminder(saved.capture())
        val savedAudio = File(saved.firstValue.audioPath!!)
        assertTrue(savedAudio.isFile)
        // The pending cleanup is redone on the stored copy, not waited for.
        verify(enhancer).enhance(savedAudio)
        // The list says "improving" while the stored copy is cleaned up, and stops afterwards.
        assertTrue(shownDuringCleanup.any { savedAudio.absolutePath in it })
        assertFalse(
            savedAudio.absolutePath in com.ghostgramlabs.speakalert.audio.AudioCleanupTracker.inProgress.value
        )
    }

    @Test
    fun `saving during a take that captured nothing still reports the problem`() = runTest {
        // Finishing the take for the user must not paper over a take with nothing in it.
        whenever(recorder.stop()).thenReturn(RecordingOutcome.NOTHING)
        viewModel.startRecording()
        advanceTimeBy(100)

        viewModel.saveReminder()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showError)
        assertFalse(viewModel.uiState.value.saveCompleted)
        verify(repository, Mockito.never()).insertReminder(any())
    }

    private fun recordBaselineTake(): File {
        experimentalVoiceEnhancementEnabled.value = false
        viewModel.startRecording()
        val fileCaptor = argumentCaptor<File>()
        verify(recorder).start(fileCaptor.capture(), org.mockito.kotlin.eq(false))
        fileCaptor.firstValue.writeBytes(byteArrayOf(1, 2, 3, 4))
        viewModel.stopRecording()
        return fileCaptor.firstValue
    }

    @Test
    fun `failed database write keeps temp take and removes its unreferenced copy`() = runTest {
        advanceUntilIdle()
        val take = recordBaselineTake()
        whenever(repository.insertReminder(any()))
            .thenThrow(IllegalStateException("Simulated database failure"))

        viewModel.saveReminder()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.saveCompleted)
        assertFalse(state.isSaving)
        assertTrue(take.isFile)
        assertEquals(take.absolutePath, state.recordedAudioPath)
        assertEquals(0, File(context.filesDir, "reminders").listFiles()!!.size)
    }

    @Test
    fun `schedule failure after insert retries as an update with the saved audio`() = runTest {
        advanceUntilIdle()
        val take = recordBaselineTake()
        whenever(repository.insertReminder(any())).thenReturn(21L)
        org.mockito.kotlin.doThrow(IllegalStateException("Simulated alarm failure"))
            .doNothing()
            .whenever(scheduler).schedule(any(), any())

        viewModel.saveReminder()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.saveCompleted)
        assertFalse(take.exists())

        viewModel.saveReminder()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.saveCompleted)
        verify(repository, times(1)).insertReminder(any())
        val updated = argumentCaptor<ReminderEntity>()
        verify(repository).updateReminder(updated.capture())
        assertEquals(21L, updated.firstValue.id)
        assertTrue(File(updated.firstValue.audioPath!!).isFile)
    }
}
