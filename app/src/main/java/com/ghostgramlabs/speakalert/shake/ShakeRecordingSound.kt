package com.ghostgramlabs.speakalert.shake

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Short notification-stream cues; never change the user's volume or interrupt capture on error. */
object ShakeRecordingSound {
    fun started() = play(ToneGenerator.TONE_PROP_BEEP, 150)
    fun stopped() = play(ToneGenerator.TONE_PROP_ACK, 350)
    fun edited(hours: Boolean) = play(if (hours) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 200)

    private fun play(tone: Int, durationMs: Int) {
        var generator: ToneGenerator? = null
        try {
            val sound = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            generator = sound
            if (sound.startTone(tone, durationMs)) {
                // Independent of the service's recording timer, so Stop listening does not
                // cancel the release or cut off the final confirmation sound.
                Handler(Looper.getMainLooper()).postDelayed({ sound.release() }, durationMs + 150L)
            } else {
                sound.release()
            }
        } catch (_: RuntimeException) {
            runCatching { generator?.release() }
        }
    }
}
