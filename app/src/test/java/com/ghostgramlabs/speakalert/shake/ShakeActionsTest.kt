package com.ghostgramlabs.speakalert.shake

import org.junit.Assert.*
import org.junit.Test

class ShakeActionsTest {
    private val wall = 1_800_000_037_123L

    @Test fun `default flow preserves five minutes then adds fine and coarse steps`() {
        val engine = ShakeActionEngine(ShakeSettings())
        assertEquals(ShakeAction.START_RECORDING, engine.settings.action(engine.mode, 1))
        engine.recordingStarted()
        assertEquals(ShakeAction.STOP_RECORDING, engine.settings.action(engine.mode, 1))
        engine.recordingStopped(wall, 1_000L)
        assertEquals(wall + 300_000L, engine.triggerAt)
        assertFalse(engine.expired(10_999L))
        assertTrue(engine.expired(11_000L))
        assertTrue(engine.perform(engine.settings.action(engine.mode, 1), wall, 2_000L))
        assertEquals(ShakeMode.MINUTES, engine.mode)
        assertEquals(wall + 300_000L, engine.triggerAt) // Entering edit does not add a step.
        engine.perform(engine.settings.action(engine.mode, 1), wall, 3_000L)
        assertEquals(wall + 600_000L, engine.triggerAt)
        engine.perform(engine.settings.action(engine.mode, 2), wall, 4_000L)
        assertEquals(ShakeMode.HOURS, engine.mode)
        assertEquals(wall + 600_000L, engine.triggerAt) // Switching mode does not add a step.
        engine.perform(engine.settings.action(engine.mode, 1), wall, 5_000L)
        assertEquals(wall + 2_400_000L, engine.triggerAt)
        assertEquals(ShakeAction.FINISH, engine.settings.action(engine.mode, 3))
        engine.finished()
        assertFalse(engine.pending)
        assertNull(engine.deadline)
        assertFalse(engine.perform(ShakeAction.ADD_TIME, wall, 6_000L))
        assertEquals(wall + 2_400_000L, engine.triggerAt)
    }

    @Test fun `assignments are independent for each mode and can be swapped`() {
        val config = ShakeSettings(defaultMinutes = 12, minuteStep = 7, hourStep = 45,
            bindings = ShakeSettings.defaultBindings() + mapOf(
                ShakeBinding(ShakeMode.AFTER_RECORDING, 2) to ShakeAction.ENTER_EDIT,
                ShakeBinding(ShakeMode.MINUTES, 1) to ShakeAction.SWITCH_MODE,
                ShakeBinding(ShakeMode.HOURS, 3) to ShakeAction.ADD_TIME
            ))
        val engine = ShakeActionEngine(config)
        engine.recordingStopped(wall, 0)
        engine.perform(config.action(engine.mode, 2), wall, 1_000)
        engine.perform(config.action(engine.mode, 1), wall, 2_000)
        assertEquals(ShakeMode.HOURS, engine.mode)
        engine.perform(config.action(engine.mode, 3), wall, 3_000)
        assertEquals(wall + 57 * 60_000L, engine.triggerAt)
        assertEquals(ShakeAction.START_RECORDING, config.action(ShakeMode.READY, 1))
    }

    @Test fun `subtract and reset keep time in the future and increments have an upper bound`() {
        val engine = ShakeActionEngine(ShakeSettings(defaultMinutes = 1, minuteStep = 1_440))
        engine.recordingStopped(wall, 0)
        engine.perform(ShakeAction.ENTER_EDIT, wall, 1)
        engine.perform(ShakeAction.SUBTRACT_TIME, wall + 30_000, 2)
        assertEquals(wall + 90_000L, engine.triggerAt)
        repeat(10) { engine.perform(ShakeAction.ADD_TIME, wall, it.toLong() + 3) }
        assertEquals(wall + ShakeActionEngine.MAX_DELAY_MS, engine.triggerAt)
        engine.perform(ShakeAction.RESET_TIME, wall + 120_000, 20)
        assertEquals(wall + 180_000L, engine.triggerAt)
    }

    @Test fun `only accepted edit actions extend monotonic idle deadline`() {
        val engine = ShakeActionEngine(ShakeSettings(entryWindowSeconds = 4, editTimeoutSeconds = 6))
        engine.recordingStopped(wall, 100)
        assertEquals(4_100L, engine.deadline)
        engine.perform(ShakeAction.ENTER_EDIT, wall, 1_000)
        assertEquals(7_000L, engine.deadline)
        assertFalse(engine.perform(ShakeAction.NONE, wall, 2_000))
        assertFalse(engine.perform(ShakeAction.START_RECORDING, wall, 2_000))
        assertEquals(7_000L, engine.deadline)
        engine.perform(ShakeAction.SWITCH_MODE, wall - 3_600_000, 3_000)
        assertEquals(9_000L, engine.deadline)
        assertFalse(engine.expired(8_999))
        assertTrue(engine.expired(9_000))
    }

    @Test fun `start request does not claim recording before recorder succeeds`() {
        val engine = ShakeActionEngine(ShakeSettings())
        assertTrue(engine.perform(ShakeAction.START_RECORDING, wall, 0))
        assertEquals(ShakeMode.READY, engine.mode)
        engine.recordingStarted()
        assertEquals(ShakeMode.RECORDING, engine.mode)
        assertFalse(engine.perform(ShakeAction.ENTER_EDIT, wall, 1))
    }

    @Test fun `validation clamps unsafe detection values and rejects incompatible assignments`() {
        val settings = ShakeSettings(defaultMinutes = -3, hourStep = 9_999, entryWindowSeconds = 0,
            editTimeoutSeconds = 500, sequenceGapMs = 600, cooldownMs = 1_200, thresholdG = Float.NaN,
            bindings = ShakeSettings.defaultBindings() + (ShakeBinding(ShakeMode.READY, 1) to ShakeAction.ADD_TIME)).validated()
        assertEquals(1, settings.defaultMinutes)
        assertEquals(1_440, settings.hourStep)
        assertEquals(3, settings.entryWindowSeconds)
        assertEquals(60, settings.editTimeoutSeconds)
        assertEquals(1_600, settings.sequenceGapMs)
        assertEquals(2.7f, settings.thresholdG, 0.001f)
        assertEquals(ShakeAction.NONE, settings.action(ShakeMode.READY, 1))
        assertEquals(ShakeAction.NONE, settings.action(ShakeMode.READY, 4))
    }

    @Test fun `sequence waits for quiet so a double never performs the single action first`() {
        val sequence = ShakeSequence(1_400)
        sequence.add(1_000)
        assertNull(sequence.take(2_000))
        sequence.add(2_100)
        assertNull(sequence.take(3_499))
        assertEquals(2, sequence.take(3_500))
        assertFalse(sequence.pending)
        assertNull(sequence.take(5_000))
        sequence.add(6_000)
        assertEquals(1, sequence.take(7_400))
    }

    @Test fun `triple overflow and explicit cancellation never leak a second action`() {
        val sequence = ShakeSequence(1_000)
        repeat(3) { sequence.add(it * 800L) }
        assertEquals(3, sequence.take(2_600))
        repeat(4) { sequence.add(3_000 + it * 800L) }
        assertEquals(0, sequence.take(6_400))
        assertFalse(sequence.pending)
        sequence.add(7_000)
        sequence.clear()
        assertNull(sequence.take(9_000))
    }
}
