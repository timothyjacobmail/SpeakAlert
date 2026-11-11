package com.ghostgramlabs.speakalert.quickrecord

import com.ghostgramlabs.speakalert.audio.AudioRecorder
import com.ghostgramlabs.speakalert.shake.ShakeDraftStore
import com.ghostgramlabs.speakalert.shake.ShakeReminderResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

enum class QuickRecordingPhase { IDLE, RECORDING, SAVING, SAVED, FAILED }

data class QuickRecordingState(
    val phase: QuickRecordingPhase = QuickRecordingPhase.IDLE,
    val startedAt: Long = 0L,
    val reminderId: Long? = null,
    val scheduled: Boolean = false,
    val silent: Boolean = false
)

/** One serial recording/save handoff, shared by hardware launches and notification controls. */
class QuickActionRecordingSession(
    private val recorder: AudioRecorder,
    private val drafts: ShakeDraftStore,
    private val save: suspend (File, Long, Long) -> ShakeReminderResult,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long,
    private val experimental: () -> Boolean = { false }
) {
    private val mutableState = MutableStateFlow(QuickRecordingState())
    val state = mutableState.asStateFlow()
    private var file: File? = null
    private var lastActivation: Long? = null

    suspend fun toggle() {
        val at = elapsed()
        // OEMs can redeliver a launcher intent during the same long press.
        if (lastActivation?.let { at - it < 750L } == true) return
        lastActivation = at
        when (state.value.phase) {
            QuickRecordingPhase.RECORDING -> stopAndSave()
            QuickRecordingPhase.SAVING -> Unit
            else -> start()
        }
    }

    private fun start() {
        var candidate: File? = null
        try {
            candidate = drafts.newRecording()
            recorder.start(candidate, experimental())
            file = candidate
            mutableState.value = QuickRecordingState(QuickRecordingPhase.RECORDING, startedAt = now())
        } catch (_: Exception) {
            candidate?.delete()
            mutableState.value = QuickRecordingState(QuickRecordingPhase.FAILED)
        }
    }

    suspend fun stopAndSave() {
        val take = file ?: return
        file = null
        val stoppedAt = now()
        val previous = state.value
        mutableState.value = previous.copy(phase = QuickRecordingPhase.SAVING)
        // Once Stop is accepted, closing the UI/service must not cancel the durable handoff.
        withContext(NonCancellable) {
            try {
                val outcome = recorder.stop()
                check(outcome.file != null && take.length() > 0L) { "No completed audio" }
                val completed = drafts.finish(take)
                val result = save(completed, stoppedAt, stoppedAt + DEFAULT_DELAY_MS)
                mutableState.value = previous.copy(
                    phase = QuickRecordingPhase.SAVED,
                    reminderId = result.reminder.id,
                    scheduled = result.scheduled,
                    silent = outcome.isSilent
                )
            } catch (failure: Exception) {
                // Completed takes stay in the recordings inbox if persistence fails.
                if (take.exists()) {
                    if (take.length() == 0L) take.delete() else runCatching { drafts.finish(take) }
                }
                mutableState.value = previous.copy(phase = QuickRecordingPhase.FAILED)
            }
        }
    }

    fun sampleAmplitude() {
        if (state.value.phase == QuickRecordingPhase.RECORDING) runCatching { recorder.getMaxAmplitude() }
    }

    companion object {
        const val DEFAULT_DELAY_MS = 10 * 60_000L
        const val MAX_RECORDING_MS = 5 * 60_000L
    }
}
