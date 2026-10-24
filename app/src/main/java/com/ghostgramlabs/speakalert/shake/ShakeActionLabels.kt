package com.ghostgramlabs.speakalert.shake

import com.ghostgramlabs.speakalert.R

fun ShakeMode.labelRes(): Int = when (this) {
    ShakeMode.READY -> R.string.shake_mode_ready
    ShakeMode.RECORDING -> R.string.shake_mode_recording
    ShakeMode.AFTER_RECORDING -> R.string.shake_mode_after
    ShakeMode.MINUTES -> R.string.shake_mode_minutes
    ShakeMode.HOURS -> R.string.shake_mode_hours
}

fun ShakeAction.labelRes(): Int = when (this) {
    ShakeAction.NONE -> R.string.shake_action_none
    ShakeAction.START_RECORDING -> R.string.shake_action_start
    ShakeAction.STOP_RECORDING -> R.string.shake_action_stop
    ShakeAction.ENTER_EDIT -> R.string.shake_action_enter
    ShakeAction.ADD_TIME -> R.string.shake_action_add
    ShakeAction.SUBTRACT_TIME -> R.string.shake_action_subtract
    ShakeAction.SWITCH_MODE -> R.string.shake_action_switch
    ShakeAction.RESET_TIME -> R.string.shake_action_reset
    ShakeAction.FINISH -> R.string.shake_finish_time
}
