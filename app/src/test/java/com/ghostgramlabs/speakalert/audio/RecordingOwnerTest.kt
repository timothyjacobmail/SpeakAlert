package com.ghostgramlabs.speakalert.audio

import org.junit.Assert.fail
import org.junit.Test

class RecordingOwnerTest {
    @Test fun `competing recorders cannot steal or release microphone ownership`() {
        val editor = Any()
        val service = Any()
        RecordingOwner.acquire(editor)
        try {
            RecordingOwner.release(service)
            try {
                RecordingOwner.acquire(service)
                fail("Another recorder must not acquire the microphone")
            } catch (_: IllegalStateException) { }
        } finally {
            RecordingOwner.release(editor)
        }
        RecordingOwner.acquire(service)
        RecordingOwner.release(service)
    }
}
