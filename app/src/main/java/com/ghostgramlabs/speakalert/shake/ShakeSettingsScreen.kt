package com.ghostgramlabs.speakalert.shake

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.speakalert.R
import com.ghostgramlabs.speakalert.ui.components.mirrorInRtl
import kotlinx.coroutines.launch

private fun numberValues(settings: ShakeSettings) = hashMapOf(
    "default" to settings.defaultMinutes.toString(), "minutes" to settings.minuteStep.toString(),
    "hours" to settings.hourStep.toString(), "entry" to settings.entryWindowSeconds.toString(),
    "timeout" to settings.editTimeoutSeconds.toString(), "gap" to settings.sequenceGapMs.toString(),
    "cooldown" to settings.cooldownMs.toString(), "sensitivity" to settings.thresholdG.toString()
)
private fun bindingValues(settings: ShakeSettings) = HashMap(settings.bindings.mapKeys {
    "${it.key.mode.name}:${it.key.count}"
}.mapValues { it.value.name })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShakeSettingsScreen(store: ShakeSettingsStore, onBack: () -> Unit) {
    val saved by store.settings.collectAsState(initial = null)
    val service by ShakeRecordingService.state.collectAsState()
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.shake_customize)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.action_back), Modifier.mirrorInRtl())
            }
        })
    }) { padding ->
        val initial = saved
        if (initial == null) CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
        else ShakeSettingsForm(initial, store, service.listening || service.saving || service.preparing, onBack, Modifier.padding(padding))
    }
}

@Composable
private fun ShakeSettingsForm(initial: ShakeSettings, store: ShakeSettingsStore, active: Boolean, onBack: () -> Unit, modifier: Modifier) {
    var numbers by rememberSaveable { mutableStateOf(numberValues(initial)) }
    var bindings by rememberSaveable { mutableStateOf(bindingValues(initial)) }
    var sounds by rememberSaveable { mutableStateOf(initial.sounds) }
    var vibration by rememberSaveable { mutableStateOf(initial.vibration) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val valid = listOf("default", "minutes", "hours").all { numbers[it]?.toIntOrNull() in 1..1_440 } &&
        listOf("entry", "timeout").all { numbers[it]?.toIntOrNull() in 3..60 } &&
        numbers["gap"]?.toIntOrNull() in 600..2_500 && numbers["cooldown"]?.toIntOrNull() in 200..1_200 &&
        (numbers["gap"]?.toIntOrNull() ?: 0) >= (numbers["cooldown"]?.toIntOrNull() ?: 0) + 400 &&
        (numbers["sensitivity"]?.toFloatOrNull() ?: 0f) in 1.8f..4.5f

    fun value(): ShakeSettings = ShakeSettings(
        defaultMinutes = numbers.getValue("default").toInt(), minuteStep = numbers.getValue("minutes").toInt(),
        hourStep = numbers.getValue("hours").toInt(), entryWindowSeconds = numbers.getValue("entry").toInt(),
        editTimeoutSeconds = numbers.getValue("timeout").toInt(), sequenceGapMs = numbers.getValue("gap").toInt(),
        cooldownMs = numbers.getValue("cooldown").toInt(), thresholdG = numbers.getValue("sensitivity").toFloat(),
        sounds = sounds, vibration = vibration,
        bindings = ShakeSettings.defaultBindings().mapValues { (binding, fallback) ->
            ShakeAction.values().find { it.name == bindings["${binding.mode.name}:${binding.count}"] } ?: fallback
        }
    )

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.shake_custom_help)) }
        if (active) item {
            Text(stringResource(R.string.shake_stop_to_configure))
            OutlinedButton(onClick = { ShakeRecordingService.stop(context) }) { Text(stringResource(R.string.shake_disable)) }
        }
        item { Text(stringResource(R.string.shake_time_settings), style = MaterialTheme.typography.titleLarge) }
        listOf(
            Triple("default", R.string.shake_default_delay, R.string.shake_range_minutes),
            Triple("minutes", R.string.shake_fine_step, R.string.shake_range_minutes),
            Triple("hours", R.string.shake_coarse_step, R.string.shake_range_minutes),
            Triple("entry", R.string.shake_entry_window, R.string.shake_range_seconds),
            Triple("timeout", R.string.shake_edit_timeout, R.string.shake_range_seconds)
        ).forEach { (key, label, help) -> item {
            NumberSetting(numbers.getValue(key), label, help, !saving) { input -> numbers = HashMap(numbers).apply { put(key, input) } }
        } }
        item { Text(stringResource(R.string.shake_assignments), style = MaterialTheme.typography.titleLarge) }
        ShakeMode.values().forEach { mode -> item {
            var expanded by rememberSaveable { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(mode.labelRes()) + if (expanded) " −" else " +")
                    }
                    if (expanded) (1..3).forEach { count ->
                        val key = "${mode.name}:$count"
                        val action = ShakeAction.values().find { it.name == bindings[key] } ?: ShakeAction.NONE
                        ActionPicker(count, action, ShakeSettings.allowedActions(mode), !saving) {
                            bindings = HashMap(bindings).apply { put(key, it.name) }
                        }
                    }
                }
            }
        } }
        item { Text(stringResource(R.string.shake_detection_settings), style = MaterialTheme.typography.titleLarge) }
        listOf(
            Triple("gap", R.string.shake_sequence_gap, R.string.shake_range_gap),
            Triple("cooldown", R.string.shake_cooldown, R.string.shake_range_cooldown),
            Triple("sensitivity", R.string.shake_threshold, R.string.shake_range_threshold)
        ).forEach { (key, label, help) -> item {
            NumberSetting(numbers.getValue(key), label, help, !saving) { input -> numbers = HashMap(numbers).apply { put(key, input) } }
        } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.shake_sound_setting), Modifier.weight(1f))
                Switch(sounds, { sounds = it }, enabled = !saving)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.shake_vibration_setting), Modifier.weight(1f))
                Switch(vibration, { vibration = it }, enabled = !saving)
            }
        }
        if (!valid) item { Text(stringResource(R.string.shake_invalid_settings), color = MaterialTheme.colorScheme.error) }
        if (failed) item { Text(stringResource(R.string.shake_settings_failed), color = MaterialTheme.colorScheme.error) }
        item {
            Button(enabled = valid && !active && !saving, onClick = {
                saving = true
                failed = false
                scope.launch {
                    try { store.save(value()); onBack() }
                    catch (_: Exception) { failed = true }
                    finally { saving = false }
                }
            }) { Text(stringResource(R.string.shake_save_settings)) }
            TextButton(enabled = !saving, onClick = {
                val defaults = ShakeSettings()
                numbers = numberValues(defaults)
                bindings = bindingValues(defaults)
                sounds = defaults.sounds
                vibration = defaults.vibration
            }) { Text(stringResource(R.string.shake_restore_defaults)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberSetting(value: String, label: Int, help: Int, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true,
        label = { Text(stringResource(label)) }, supportingText = { Text(stringResource(help)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun ActionPicker(count: Int, selected: ShakeAction, actions: List<ShakeAction>, enabled: Boolean, onChange: (ShakeAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(when (count) { 1 -> R.string.shake_single; 2 -> R.string.shake_double; else -> R.string.shake_triple }),
            style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(stringResource(selected.labelRes())) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                actions.forEach { action -> DropdownMenuItem(text = { Text(stringResource(action.labelRes())) }, onClick = {
                    onChange(action); expanded = false
                }) }
            }
        }
    }
}
