package com.ghostgramlabs.speakalert.shake

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ShakeSettingsStoreTest {
    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun `existing installations get complete defaults without changing other settings`() = runTest {
        val data = MemoryStore()
        val unrelated = stringPreferencesKey("theme")
        data.edit { it[unrelated] = "dark" }
        val store = ShakeSettingsStore(data)
        assertEquals(ShakeSettings(), store.settings.first())
        store.save(ShakeSettings(defaultMinutes = 20))
        assertEquals("dark", data.data.first()[unrelated])
    }

    @Test fun `all custom values and context mappings survive a new repository instance`() = runTest {
        val data = MemoryStore()
        val config = ShakeSettings(defaultMinutes = 15, minuteStep = 3, hourStep = 90,
            entryWindowSeconds = 20, editTimeoutSeconds = 25, sequenceGapMs = 2_000, cooldownMs = 900,
            thresholdG = 3.2f, sounds = false, vibration = false,
            bindings = ShakeSettings.defaultBindings() + mapOf(
                ShakeBinding(ShakeMode.MINUTES, 1) to ShakeAction.SUBTRACT_TIME,
                ShakeBinding(ShakeMode.HOURS, 2) to ShakeAction.RESET_TIME,
                ShakeBinding(ShakeMode.RECORDING, 3) to ShakeAction.STOP_RECORDING))
        ShakeSettingsStore(data).save(config)
        assertEquals(config, ShakeSettingsStore(data).settings.first())
    }

    @Test fun `unknown persisted actions fall back and incompatible actions are ignored`() = runTest {
        val data = MemoryStore()
        data.edit {
            it[stringPreferencesKey("shake_action_READY_1")] = "FUTURE_ACTION"
            it[stringPreferencesKey("shake_action_RECORDING_1")] = "ADD_TIME"
            it[intPreferencesKey("shake_default_minutes")] = -50
        }
        val config = ShakeSettingsStore(data).settings.first()
        assertEquals(ShakeAction.START_RECORDING, config.action(ShakeMode.READY, 1))
        assertEquals(ShakeAction.NONE, config.action(ShakeMode.RECORDING, 1))
        assertEquals(1, config.defaultMinutes)
    }
}
