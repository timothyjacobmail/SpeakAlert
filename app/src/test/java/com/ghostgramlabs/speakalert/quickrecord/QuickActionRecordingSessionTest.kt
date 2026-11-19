package com.ghostgramlabs.speakalert.quickrecord

import com.ghostgramlabs.speakalert.audio.AudioRecorder
import com.ghostgramlabs.speakalert.audio.RecordingOutcome
import com.ghostgramlabs.speakalert.alarm.AlarmScheduler
import com.ghostgramlabs.speakalert.data.repository.ReminderRepository
import com.ghostgramlabs.speakalert.shake.ShakeDraftStore
import com.ghostgramlabs.speakalert.shake.ShakeReminderCreator
import com.ghostgramlabs.speakalert.shake.ShakeReminderResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class QuickActionRecordingSessionTest {
    @get:Rule val folder = TemporaryFolder()
    private val drafts get() = ShakeDraftStore(folder.root)
    private var wall = 1_800_000_037_123L
    private var monotonic = 0L
    private val recorder = FakeRecorder()

    private fun session(save: suspend (File, Long, Long) -> ShakeReminderResult) = QuickActionRecordingSession(
        recorder, drafts, save, now = { wall }, elapsed = { monotonic }
    )

    @Test fun `second activation saves the voice exactly ten minutes after stopping`() = runTest {
        val repository: ReminderRepository = mock()
        val alarms: AlarmScheduler = mock()
        whenever(repository.insertReminder(any())).thenReturn(12L)
        val creator = ShakeReminderCreator(repository, alarms, folder.root, StandardTestDispatcher(testScheduler))
        val session = session { file, stopped, due -> creator.create(file, stopped, triggerAt = due) }
        session.toggle()
        assertEquals(QuickRecordingPhase.RECORDING, session.state.value.phase)
        verify(repository, never()).insertReminder(any())
        wall += 23_777L
        monotonic += 23_777L
        session.toggle()
        assertEquals(1, recorder.starts)
        assertEquals(1, recorder.stops)
        assertEquals(QuickRecordingPhase.SAVED, session.state.value.phase)
        val reminder = argumentCaptor<com.ghostgramlabs.speakalert.data.model.ReminderEntity>()
        verify(alarms).schedule(reminder.capture(), eq(false))
        assertEquals(wall + 600_000L, reminder.firstValue.nextTriggerAt)
        assertEquals("voice", File(reminder.firstValue.audioPath!!).readText())
        assertTrue(drafts.list().isEmpty())
    }

    @Test fun `duplicate delivery of one long press does not immediately stop recording`() = runTest {
        var saves = 0
        val session = session { _, _, _ -> saves++; error("Not expected") }
        session.toggle()
        monotonic += 100L
        session.toggle()
        assertEquals(QuickRecordingPhase.RECORDING, session.state.value.phase)
        assertEquals(0, recorder.stops)
        assertEquals(0, saves)
    }

    @Test fun `notification stop and a simultaneous second activation save only once`() = runTest {
        val saving = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var saves = 0
        val session = session { file, stopped, due ->
            saves++
            saving.complete(Unit)
            finish.await()
            file.delete()
            ShakeReminderResult(com.ghostgramlabs.speakalert.data.model.ReminderEntity(id = 9L, nextTriggerAt = due, createdAt = stopped), true)
        }
        session.toggle()
        val firstStop = launch { session.stopAndSave() }
        runCurrent()
        assertTrue(saving.isCompleted)
        session.stopAndSave()
        monotonic += 2_000L
        session.toggle()
        assertEquals(QuickRecordingPhase.SAVING, session.state.value.phase)
        finish.complete(Unit)
        firstStop.join()
        assertEquals(1, saves)
        assertEquals(1, recorder.stops)
        assertEquals(QuickRecordingPhase.SAVED, session.state.value.phase)
    }

    @Test fun `failed database handoff retains a completed recording in the inbox`() = runTest {
        val session = session { _, _, _ -> error("Storage unavailable") }
        session.toggle()
        session.stopAndSave()
        assertEquals(QuickRecordingPhase.FAILED, session.state.value.phase)
        assertEquals("voice", drafts.list().single().readText())
    }

    @Test fun `microphone contention leaves the other recording alone`() = runTest {
        recorder.busy = true
        val session = session { _, _, _ -> error("No save expected") }
        session.toggle()
        session.stopAndSave()
        assertEquals(QuickRecordingPhase.FAILED, session.state.value.phase)
        assertEquals(0, recorder.stops)
        assertTrue(drafts.list().isEmpty())
    }

    @Test fun `cancelled persistence reports failure instead of leaving saving stuck`() = runTest {
        val session = session { _, _, _ -> throw kotlinx.coroutines.CancellationException("Save unavailable") }
        session.toggle()
        session.stopAndSave()
        assertEquals(QuickRecordingPhase.FAILED, session.state.value.phase)
        assertEquals("voice", drafts.list().single().readText())
    }

    @Test fun `empty take never creates a reminder and its temporary file is deleted`() = runTest {
        recorder.empty = true
        var saves = 0
        val session = session { _, _, _ -> saves++; error("No save expected") }
        session.toggle()
        session.stopAndSave()
        assertEquals(0, saves)
        assertEquals(QuickRecordingPhase.FAILED, session.state.value.phase)
        assertFalse(recorder.output!!.exists())
    }

    @Test fun `closing the caller during save still completes the accepted stop`() = runTest {
        val finish = CompletableDeferred<Unit>()
        val session = session { _, stopped, due ->
            finish.await()
            ShakeReminderResult(com.ghostgramlabs.speakalert.data.model.ReminderEntity(id = 4L, nextTriggerAt = due, createdAt = stopped), true)
        }
        session.toggle()
        val caller = launch { session.stopAndSave() }
        runCurrent()
        caller.cancel()
        finish.complete(Unit)
        caller.join()
        assertEquals(QuickRecordingPhase.SAVED, session.state.value.phase)
        assertEquals(4L, session.state.value.reminderId)
    }

    private class FakeRecorder : AudioRecorder {
        var output: File? = null
        var starts = 0
        var stops = 0
        var busy = false
        var empty = false
        override fun start(outputFile: File, useExperimentalBehavior: Boolean) {
            check(!busy)
            starts++
            output = outputFile
            outputFile.writeText(if (empty) "" else "voice")
        }
        override fun stop(): RecordingOutcome {
            stops++
            return RecordingOutcome(output, 10_000, output?.length() ?: 0)
        }
        override fun getMaxAmplitude() = 10_000
    }
}
