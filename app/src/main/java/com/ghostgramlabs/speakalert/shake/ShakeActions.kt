package com.ghostgramlabs.speakalert.shake

enum class ShakeMode { READY, RECORDING, AFTER_RECORDING, MINUTES, HOURS }
enum class ShakeAction { NONE, START_RECORDING, STOP_RECORDING, ENTER_EDIT, ADD_TIME, SUBTRACT_TIME, SWITCH_MODE, RESET_TIME, FINISH }

data class ShakeBinding(val mode: ShakeMode, val count: Int)

/** One assignment per gesture and context prevents conflicting rules. */
data class ShakeSettings(
    val defaultMinutes: Int = 5,
    val minuteStep: Int = 5,
    val hourStep: Int = 30,
    val entryWindowSeconds: Int = 10,
    val editTimeoutSeconds: Int = 10,
    val sequenceGapMs: Int = 1_400,
    val cooldownMs: Int = 500,
    val thresholdG: Float = 2.7f,
    val sounds: Boolean = true,
    val vibration: Boolean = true,
    val bindings: Map<ShakeBinding, ShakeAction> = defaultBindings()
) {
    fun action(mode: ShakeMode, count: Int): ShakeAction =
        (bindings[ShakeBinding(mode, count)] ?: ShakeAction.NONE).takeIf { it in allowedActions(mode) }
            ?: ShakeAction.NONE

    fun validated(): ShakeSettings = copy(
        defaultMinutes = defaultMinutes.coerceIn(1, 1_440),
        minuteStep = minuteStep.coerceIn(1, 1_440),
        hourStep = hourStep.coerceIn(1, 1_440),
        entryWindowSeconds = entryWindowSeconds.coerceIn(3, 60),
        editTimeoutSeconds = editTimeoutSeconds.coerceIn(3, 60),
        cooldownMs = cooldownMs.coerceIn(200, 1_200),
        sequenceGapMs = sequenceGapMs.coerceIn(600, 2_500).coerceAtLeast(cooldownMs.coerceIn(200, 1_200) + 400),
        thresholdG = if (thresholdG.isFinite()) thresholdG.coerceIn(1.8f, 4.5f) else 2.7f,
        bindings = defaultBindings().mapValues { (key, _) -> action(key.mode, key.count) }
    )

    companion object {
        fun allowedActions(mode: ShakeMode): List<ShakeAction> = when (mode) {
            ShakeMode.READY -> listOf(ShakeAction.START_RECORDING, ShakeAction.NONE)
            ShakeMode.RECORDING -> listOf(ShakeAction.STOP_RECORDING, ShakeAction.NONE)
            ShakeMode.AFTER_RECORDING -> listOf(ShakeAction.ENTER_EDIT, ShakeAction.FINISH, ShakeAction.NONE)
            ShakeMode.MINUTES, ShakeMode.HOURS -> listOf(ShakeAction.ADD_TIME, ShakeAction.SUBTRACT_TIME,
                ShakeAction.SWITCH_MODE, ShakeAction.RESET_TIME, ShakeAction.FINISH, ShakeAction.NONE)
        }

        fun defaultBindings(): Map<ShakeBinding, ShakeAction> = ShakeMode.values().flatMap { mode ->
            (1..3).map { count ->
                ShakeBinding(mode, count) to when (mode) {
                    ShakeMode.READY -> if (count == 1) ShakeAction.START_RECORDING else ShakeAction.NONE
                    ShakeMode.RECORDING -> if (count == 1) ShakeAction.STOP_RECORDING else ShakeAction.NONE
                    ShakeMode.AFTER_RECORDING -> when (count) { 1 -> ShakeAction.ENTER_EDIT; 3 -> ShakeAction.FINISH; else -> ShakeAction.NONE }
                    ShakeMode.MINUTES, ShakeMode.HOURS -> when (count) {
                        1 -> ShakeAction.ADD_TIME; 2 -> ShakeAction.SWITCH_MODE; else -> ShakeAction.FINISH
                    }
                }
            }
        }.toMap()
    }
}

/** Groups complete shakes, not individual acceleration peaks. Wait for quiet before dispatch. */
class ShakeSequence(private val gapMs: Long) {
    private var count = 0
    private var lastAt = 0L
    val pending get() = count > 0
    val gestureCount get() = count
    fun add(now: Long) { count = (count + 1).coerceAtMost(4); lastAt = now }
    fun take(now: Long): Int? {
        if (!pending || now - lastAt < gapMs) return null
        val result = if (count in 1..3) count else 0
        clear()
        return result
    }
    fun clear() { count = 0 }
}

/** Wall time sets the alarm; monotonic time controls gesture windows, even if the clock changes. */
class ShakeActionEngine(val settings: ShakeSettings) {
    var mode = ShakeMode.READY; private set
    var triggerAt: Long? = null; private set
    var stoppedAt: Long = 0; private set
    var deadline: Long? = null; private set
    val pending get() = mode in listOf(ShakeMode.AFTER_RECORDING, ShakeMode.MINUTES, ShakeMode.HOURS)
    val stepMinutes get() = if (mode == ShakeMode.HOURS) settings.hourStep else settings.minuteStep

    fun recordingStarted() { mode = ShakeMode.RECORDING; triggerAt = null; deadline = null }
    fun recordingStopped(wall: Long, elapsed: Long) {
        stoppedAt = wall
        triggerAt = wall + settings.defaultMinutes * 60_000L
        mode = ShakeMode.AFTER_RECORDING
        deadline = elapsed + settings.entryWindowSeconds * 1_000L
    }

    fun expired(elapsed: Long): Boolean = deadline?.let { elapsed >= it } ?: false

    fun perform(action: ShakeAction, wall: Long, elapsed: Long): Boolean {
        if (action !in ShakeSettings.allowedActions(mode) || action == ShakeAction.NONE) return false
        when (action) {
            ShakeAction.ENTER_EDIT -> mode = ShakeMode.MINUTES
            ShakeAction.ADD_TIME -> triggerAt = ((triggerAt ?: wall) + stepMinutes * 60_000L)
                .coerceAtMost(wall + MAX_DELAY_MS).coerceAtLeast(wall + 60_000L)
            ShakeAction.SUBTRACT_TIME -> triggerAt = ((triggerAt ?: wall) - stepMinutes * 60_000L).coerceAtLeast(wall + 60_000L)
            ShakeAction.RESET_TIME -> triggerAt = (stoppedAt + settings.defaultMinutes * 60_000L).coerceAtLeast(wall + 60_000L)
            ShakeAction.SWITCH_MODE -> mode = if (mode == ShakeMode.MINUTES) ShakeMode.HOURS else ShakeMode.MINUTES
            else -> Unit // Recording and saving only change state after the service accepts them.
        }
        if (mode == ShakeMode.MINUTES || mode == ShakeMode.HOURS) deadline = elapsed + settings.editTimeoutSeconds * 1_000L
        return true
    }

    fun finished() { mode = ShakeMode.READY; deadline = null }
    companion object { const val MAX_DELAY_MS = 7 * 24 * 60 * 60 * 1_000L }
}
