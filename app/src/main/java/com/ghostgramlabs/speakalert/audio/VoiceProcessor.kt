package com.ghostgramlabs.speakalert.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voice cleanup itself: everything that happens to a reminder between the microphone and
 * the file, expressed as plain arithmetic on samples.
 *
 * This is deliberately free of any Android media class so it can be run and checked directly in
 * unit tests, which is where the maths is actually verified. [Mp4AudioEnhancer] owns the codec
 * plumbing and does nothing but feed this.
 *
 * Three stages, in order:
 *
 *  1. **High-pass at 90 Hz.** Handling thumps, pocket rustle and mains hum live below speech and
 *     do nothing but eat headroom.
 *  2. **Spectral subtraction.** Estimates the steady background noise of the room and removes it
 *     from each short slice of the recording, keeping the phase intact. This is what takes out
 *     fan, hiss, traffic and air conditioning from underneath the voice.
 *  3. **Loudness normalisation.** Lifts the result to a consistent, room-audible level.
 *
 * Usage is two passes over the same audio: [analyze] every sample first, then [Analysis] from
 * [finishAnalysis] drives [render].
 */
class VoiceProcessor(private val sampleRate: Int) {

    /** What the first pass learned about the recording. */
    data class Analysis(
        val noiseMagnitudes: FloatArray?,
        val peak: Float,
        val frameCount: Int,
        /**
         * What the room alone measures, in the same units as [peak]: the peak of the quietest
         * frame in each window, averaged over the windows. Zero when the recording was too short
         * to contain a stretch that is plausibly background rather than speech.
         */
        val noiseFloor: Float = 0f
    ) {
        val canDenoise: Boolean get() = noiseMagnitudes != null
    }

    private val fft = SHARED_FFT
    private val window = FloatArray(FRAME_SIZE) { index ->
        // Root-Hann. Applied on both analysis and synthesis it squares to a Hann window, which
        // overlap-adds back to exactly 1.0 at this hop - so unmodified audio passes through
        // untouched and only the changes we actually make are audible.
        sqrt(0.5f * (1f - cos(2.0 * PI * index / FRAME_SIZE).toFloat()))
    }

    private val highPass = Biquad.highPass(HIGH_PASS_HZ, sampleRate)

    // Framing state, shared by both passes.
    private val pending = FloatArray(FRAME_SIZE * 2)
    private var pendingSize = 0
    private val real = FloatArray(FRAME_SIZE)
    private val imaginary = FloatArray(FRAME_SIZE)
    private val magnitude = FloatArray(BIN_COUNT)

    // First pass. The noise estimate is the average of the quietest moment in each half-second
    // window rather than the single quietest moment overall: a plain global minimum keeps
    // drifting lower the longer someone talks, which would make the same room sound like
    // different amounts of noise depending on how long the reminder was.
    private val windowMinimum = FloatArray(BIN_COUNT) { Float.MAX_VALUE }
    private val noiseTotals = FloatArray(BIN_COUNT)
    private var noiseWindows = 0
    private var framesInWindow = 0
    private var peak = 0f
    private var frames = 0
    // The level counterpart of the spectral estimate above, measured the same way: the quietest
    // frame in each window, averaged across windows. Tracked in the time domain because the gain
    // it caps is applied to samples, not to bins.
    private var framePeakMinimum = Float.MAX_VALUE
    private var noiseFloorTotal = 0f

    // Second pass.
    private var noise: FloatArray? = null
    private var gain = 1f
    private val previousGains = FloatArray(BIN_COUNT) { 1f }
    private val overlap = FloatArray(FRAME_SIZE)
    private var overlapPrimed = false
    private var renderInputSamples = 0L
    private var renderOutputSamples = 0L
    private val directOutput = FloatArray(FRAME_SIZE)
    private val overlapOutput = FloatArray(HOP)

    // ---------------------------------------------------------------- analysis pass

