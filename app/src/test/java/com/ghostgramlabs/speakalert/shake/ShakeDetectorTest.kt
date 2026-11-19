package com.ghostgramlabs.speakalert.shake

import org.junit.Assert.*
import org.junit.Test

class ShakeDetectorTest {
    /** Continuous 50 Hz traces, with gravity added to simulated linear acceleration in g. */
    private class Trace(val detector: ShakeDetector = ShakeDetector(), val gravity: FloatArray = floatArrayOf(0f, 0f, 1f)) {
        var time = 0L
        val triggers = mutableListOf<Long>()
        fun move(ms: Int, x: Float = 0f, y: Float = 0f, z: Float = 0f) {
            repeat(ms / 20) {
                if (detector.sample((x + gravity[0]) * G, (y + gravity[1]) * G, (z + gravity[2]) * G, time)) {
                    triggers += time
                }
                time += 20
            }
        }
        fun flick(sign: Float = 1f, force: Float = 3.4f) {
            move(40, sign * force)
            move(60, -sign * 1.3f) // Braking is part of this flick.
            move(120)
        }
        fun gesture(sign: Float = 1f, force: Float = 3.4f) {
            flick(sign, force)
            move(160, -sign * 0.7f) // Lift back gently.
            move(120)
            flick(sign, force)
            move(500)
        }
    }

    @Test fun `two same direction width flicks with a return and pause trigger for either hand`() {
        for (sign in listOf(-1f, 1f)) {
            val trace = Trace()
            trace.move(1_000)
            trace.gesture(sign)
            assertEquals(1, trace.triggers.size)
            assertTrue(trace.triggers.single() >= 1_880L)
        }
    }

