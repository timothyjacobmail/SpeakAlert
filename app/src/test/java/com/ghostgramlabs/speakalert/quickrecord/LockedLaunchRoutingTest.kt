package com.ghostgramlabs.speakalert.quickrecord

import org.junit.Assert.*
import org.junit.Test

class LockedLaunchRoutingTest {
    @Test fun `locked launcher can enter recording without opening reminder data`() {
        assertTrue(shouldRecordQuickActionLaunch(locked = true, shortcut = false, launcher = true, reminderNavigation = false, enabled = true))
    }

    @Test fun `unlocked launches and notification or widget navigation keep their ordinary screens`() {
        assertFalse(shouldRecordQuickActionLaunch(locked = false, shortcut = false, launcher = true, reminderNavigation = false, enabled = true))
        assertFalse(shouldRecordQuickActionLaunch(locked = true, shortcut = true, launcher = false, reminderNavigation = false, enabled = true))
        assertFalse(shouldRecordQuickActionLaunch(locked = true, shortcut = true, launcher = true, reminderNavigation = true, enabled = true))
    }

    @Test fun `disabling locked launcher recording keeps the app behind unlock`() {
        assertFalse(shouldRecordQuickActionLaunch(locked = true, shortcut = true, launcher = true, reminderNavigation = false, enabled = false))
    }

    @Test fun `observed vivo shortcut still records after unlocking`() {
        val shortcut = isVivoQuickActionLaunch(0x30204000, "vivo")
        assertTrue(shortcut)
        assertTrue(shouldRecordQuickActionLaunch(locked = false, shortcut = shortcut, launcher = true, reminderNavigation = false, enabled = true))
    }

    @Test fun `normal app icon flags do not activate recording after unlocking`() {
        assertFalse(isVivoQuickActionLaunch(0x10200000, "vivo"))
        assertFalse(isVivoQuickActionLaunch(0x10200000, "VIVO"))
    }

    @Test fun `vivo shortcut flag is not assumed for other manufacturers`() {
        assertFalse(isVivoQuickActionLaunch(0x30204000, "Google"))
    }
}
