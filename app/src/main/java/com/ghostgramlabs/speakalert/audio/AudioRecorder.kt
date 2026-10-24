package com.ghostgramlabs.speakalert.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

interface AudioRecorder {
    fun start(outputFile: File, useExperimentalBehavior: Boolean)
    fun stop(): RecordingOutcome
    fun getMaxAmplitude(): Int
}

/**
 * What a finished take actually contained. [isSilent] is the important one: several OEMs hand
 * back a running recorder that never delivers any level, either because a restricted microphone
 * permission is silently enforced or because another app owns the capture path.
 */
data class RecordingOutcome(
    val file: File?,
    val peakAmplitude: Int,
    val sizeBytes: Long
) {
    val isSilent: Boolean
        get() = file != null && peakAmplitude < SILENCE_PEAK_THRESHOLD

    companion object {
        /**
         * MediaRecorder peaks run 0..32767. This is deliberately near the floor: it must catch
         * a capture path that returned nothing at all, without ever second-guessing someone who
         * recorded a whisper in a quiet room. Real audio, however soft, clears it easily.
         */
        const val SILENCE_PEAK_THRESHOLD = 40
        val NOTHING = RecordingOutcome(file = null, peakAmplitude = 0, sizeBytes = 0L)
    }
}

/**
 * Captures on [MediaRecorder.AudioSource.MIC] and nothing else.
 *
 * The speech-tuned sources (VOICE_COMMUNICATION, VOICE_RECOGNITION) hand the recording to the
 * device's own audio HAL, and what comes back differs wildly between manufacturers - including
 * devices where the recorder starts cleanly and then delivers pure silence. MIC is the one
 * source every Android device supports the same way, so the noise reduction and levelling now
 * happen in our own code afterwards, in [VoiceProcessor], where the result is identical on
 * every phone and can never cost the user their reminder.
 */
class AndroidAudioRecorder(
    private val context: Context
): AudioRecorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var peakAmplitude: Int = 0
    private var useExperimentalBehavior: Boolean = false
    private var legacyOutputStream: FileOutputStream? = null

    private fun createRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }

    override fun start(outputFile: File, useExperimentalBehavior: Boolean) {
        check(recorder == null) { "A recording is already in progress" }
        RecordingOwner.acquire(this)
        try {
            startOwned(outputFile, useExperimentalBehavior)
        } catch (failure: Throwable) {
            RecordingOwner.release(this)
            throw failure
        }
    }

    private fun startOwned(outputFile: File, useExperimentalBehavior: Boolean) {
        outputFile.parentFile?.mkdirs()
        peakAmplitude = 0
        this.useExperimentalBehavior = useExperimentalBehavior

        if (!useExperimentalBehavior) {
            startLegacy(outputFile)
            return
        }

        // The only variation left is the encoder settings: a small number of devices reject an
        // explicitly requested AAC sample rate or bitrate, so fall back to MediaRecorder's own
        // defaults rather than failing.
        var lastFailure: Exception? = null
        for (highQuality in listOf(true, false)) {
            outputFile.delete()
            val candidate = createRecorder()
            try {
                configure(candidate, outputFile, highQuality)
                candidate.prepare()
                candidate.start()
                recorder = candidate
                this.outputFile = outputFile
                return
            } catch (failure: Exception) {
                lastFailure?.let(failure::addSuppressed)
                lastFailure = failure
                Log.w(TAG, "Microphone capture failed (highQuality=$highQuality)", failure)
                candidate.releaseSafely()
            }
        }

        outputFile.delete()
        throw lastFailure ?: IllegalStateException("The microphone is unavailable")
    }

    /** The raw MIC/AAC configuration shipped at commit 300df35. */
    private fun startLegacy(outputFile: File) {
        val stream = FileOutputStream(outputFile)
        val candidate = createRecorder()
        try {
            candidate.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(stream.fd)
                prepare()
                start()
            }
            recorder = candidate
            this.outputFile = outputFile
            legacyOutputStream = stream
        } catch (failure: Exception) {
            runCatching { candidate.release() }
            runCatching { stream.close() }
            throw failure
        }
    }

    override fun stop(): RecordingOutcome {
        val activeRecorder = recorder ?: return RecordingOutcome.NOTHING
        val file = outputFile
        recorder = null
        outputFile = null
        val experimental = useExperimentalBehavior
        useExperimentalBehavior = false
        try {
            // Capture the final interval before stopping. The UI polls every 100 ms, but a fast
            // stop can otherwise leave the last (or only) spoken syllable out of the take peak.
            val finalAmplitude = runCatching { activeRecorder.maxAmplitude }.getOrDefault(0)
            if (finalAmplitude > peakAmplitude) peakAmplitude = finalAmplitude
            activeRecorder.stop()
        } catch (failure: RuntimeException) {
            // Experimental mode rejects an incomplete take. Baseline mode preserves whatever
            // the original recorder wrote, matching the raw-recording behavior from 300df35.
            Log.w(TAG, "Recorder stopped before capturing usable audio", failure)
            if (experimental) {
                file?.delete()
                return RecordingOutcome.NOTHING
            }
            val size = file?.length() ?: 0L
            return RecordingOutcome(file = file, peakAmplitude = peakAmplitude, sizeBytes = size)
        } finally {
            try {
                activeRecorder.releaseSafely()
            } finally {
                runCatching { legacyOutputStream?.close() }
                legacyOutputStream = null
                RecordingOwner.release(this)
            }
        }

        val size = file?.length() ?: 0L
        if (experimental && file != null && size <= 0L) {
            file.delete()
            return RecordingOutcome.NOTHING
        }
        return RecordingOutcome(file = file, peakAmplitude = peakAmplitude, sizeBytes = size)
    }

    override fun getMaxAmplitude(): Int {
        if (!useExperimentalBehavior) return recorder?.maxAmplitude ?: 0

        // Reading also clears the running peak, so remember the loudest value of the take for
        // the silent-capture check in stop().
        val amplitude = try {
            recorder?.maxAmplitude ?: 0
        } catch (_: RuntimeException) {
            0
        }
        if (amplitude > peakAmplitude) peakAmplitude = amplitude
        return amplitude
    }

    private fun configure(recorder: MediaRecorder, outputFile: File, highQuality: Boolean) {
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            if (highQuality) {
                setAudioChannels(CHANNEL_COUNT)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BIT_RATE_BPS)
            }
            setOutputFile(outputFile.absolutePath)
        }
    }

    private fun MediaRecorder.releaseSafely() {
        try {
            reset()
        } catch (_: RuntimeException) {
            // The recorder may not have reached the initialized state.
        }
        release()
    }

    private companion object {
        const val TAG = "AndroidAudioRecorder"
        const val CHANNEL_COUNT = 1
        const val SAMPLE_RATE_HZ = 44_100
        const val BIT_RATE_BPS = 128_000
    }
}