    /** Feed every sample of the recording through this first, in order. */
    fun analyze(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val taken = fill(samples, offset, count, trackPeak = true)
            offset += taken
            while (pendingSize >= FRAME_SIZE) {
                loadFrame()
                fft.forward(real, imaginary)
                magnitudes()
                for (bin in 0 until BIN_COUNT) {
                    // The quietest this bin gets within a window is, by definition, a moment
                    // where only the room is audible. That is the noise.
                    if (magnitude[bin] < windowMinimum[bin]) windowMinimum[bin] = magnitude[bin]
                }
                // Same reasoning, in level rather than spectrum: the quietest frame of the
                // window is a moment with no voice in it, so its peak is what the room measures.
                var framePeak = 0f
                for (i in 0 until FRAME_SIZE) {
                    val level = abs(pending[i])
                    if (level > framePeak) framePeak = level
                }
                if (framePeak < framePeakMinimum) framePeakMinimum = framePeak
                frames++
                if (++framesInWindow >= NOISE_WINDOW_FRAMES) closeNoiseWindow()
                consume()
            }
        }
    }

    fun finishAnalysis(): Analysis {
        flushPending { sample ->
            val level = abs(sample)
            if (level > peak) peak = level
        }
        if (framesInWindow > 0) closeNoiseWindow()

        // Too short to tell background from speech; a fraction of a second of audio gives the
        // estimator nothing to work with and guessing would damage the voice.
        val usable = frames >= MIN_FRAMES_FOR_NOISE_ESTIMATE && noiseWindows > 0
        val estimate = if (usable) {
            val averaged = FloatArray(BIN_COUNT) { bin -> noiseTotals[bin] / noiseWindows }
            FloatArray(BIN_COUNT) { bin ->
                // Smooth across neighbouring bins so one unlucky minimum cannot punch a hole in
                // the estimate, then lean high: the quietest moment in a window still sits below
                // the true average noise level, so it needs correcting upward.
                val low = averaged[max(0, bin - 1)]
                val high = averaged[min(BIN_COUNT - 1, bin + 1)]
                ((low + averaged[bin] + high) / 3f) * NOISE_OVERESTIMATE
            }
        } else {
            null
        }
        val floor = if (usable) noiseFloorTotal / noiseWindows else 0f
        return Analysis(estimate, peak, frames, floor)
    }

    // ---------------------------------------------------------------- render pass

    /**
     * Prepare for the second pass. [outputGain] is applied after denoising; derive it from
     * [Analysis.peak] with [normalizationGain].
     */
    fun beginRender(analysis: Analysis, outputGain: Float) {
        reset()
        noise = analysis.noiseMagnitudes
        gain = outputGain
        if (noise != null) {
            // Centre the first analysis frame on the beginning of the recording. The matching
            // padding in finishRender lets overlap-add retain the first and last half-frame.
            java.util.Arrays.fill(pending, 0, HOP, 0f)
            pendingSize = HOP
        }
    }

    /**
     * Process the next block of input, handing finished output to [onOutput]. Output lags input
     * by one frame because of the overlap-add, so [finishRender] must be called at the end.
     * Output buffers are reused; consume them synchronously or copy the valid samples.
     */
    fun render(samples: ShortArray, count: Int, onOutput: (FloatArray, Int) -> Unit) {
        require(count in 0..samples.size) { "count must describe samples in the input array" }
        renderInputSamples += count

        // Short takes do not have a trustworthy noise estimate. Stream the high-pass and gain
        // directly so they remain sample-exact and do not pay the FFT's frame latency.
        if (noise == null) {
            var offset = 0
            while (offset < count) {
                val chunk = min(directOutput.size, count - offset)
                for (i in 0 until chunk) {
                    directOutput[i] = clamp(highPass.process(samples[offset + i].toFloat()) * gain)
                }
                onOutput(directOutput, chunk)
                renderOutputSamples += chunk
                offset += chunk
            }
            return
        }

        var offset = 0
        while (offset < count) {
            val taken = fill(samples, offset, count, trackPeak = false)
            offset += taken
            while (pendingSize >= FRAME_SIZE) {
                loadFrame()
                val cleaned = noise
                if (cleaned != null) {
                    fft.forward(real, imaginary)
                    subtract(cleaned)
                    fft.inverse(real, imaginary)
                }
                overlapAdd(onOutput)
                consume()
            }
        }
    }

    /** Emit the final partial frame held back by the overlap. */
    fun finishRender(onOutput: (FloatArray, Int) -> Unit) {
        if (noise == null) {
            return
        }

        // Complete the final frame with silence. Together with the leading half-frame inserted
        // in beginRender this preserves both boundaries instead of trimming the first consonant
        // and the end of the reminder.
        while (pendingSize < FRAME_SIZE) pending[pendingSize++] = 0f
        loadFrame()
        fft.forward(real, imaginary)
        subtract(checkNotNull(noise))
        fft.inverse(real, imaginary)
        overlapAdd(onOutput)
        consume()

        if (overlapPrimed) {
            val tail = FloatArray(FRAME_SIZE - HOP)
            for (i in tail.indices) tail[i] = clamp(overlap[i] * gain)
            emitProcessed(tail, tail.size, onOutput)
        }
        pendingSize = 0
    }

    /** How much to lift the recording, or null when it is already at a good level. */
    fun normalizationGain(peak: Float): Float? = normalizationGain(peak, noiseFloor = 0f)

    /**
     * How much to lift the recording, held back so the lift never makes the room audible.
     *
     * Normalisation multiplies everything, background included, so a quiet take recorded in a
     * noisy room is exactly the case where a large gain turns a barely-there hiss into an
     * obvious one. The lift is therefore capped at what keeps the residual room under
     * [NOISE_CEILING] rather than at what would make the voice loudest.
     *
     * This only ever declines to amplify. Nothing is attenuated and no frequency is removed, so
     * a take that is already clean still normalises exactly as before, and a take that is not
     * comes back no quieter than it was recorded.
     */
    fun normalizationGain(peak: Float, noiseFloor: Float): Float? {
        if (peak <= 0f) return null
        val desired = TARGET_PEAK / peak
        if (desired <= MIN_WORTHWHILE_GAIN) return null
        val ceiling = if (noiseFloor > 0f) {
            max(1f, NOISE_CEILING / noiseFloor)
        } else {
            MAX_GAIN
        }
        val allowed = min(min(desired, MAX_GAIN), ceiling)
        // Below this the lift is not worth a re-encode; the caller leaves the take alone unless
        // it still has denoising to do.
        if (allowed <= MIN_WORTHWHILE_GAIN) return null
        return allowed
    }

    // ---------------------------------------------------------------- internals

    /** Bank this window's quietest reading and start the next one. */
    private fun closeNoiseWindow() {
        for (bin in 0 until BIN_COUNT) {
            val value = windowMinimum[bin]
            if (value != Float.MAX_VALUE) noiseTotals[bin] += value
            windowMinimum[bin] = Float.MAX_VALUE
        }
        if (framePeakMinimum != Float.MAX_VALUE) noiseFloorTotal += framePeakMinimum
        framePeakMinimum = Float.MAX_VALUE
        noiseWindows++
        framesInWindow = 0
    }

    private fun reset() {
        pendingSize = 0
        overlapPrimed = false
        renderInputSamples = 0L
        renderOutputSamples = 0L
        java.util.Arrays.fill(overlap, 0f)
        java.util.Arrays.fill(previousGains, 1f)
        highPass.reset()
    }

    /** Copy input into the frame buffer, high-passing on the way in. */
    private fun fill(samples: ShortArray, offset: Int, count: Int, trackPeak: Boolean): Int {
        val room = pending.size - pendingSize
        val taken = min(room, count - offset)
        for (i in 0 until taken) {
            val filtered = highPass.process(samples[offset + i].toFloat())
            pending[pendingSize + i] = filtered
            if (trackPeak) {
                val level = abs(filtered)
                if (level > peak) peak = level
            }
        }
        pendingSize += taken
        return taken
    }

    private fun loadFrame() {
        for (i in 0 until FRAME_SIZE) {
            real[i] = pending[i] * window[i]
            imaginary[i] = 0f
        }
    }

    private fun consume() {
        System.arraycopy(pending, HOP, pending, 0, pendingSize - HOP)
        pendingSize -= HOP
    }

    private fun magnitudes() {
        for (bin in 0 until BIN_COUNT) {
            magnitude[bin] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
        }
    }

    /**
     * Scale each frequency bin down by how much of it is noise, leaving phase alone. A bin that
     * is mostly voice passes through; a bin that is mostly room gets pushed toward the floor.
     */
    private fun subtract(noiseEstimate: FloatArray) {
        magnitudes()
        for (bin in 0 until BIN_COUNT) {
            val level = magnitude[bin]
            val target = if (level <= 1e-6f) {
                SPECTRAL_FLOOR
            } else {
                val clean = level - subtractionStrength(level, noiseEstimate[bin]) * noiseEstimate[bin]
                max(clean / level, SPECTRAL_FLOOR)
            }
            // Ease each bin's gain toward its new value instead of snapping. Abrupt per-frame
            // changes are what turns spectral subtraction into warbling "musical noise".
            val smoothed = GAIN_SMOOTHING * previousGains[bin] + (1f - GAIN_SMOOTHING) * target
            previousGains[bin] = smoothed

            real[bin] *= smoothed
            imaginary[bin] *= smoothed
            // Keep the spectrum conjugate-symmetric so the inverse transform stays real.
            if (bin in 1 until BIN_COUNT - 1) {
                val mirror = FRAME_SIZE - bin
                real[mirror] *= smoothed
                imaginary[mirror] *= smoothed
            }
        }
    }

    /**
     * How hard to subtract from one bin, decided by how much of that bin is voice.
     *
     * A single strength for the whole spectrum has to choose between leaving the room in and
     * chewing the voice up, because the bins carrying a vowel and the bins carrying only a fan
     * get the same treatment. Grading it by signal-to-noise splits that decision: a bin well
     * above the noise estimate is speech and gets only its measured noise taken out, while a bin
     * sitting at the estimate is the room and gets pushed well under it.
     *
     * The effect on what the user hears is that the steady background goes further down than a
     * flat strength could manage, and the voice is handled more gently than it was before.
     */
    private fun subtractionStrength(level: Float, noiseLevel: Float): Float {
        if (noiseLevel <= 1e-6f) return SUBTRACTION_GENTLE
        val snr = level / noiseLevel
        if (snr <= SNR_ALL_NOISE) return SUBTRACTION_AGGRESSIVE
        if (snr >= SNR_ALL_VOICE) return SUBTRACTION_GENTLE
        val across = (snr - SNR_ALL_NOISE) / (SNR_ALL_VOICE - SNR_ALL_NOISE)
        return SUBTRACTION_AGGRESSIVE - across * (SUBTRACTION_AGGRESSIVE - SUBTRACTION_GENTLE)
    }

    private fun overlapAdd(onOutput: (FloatArray, Int) -> Unit) {
        for (i in 0 until FRAME_SIZE) {
            overlap[i] += real[i] * window[i]
        }
        if (!overlapPrimed) {
            // The first frame's leading hop has not been overlapped by anything yet; holding it
            // back keeps the reconstruction exact.
            overlapPrimed = true
        } else {
            for (i in 0 until HOP) overlapOutput[i] = clamp(overlap[i] * gain)
            emitProcessed(overlapOutput, HOP, onOutput)
        }
        System.arraycopy(overlap, HOP, overlap, 0, FRAME_SIZE - HOP)
        java.util.Arrays.fill(overlap, FRAME_SIZE - HOP, FRAME_SIZE, 0f)
    }

    /** Emit no more samples than were supplied by the caller, trimming only FFT padding. */
    private fun emitProcessed(
        samples: FloatArray,
        count: Int,
        onOutput: (FloatArray, Int) -> Unit
    ) {
        val remaining = (renderInputSamples - renderOutputSamples).coerceAtLeast(0L)
        val emitted = min(count.toLong(), remaining).toInt()
        if (emitted > 0) {
            onOutput(samples, emitted)
            renderOutputSamples += emitted
        }
    }

    /** Drain whatever never made up a full frame. */
    private inline fun flushPending(onSample: (Float) -> Unit) {
        for (i in 0 until pendingSize) onSample(pending[i])
        pendingSize = 0
    }

    private fun clamp(value: Float): Float {
        return when {
            value > MAX_PCM -> MAX_PCM
            value < MIN_PCM -> MIN_PCM
            else -> value
        }
    }

    /** Direct-form-1 biquad. */
    private class Biquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float
    ) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun reset() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }

        fun process(x0: Float): Float {
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            return y0
        }

        companion object {
            /** Standard RBJ high-pass at Butterworth Q, so the passband stays flat. */
            fun highPass(cutoffHz: Float, sampleRate: Int): Biquad {
                val omega = 2.0 * PI * cutoffHz / sampleRate
                val cosOmega = cos(omega)
                val alpha = sin(omega) / (2.0 * FILTER_Q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = (((1.0 + cosOmega) / 2.0) / a0).toFloat(),
                    b1 = ((-(1.0 + cosOmega)) / a0).toFloat(),
                    b2 = (((1.0 + cosOmega) / 2.0) / a0).toFloat(),
                    a1 = ((-2.0 * cosOmega) / a0).toFloat(),
                    a2 = ((1.0 - alpha) / a0).toFloat()
                )
            }
        }
    }

    companion object {
        const val FRAME_SIZE = 1024
        private val SHARED_FFT = Radix2Fft(FRAME_SIZE)
        const val HOP = FRAME_SIZE / 2
        const val BIN_COUNT = FRAME_SIZE / 2 + 1

        /** Below this there is no speech, only rumble, handling noise and mains hum. */
        const val HIGH_PASS_HZ = 90f
        private const val FILTER_Q = 0.7071

        /** Roughly a quarter second at 44.1 kHz - less than that cannot describe a room. */
        const val MIN_FRAMES_FOR_NOISE_ESTIMATE = 20
        /** About half a second: long enough to contain a gap between words. */
        private const val NOISE_WINDOW_FRAMES = 43
        /**
         * The quietest moment in a window still sits under the room's average level, so the
         * estimate is corrected upward. Together with [SUBTRACTION_STRENGTH] this is the
         * balance between audible noise and a hollowed-out voice - raise them and the
         * background disappears but speech starts to sound thin and watery.
         */
        private const val NOISE_OVERESTIMATE = 1.5f
        /**
         * Subtraction multipliers, picked per bin by [subtractionStrength]. The gentle end is
         * below the old flat 1.5 so speech keeps more of itself than it used to; the aggressive
         * end is above it so the room loses more than it used to.
         */
        private const val SUBTRACTION_GENTLE = 1.0f
        private const val SUBTRACTION_AGGRESSIVE = 2.5f

        /** A bin at or under the noise estimate is room; four times over it is voice. */
        private const val SNR_ALL_NOISE = 1.0f
        private const val SNR_ALL_VOICE = 4.0f
        /** Leave ~26 dB of the original noise rather than a silence full of artefacts. */
        private const val SPECTRAL_FLOOR = 0.05f
        private const val GAIN_SMOOTHING = 0.5f

        /** About -1 dBFS: loud and consistent, with headroom left for the encoder. */
        const val TARGET_PEAK = 29_000f

        /**
         * The loudest the room is allowed to end up after normalisation, on the same 0..32767
         * scale as the samples. Around -38 dBFS: audible if you hunt for it on headphones in a
         * silent room, nowhere near audible from a phone speaker across a kitchen, which is
         * where these reminders actually play.
         */
        const val NOISE_CEILING = 400f
        /** Not worth a re-encode for less than roughly 1 dB of change. */
        private const val MIN_WORTHWHILE_GAIN = 1.12f
        /** ~18 dB. Beyond this a near-silent take just gets a louder noise floor. */
        private const val MAX_GAIN = 8f

        private const val MAX_PCM = 32_767f
        private const val MIN_PCM = -32_768f
    }
}
