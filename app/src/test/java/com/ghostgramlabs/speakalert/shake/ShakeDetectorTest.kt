package com.ghostgramlabs.speakalert.shake

import org.junit.Assert.*
import org.junit.Test

class ShakeDetectorTest {
    private val detector = ShakeDetector()
    private fun peak(at: Long) = detector.sample(30f, 0f, 0f, at)
    private fun rest(at: Long) = detector.sample(0f, 0f, 9.80665f, at)

    @Test fun `two separated peaks trigger in any direction`() {
        assertFalse(peak(1_000))
        rest(1_100)
        assertTrue(detector.sample(0f, -30f, 0f, 1_300))
    }

    @Test fun `gravity walking single bumps and sustained force do not trigger`() {
        for (t in 0L..2_000L step 20) assertFalse(detector.sample(5f, 5f, 12f, t))
        assertFalse(peak(3_000))
        for (t in 3_020L..5_000L step 20) assertFalse(peak(t))
        rest(5_100)
        assertFalse(peak(6_000))
    }

    @Test fun `sensor chatter is rejected and expired gestures start over`() {
        assertFalse(peak(1_000))
        rest(1_020)
        assertFalse(peak(1_040))
        rest(1_100)
        assertFalse(peak(3_000))
        rest(3_100)
        assertTrue(peak(3_300))
    }

    @Test fun `cooldown prevents the same shake from stopping a new recording`() {
        peak(1_000)
        rest(1_100)
        assertTrue(peak(1_300))
        rest(1_400)
        assertFalse(peak(1_600))
        rest(3_400)
        assertFalse(peak(3_500))
        rest(3_600)
        assertTrue(peak(3_800))
    }

    @Test fun `reset clears a partial gesture and ignores invalid samples`() {
        peak(1_000)
        detector.reset(1_100)
        assertFalse(detector.sample(Float.NaN, 0f, 0f, 4_000))
        assertFalse(detector.sample(Float.POSITIVE_INFINITY, 0f, 0f, 4_100))
        assertFalse(peak(4_200))
    }

    @Test fun `custom force and cooldown allow distinct repeated gestures`() {
        val custom = ShakeDetector(thresholdG = 2f, cooldownMs = 500L)
        assertFalse(custom.sample(21f, 0f, 0f, 1_000))
        custom.sample(0f, 0f, 9.8f, 1_100)
        assertTrue(custom.sample(21f, 0f, 0f, 1_300))
        custom.sample(0f, 0f, 9.8f, 1_400)
        assertFalse(custom.sample(21f, 0f, 0f, 1_600))
        custom.sample(0f, 0f, 9.8f, 1_900)
        assertFalse(custom.sample(21f, 0f, 0f, 2_000))
        custom.sample(0f, 0f, 9.8f, 2_100)
        assertTrue(custom.sample(21f, 0f, 0f, 2_300))
    }
}
