package com.ghostgramlabs.speakalert.transit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BusReminderOptions(alert: BusAlert, onChange: (BusAlert) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Int?>(null) }
    val statuses = remember { mutableStateMapOf<String, String>() }
    var checking by remember { mutableStateOf<String?>(null) }
    val stops by produceState<List<BusStop>>(emptyList()) {
        value = withContext(Dispatchers.IO) { BusStops.load(context) }
    }
    Text("Bus alerts", style = MaterialTheme.typography.titleLarge)
    Text("All buses below share this reminder’s time and repeat settings. Create another reminder for a different schedule. The initial schedule is weekdays at 4:50 PM UAE time.")
    alert.journeys.forEachIndexed { index, journey ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bus ${journey.line}", style = MaterialTheme.typography.titleMedium)
                Text("${journey.stop.name} · ${journey.stop.codeLabel}")
                Text("Toward ${journey.destination}")
                if (alert.journeys.size > 1) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Include this bus", Modifier.weight(1f))
                        Switch(checked = journey.enabled, enabled = !journey.enabled || alert.journeys.count { it.enabled } > 1,
                            onCheckedChange = { value -> onChange(alert.copy(journeys = alert.journeys.mapIndexed { i, j ->
                                if (i == index) j.copy(enabled = value) else j
                            })) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editIndex = index }) { Text("Edit bus") }
                    TextButton(enabled = alert.journeys.size > 1 && (!journey.enabled || alert.journeys.count { it.enabled } > 1), onClick = { removing = index }) { Text("Remove") }
                }
                val key = journey.toString()
                OutlinedButton(enabled = checking == null, onClick = {
                    checking = key
                    scope.launch {
                        try { statuses[key] = BusGoogleLookup.check(context, journey).status }
                        finally { checking = null }
                    }
                }) { Text(if (checking == key) "Checking…" else "Check this bus now") }
                statuses[key]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    OutlinedButton(enabled = alert.journeys.size < 8, onClick = { adding = true }) { Text("Add bus / stop") }
    Text("Up to 8 buses per reminder. Each enabled bus uses at most one Google request when the reminder plays. No continuous polling.",
        style = MaterialTheme.typography.bodySmall)
    Text("Read aloud", style = MaterialTheme.typography.titleMedium)
    BusCheck("Departure time", alert.readTime, !alert.readTime || alert.readMinutes) { onChange(alert.copy(readTime = it)) }
    BusCheck("Minutes remaining", alert.readMinutes, !alert.readMinutes || alert.readTime) { onChange(alert.copy(readMinutes = it)) }
    BusCheck("Stop name", alert.readStop) { onChange(alert.copy(readStop = it)) }
    BusCheck("Direction / destination", alert.readDirection) { onChange(alert.copy(readDirection = it)) }
    Text("Keep departure time or minutes remaining selected. These choices and bus edits apply when you save the reminder.",
        style = MaterialTheme.typography.bodySmall)
    M2ReminderOptions(alert.journeys.first { it.enabled })
    Text("Google can return scheduled estimates and a limited set of journeys. A missing match does not mean no bus is running. Offline timetable fallback is available only for the original M2 preset.",
        style = MaterialTheme.typography.bodySmall)
    if (adding || editIndex != null) {
        val index = editIndex
        BusJourneyDialog(initial = index?.let { alert.journeys[it] }, stops = stops + alert.journeys.map { it.stop },
            knownLines = alert.journeys.map { it.line }, onDismiss = { adding = false; editIndex = null }) { changed ->
            val next = if (index == null) alert.journeys + changed else alert.journeys.mapIndexed { i, j -> if (i == index) changed else j }
            onChange(alert.copy(journeys = next))
            adding = false
            editIndex = null
        }
    }
    removing?.let { index ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove bus ${alert.journeys[index].line}?") },
            text = { Text("Remove this stop and line from this reminder?") },
            confirmButton = { TextButton(onClick = {
                val next = alert.journeys.filterIndexed { i, _ -> i != index }
                onChange(alert.copy(journeys = next))
                removing = null
            }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun BusCheck(label: String, checked: Boolean, enabled: Boolean = true, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled, Role.Checkbox, changed),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun BusJourneyDialog(initial: BusJourney?, stops: List<BusStop>, knownLines: List<String>,
    onDismiss: () -> Unit, onApply: (BusJourney) -> Unit) {
    var stop by remember { mutableStateOf(initial?.stop ?: BusAlert.defaultStop) }
    var line by remember { mutableStateOf(initial?.line.orEmpty()) }
    var destination by remember { mutableStateOf(initial?.destination.orEmpty()) }
    var picker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "Add bus / stop" else "Edit bus / stop") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Boarding stop", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { picker = true }) { Text("${stop.name}\n${stop.codeLabel}") }
                OutlinedTextField(value = line, onValueChange = { line = it.take(24); error = false },
                    label = { Text("Bus line") }, placeholder = { Text("M2, 111, 101…") }, singleLine = true,
                    isError = error && line.isBlank(), supportingText = { Text("Enter the line shown at the stop, or choose one below.") })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("M2") + knownLines).distinct().forEach { code ->
                        SuggestionChip(onClick = { line = code }, label = { Text(code) })
                    }
                }
                OutlinedTextField(value = destination, onValueChange = { destination = it.take(240); error = false },
                    label = { Text("Destination / direction") }, minLines = 2,
                    isError = error && destination.isBlank(),
                    supportingText = { Text("Enter a destination reached in this direction, including city. Google needs it to find a journey.") })
                if (initial == null) TextButton(onClick = { stop = BusAlert.defaultStop; line = "M2"; destination = BusAlert.defaultDestination }) {
                    Text("Use original M2 preset")
                }
                if (error) Text("Enter a bus line and destination.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            val preset = stop == BusAlert.defaultStop && line.trim().equals("M2", true) && destination.trim() == BusAlert.defaultDestination
            val journey = BusJourney(stop, line.trim().uppercase(), destination.trim(), initial?.enabled ?: true, offlineM2 = preset)
            if (journey.valid()) onApply(journey) else error = true
        }) { Text("Apply") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (picker) BusStopPicker(stops.distinctBy { it.id }, onDismiss = { picker = false }) { stop = it; picker = false }
}

