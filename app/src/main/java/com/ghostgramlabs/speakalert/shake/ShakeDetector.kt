package com.ghostgramlabs.speakalert.shake

import kotlin.math.abs

/**
 * Two width-wise flicks in the same direction, with a return and a quiet finish.
 * Uses the phone's physical X axis (independent of display rotation), accepting either grip.
 * Estimates gravity from raw samples to retain the existing wake-up accelerometer support.
 * This is a gesture heuristic, not pocket detection.
 */
class ShakeDetector(private val thresholdG: Float = 2.7f, private val cooldownMs: Long = 2_000L) {
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var lastSampleMs: Long? = null
    private var aboveThreshold = false
    private var firstPeakMs: Long? = null
    private var direction = 0f
    private var returned = false
    private var secondPeakMs: Long? = null
    private var quietSinceMs: Long? = null
    private var ignoreUntilMs = 0L

    fun reset(nowMs: Long) {
        clearGesture()
        aboveThreshold = false
        ignoreUntilMs = nowMs + cooldownMs
    }

    private fun clearGesture() {
        firstPeakMs = null
        secondPeakMs = null
        quietSinceMs = null
        returned = false
        direction = 0f
    }

    fun sample(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            clearGesture()
            lastSampleMs = null
            return false
        }
        val previous = lastSampleMs
        if (previous != null && nowMs <= previous) return false
        lastSampleMs = nowMs
        // Missing samples cannot prove a return or a quiet finish. Relearn gravity after gaps.
        if (previous == null || nowMs - previous > 250L) {
            gravityX = x
            gravityY = y
            gravityZ = z
            clearGesture()
            aboveThreshold = false
            ignoreUntilMs = maxOf(ignoreUntilMs, nowMs + 300L)
            return false
        }
        val dx = (x - gravityX) / GRAVITY
        val dy = (y - gravityY) / GRAVITY
        val dz = (z - gravityZ) / GRAVITY
        val alpha = (nowMs - previous).toFloat() / (800f + nowMs - previous)
        gravityX += alpha * (x - gravityX)
        gravityY += alpha * (y - gravityY)
        gravityZ += alpha * (z - gravityZ)
        val forceSquared = dx * dx + dy * dy + dz * dz
        if (!forceSquared.isFinite()) {
            clearGesture()
            lastSampleMs = null
            return false
        }
        if (forceSquared < 1f) aboveThreshold = false
        if (nowMs < ignoreUntilMs) return false

        val second = secondPeakMs
        if (second != null) {
            if (nowMs - second > 800L) {
                clearGesture()
            } else {
                // Allow the short braking impulse of the second flick, then require stillness.
                if (nowMs - second >= 180L) {
                    if (forceSquared >= thresholdG * thresholdG) {
                        clearGesture()
                        aboveThreshold = true
                    } else if (forceSquared <= 0.65f * 0.65f) {
                        val quiet = quietSinceMs
                        if (quiet == null) quietSinceMs = nowMs
                        else if (nowMs - quiet >= 200L) {
                            reset(nowMs)
                            return true
                        }
                    } else quietSinceMs = null
                }
                return false
            }
        }

        val first = firstPeakMs
        if (first != null && nowMs - first > 1_200L) clearGesture()
        // A natural return can begin as soon as the first flick brakes, and may itself
        // exceed the force threshold. It is evidence of a return, never a second flick.
        // Same-direction peaks still need 240 ms separation and a quiet finish below.
        if (firstPeakMs != null && dx * direction < -0.35f) {
            returned = true
            return false
        }
        if (forceSquared < thresholdG * thresholdG || aboveThreshold) return false
        val widthDominant = abs(dx) >= thresholdG && dx * dx >= 2.25f * (dy * dy + dz * dz)
        // Wait for the width-axis peak instead of latching an off-axis leading sample.
        if (!widthDominant) return false
        aboveThreshold = true
        // Do not count the braking impulse from one flick as another flick.
        if (firstPeakMs != null && nowMs - firstPeakMs!! < 240L) return false
        if (firstPeakMs == null) {
            firstPeakMs = nowMs
            direction = if (dx > 0) 1f else -1f
        } else if (dx * direction > 0 && returned) {
            secondPeakMs = nowMs
        } else {
            clearGesture()
        }
        return false
    }

    private companion object { const val GRAVITY = 9.80665f }
}
