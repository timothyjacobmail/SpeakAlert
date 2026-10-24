package com.ghostgramlabs.speakalert.audio

/** Prevent the shake service and reminder editor from taking each other's microphone. */
internal object RecordingOwner {
    private var owner: Any? = null

    @Synchronized fun acquire(candidate: Any) {
        check(owner == null) { "Another recording is already in progress" }
        owner = candidate
    }

    @Synchronized fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }
}