@Composable
private fun BusStopPicker(stops: List<BusStop>, onDismiss: () -> Unit, selected: (BusStop) -> Unit) {
    var query by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    val matches = remember(stops, query) { BusStops.search(stops, query) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose bus stop") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search name or stop code") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Abu Dhabi public stop catalogue. Some stop names may be older; add a custom stop when needed.",
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (matches.isEmpty()) item { Text(if (stops.isEmpty()) "Loading stops…" else "No matching stops. Try a different name or add a custom stop.") }
                    items(matches, key = { it.id }) { item ->
                        Column(Modifier.fillMaxWidth().clickable { selected(item) }.padding(vertical = 12.dp)) {
                            Text(item.name)
                            Text("${item.codeLabel}", style = MaterialTheme.typography.bodySmall)
                        }
                        Divider()
                    }
                }
                TextButton(onClick = { custom = true; name = query }) { Text("Add custom stop") }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (custom) AlertDialog(onDismissRequest = { custom = false }, title = { Text("Custom bus stop") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(180) }, label = { Text("Stop name as shown in Google Maps") })
            OutlinedTextField(address, { address = it.take(300) }, label = { Text("Full address, including city") })
            Text("Use the exact boarding-stop name. A route boarding at a different stop will be rejected.")
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && address.isNotBlank(), onClick = {
            selected(BusStop("custom-" + java.util.UUID.randomUUID(), name.trim(), address.trim()))
        }) { Text("Use stop") } }, dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } })
}