    @Test fun `gravity orientation does not change the width gesture`() {
        for (gravity in listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0.6f, 0.8f))) {
            val trace = Trace(gravity = gravity)
            trace.move(1_000)
            trace.gesture()
            assertEquals(1, trace.triggers.size)
        }
    }

    @Test fun `quick return before 180 ms still connects two deliberate flicks`() {
        for (sign in listOf(-1f, 1f)) {
            val trace = Trace()
            trace.move(1_000)
            trace.flick(sign)
            trace.move(80)
            trace.flick(sign)
            trace.move(600)
            assertEquals(1, trace.triggers.size)
        }
    }

    @Test fun `strong return does not cancel the first flick`() {
        val trace = Trace()
        trace.move(1_000)
        trace.flick()
        trace.move(80, -3.4f)
        trace.move(200)
        trace.flick()
        trace.move(600)
        assertEquals(1, trace.triggers.size)
    }

    @Test fun `off axis leading edge does not swallow a width dominant peak`() {
        val trace = Trace()
        trace.move(1_000)
        repeat(2) {
            trace.move(20, 2.5f, 1.2f)
            trace.flick()
            trace.move(160, -0.7f)
            trace.move(120)
        }
        trace.move(600)
        assertEquals(1, trace.triggers.size)
    }

    @Test fun `single flick and its opposite braking peak do not form a gesture`() {
        val trace = Trace()
        trace.move(1_000)
        trace.move(40, 3.5f)
        trace.move(40)
        trace.move(40, -3.5f)
        trace.move(2_000)
        assertTrue(trace.triggers.isEmpty())
    }

    @Test fun `same direction rebound from a single flick is still too soon`() {
        val trace = Trace()
        trace.move(1_000)
        trace.move(40, 3.5f)
        trace.move(60, -1.3f)
        trace.move(40)
        trace.move(40, 3.5f)
        trace.move(1_000)
        assertTrue(trace.triggers.isEmpty())
    }

    @Test fun `opposite direction flicks and two bumps without a return are rejected`() {
        val opposite = Trace()
        opposite.move(1_000)
        opposite.flick()
        opposite.move(160, -0.7f)
        opposite.move(120)
        opposite.flick(-1f)
        opposite.move(1_000)
        assertTrue(opposite.triggers.isEmpty())

        val bumps = Trace()
        bumps.move(1_000)
        bumps.move(20, 3.4f)
        bumps.move(480)
        bumps.move(20, 3.4f)
        bumps.move(1_000)
        assertTrue(bumps.triggers.isEmpty())
    }

    @Test fun `lengthwise facewise and diagonal pocket bumps are rejected`() {
        for (axis in listOf(floatArrayOf(0f, 3.5f, 0f), floatArrayOf(0f, 0f, 3.5f), floatArrayOf(3.4f, 3f, 1f))) {
            val trace = Trace()
            trace.move(1_000)
            repeat(8) {
                trace.move(40, axis[0], axis[1], axis[2])
                trace.move(160)
                trace.move(160, -0.7f)
                trace.move(140)
            }
            trace.move(1_000)
            assertTrue(trace.triggers.isEmpty())
        }
    }

    @Test fun `continuous running motion cannot finish a candidate gesture`() {
        val trace = Trace()
        trace.move(1_000)
        repeat(12) {
            trace.flick()
            trace.move(160, -0.9f)
            trace.move(120, 0f, 1.1f)
        }
        trace.move(1_000)
        assertTrue(trace.triggers.isEmpty())
    }

    @Test fun `gravity walking and sustained force do not trigger`() {
        val trace = Trace()
        trace.move(1_000)
        repeat(20) {
            trace.move(200, 0.4f, 0.2f, 0.5f)
            trace.move(200, -0.4f, -0.2f, -0.5f)
        }
        trace.move(2_000, 3.5f)
        trace.move(2_000)
        assertTrue(trace.triggers.isEmpty())
    }

    @Test fun `expired first flick cannot be paired with a later one`() {
        val trace = Trace()
        trace.move(1_000)
        trace.flick()
        trace.move(160, -0.7f)
        trace.move(1_300)
        trace.flick()
        trace.move(1_000)
        assertTrue(trace.triggers.isEmpty())
    }

    @Test fun `cooldown prevents one gesture from retriggering and permits a later gesture`() {
        val trace = Trace()
        trace.move(1_000)
        trace.gesture()
        trace.gesture()
        assertEquals(1, trace.triggers.size)
        trace.move(2_000)
        trace.gesture()
        assertEquals(2, trace.triggers.size)
    }

    @Test fun `reset cancels a partial gesture`() {
        val trace = Trace()
        trace.move(1_000)
        trace.flick()
        trace.detector.reset(trace.time)
        trace.move(160, -0.7f)
        trace.move(120)
        trace.flick()
        trace.move(2_500)
        assertTrue(trace.triggers.isEmpty())
        trace.gesture()
        assertEquals(1, trace.triggers.size)
    }

    @Test fun `sensor gap or invalid samples cannot supply the finishing pause`() {
        for (invalid in listOf<Float?>(null, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
            val trace = Trace()
            trace.move(1_000)
            trace.flick()
            trace.move(160, -0.7f)
            trace.move(120)
            trace.flick()
            if (invalid == null) trace.time += 500
            else {
                assertFalse(trace.detector.sample(invalid, 0f, G, trace.time))
                trace.time += 20
            }
            trace.move(1_000)
            assertTrue(trace.triggers.isEmpty())
            trace.gesture()
            assertEquals(1, trace.triggers.size)
        }
    }

    @Test fun `custom force and cooldown support repeated complete gestures`() {
        val trace = Trace(ShakeDetector(thresholdG = 2f, cooldownMs = 200L))
        trace.move(1_000)
        trace.gesture(force = 2.4f)
        trace.move(240)
        trace.gesture(force = 2.4f)
        assertEquals(2, trace.triggers.size)
        val standard = Trace()
        standard.move(1_000)
        standard.gesture(force = 2.4f)
        assertTrue(standard.triggers.isEmpty())
    }

    private companion object { const val G = 9.80665f }
}
