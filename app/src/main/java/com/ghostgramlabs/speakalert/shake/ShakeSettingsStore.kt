package com.ghostgramlabs.speakalert.shake

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.map

/** Uses the app's existing settings DataStore, with stable enum names for gesture assignments. */
class ShakeSettingsStore(private val store: DataStore<Preferences>) {
    val settings = store.data.map { prefs ->
        val defaults = ShakeSettings()
        ShakeSettings(
            defaultMinutes = prefs[intPreferencesKey("shake_default_minutes")] ?: defaults.defaultMinutes,
            minuteStep = prefs[intPreferencesKey("shake_minute_step")] ?: defaults.minuteStep,
            hourStep = prefs[intPreferencesKey("shake_hour_step")] ?: defaults.hourStep,
            entryWindowSeconds = prefs[intPreferencesKey("shake_entry_seconds")] ?: defaults.entryWindowSeconds,
            editTimeoutSeconds = prefs[intPreferencesKey("shake_edit_seconds")] ?: defaults.editTimeoutSeconds,
            sequenceGapMs = prefs[intPreferencesKey("shake_sequence_ms")] ?: defaults.sequenceGapMs,
            cooldownMs = prefs[intPreferencesKey("shake_cooldown_ms")] ?: defaults.cooldownMs,
            thresholdG = prefs[floatPreferencesKey("shake_threshold_g")] ?: defaults.thresholdG,
            sounds = prefs[booleanPreferencesKey("shake_sounds")] ?: true,
            vibration = prefs[booleanPreferencesKey("shake_vibration")] ?: true,
            bindings = defaults.bindings.mapValues { (binding, fallback) ->
                val name = prefs[bindingKey(binding)]
                ShakeAction.values().find { it.name == name } ?: fallback
            }
        ).validated()
    }

    suspend fun save(settings: ShakeSettings) {
        val value = settings.validated()
        store.edit { prefs ->
            prefs[intPreferencesKey("shake_default_minutes")] = value.defaultMinutes
            prefs[intPreferencesKey("shake_minute_step")] = value.minuteStep
            prefs[intPreferencesKey("shake_hour_step")] = value.hourStep
            prefs[intPreferencesKey("shake_entry_seconds")] = value.entryWindowSeconds
            prefs[intPreferencesKey("shake_edit_seconds")] = value.editTimeoutSeconds
            prefs[intPreferencesKey("shake_sequence_ms")] = value.sequenceGapMs
            prefs[intPreferencesKey("shake_cooldown_ms")] = value.cooldownMs
            prefs[floatPreferencesKey("shake_threshold_g")] = value.thresholdG
            prefs[booleanPreferencesKey("shake_sounds")] = value.sounds
            prefs[booleanPreferencesKey("shake_vibration")] = value.vibration
            value.bindings.forEach { (binding, action) -> prefs[bindingKey(binding)] = action.name }
        }
    }

    private fun bindingKey(binding: ShakeBinding) = stringPreferencesKey("shake_action_${binding.mode.name}_${binding.count}")
}
