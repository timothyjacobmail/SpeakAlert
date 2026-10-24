package com.ghostgramlabs.speakalert.shake

/** Two distinct acceleration peaks form a gesture; sustained force and sensor noise do not. */
class ShakeDetector(private val thresholdG: Float = 2.7f, private val cooldownMs: Long = 2_000L) {
    private var aboveThreshold = false
    private var firstPeakMs: Long? = null
    private var ignoreUntilMs = 0L

    fun reset(nowMs: Long) {
        aboveThreshold = false
        firstPeakMs = null
        ignoreUntilMs = nowMs + cooldownMs
    }

    fun sample(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        val gravitySquared = (x * x + y * y + z * z) / (9.80665f * 9.80665f)
        if (!gravitySquared.isFinite()) return false
        if (gravitySquared < 1.5f * 1.5f) aboveThreshold = false
        if (nowMs < ignoreUntilMs || gravitySquared < thresholdG * thresholdG || aboveThreshold) return false
        aboveThreshold = true
        val first = firstPeakMs
        if (first == null || nowMs - first > 1_200L) {
            firstPeakMs = nowMs
            return false
        }
        if (nowMs - first < 180L) return false
        reset(nowMs)
        return true
    }
}
