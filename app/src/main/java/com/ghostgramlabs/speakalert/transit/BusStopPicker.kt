package com.ghostgramlabs.speakalert.transit

import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ghostgramlabs.speakalert.BuildConfig
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun BusStopPicker(
    stops: List<BusStop>, initialStop: BusStop, onDismiss: () -> Unit, selected: (BusStop) -> Unit
) {
    val context = LocalContext.current
    val allStops = remember(stops, initialStop) { (listOf(initialStop) + stops).distinctBy { it.id } }
    val servicesAvailable = remember {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
    val mapAvailable = BuildConfig.HAS_MAPS_KEY && servicesAvailable
    var showMap by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var pendingId by rememberSaveable { mutableStateOf<String?>(initialStop.id) }
    var centerLat by rememberSaveable { mutableStateOf(initialStop.latitude ?: BusAlert.defaultStop.latitude!!) }
    var centerLon by rememberSaveable { mutableStateOf(initialStop.longitude ?: BusAlert.defaultStop.longitude!!) }
    var focusLat by rememberSaveable { mutableStateOf(centerLat) }
    var focusLon by rememberSaveable { mutableStateOf(centerLon) }
    var custom by rememberSaveable { mutableStateOf(false) }
    var importLink by rememberSaveable { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var mapSession by remember { mutableStateOf(0) }
    var mapStatus by remember { mutableStateOf("Loading Google map…") }
    val center = StopMapPoint(centerLat, centerLon)
    val matches = remember(allStops, query, center) { StopPickerResults.ranked(allStops, query, center) }
    val mapStops = remember(allStops, query) { allStops.filter { StopPickerResults.matches(it, query) } }
    val pending = allStops.firstOrNull { it.id == pendingId }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(450)
            matches.firstOrNull { it.latitude != null && it.longitude != null }?.let {
                focusLat = it.latitude!!
                focusLon = it.longitude!!
                centerLat = focusLat
                centerLon = focusLon
            }
        }
    }
    fun choose(stop: BusStop) {
        pendingId = stop.id
        stop.latitude?.let { focusLat = it; centerLat = it }
        stop.longitude?.let { focusLon = it; centerLon = it }
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            val previousMode = window?.attributes?.softInputMode
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            onDispose { previousMode?.let { window?.setSoftInputMode(it) } }
        }
        val chooseHere: (BusStop) -> Unit = {
            choose(it)
            focusManager.clearFocus()
            keyboard?.hide()
        }
        Surface(Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.background) {
            Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Choose bus stop", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Button(enabled = pending != null, onClick = {
                                pending?.let { focusManager.clearFocus(); keyboard?.hide(); selected(it) }
                            }) { Text("Use stop") }
                        }
                        OutlinedTextField(value = query, onValueChange = { query = it; pendingId = null },
                            label = { Text("Search name or stop code") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
                            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = ""; pendingId = null }) { Text("Clear") } })
                        if (!searchFocused) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(selected = showMap && mapAvailable, enabled = mapAvailable,
                                    onClick = { keyboard?.hide(); showMap = true }, label = { Text("Map") })
                                FilterChip(selected = !showMap || !mapAvailable, onClick = { showMap = false }, label = { Text("List") })
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { help = true }) { Text("Map help") }
                            }
                            if (showMap && mapAvailable) {
                                Text(mapStatus, style = MaterialTheme.typography.bodySmall)
                                if (mapStatus.contains("not loaded") || mapStatus.contains("could not")) TextButton(onClick = {
                                    mapSession++; mapStatus = "Loading Google map…"
                                }) { Text("Retry map") }
                            } else if (!mapAvailable) Text(
                                if (!BuildConfig.HAS_MAPS_KEY) "Map key not configured. Stop search works offline."
                                else "Google Play services is unavailable here. Stop search works offline.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                bottomBar = {
                    if (!searchFocused) Surface(tonalElevation = 3.dp) {
                        Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                            Text(pending?.name ?: "Tap a stop to select it.", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            pending?.let { Text(it.codeLabel, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }) { padding ->
                BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                    val list: @Composable (Modifier) -> Unit = { modifier ->
                        LazyColumn(modifier) {
                            item {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (query.isNotBlank()) "Search results" else if (showMap && mapAvailable) "Stops nearest the map centre" else "Nearby stops",
                                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); importLink = true }) { Text("Google Maps link") }
                                }
                            }
                            if (matches.isEmpty()) item {
                                Text(if (allStops.isEmpty()) "Loading stops…" else "No matching stops. Try another name or add a custom stop.",
                                    modifier = Modifier.padding(12.dp))
                            }
                            items(matches, key = { it.id }) { stop ->
                                Surface(color = if (stop.id == pendingId) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surface) {
                                    Column(Modifier.fillMaxWidth().clickable { chooseHere(stop) }.padding(12.dp)) {
                                        Text(stop.name)
                                        val distance = StopPickerResults.distance(stop, center)
                                        val nearby = if (distance < 1000) " · ${distance.toInt()} m from centre"
                                            else if (distance < Double.MAX_VALUE) " · ${"%.1f".format(distance / 1000)} km from centre" else ""
                                        Text(stop.codeLabel + nearby, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Divider()
                            }
                            item {
                                TextButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); custom = true }) { Text("Custom stop") }
                            }
                        }
                    }
                    val map: @Composable (Modifier) -> Unit = { modifier ->
                        key(mapSession) {
                            NativeStopMap(mapStops, pendingId, StopMapPoint(focusLat, focusLon), modifier,
                                picked = chooseHere,
                                centered = { centerLat = it.latitude; centerLon = it.longitude },
                                status = { mapStatus = it })
                        }
                    }
                    if (showMap && mapAvailable) {
                        if (landscape) Row(Modifier.fillMaxSize()) {
                            map(if (searchFocused) Modifier.width(0.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight())
                            list(Modifier.weight(1f).fillMaxHeight())
                        } else Column(Modifier.fillMaxSize()) {
                            map(if (searchFocused) Modifier.fillMaxWidth().height(0.dp) else Modifier.fillMaxWidth().weight(1f))
                            list(Modifier.fillMaxWidth().weight(1f))
                        }
                    } else list(Modifier.fillMaxSize())
                }
            }
        }
    }
    if (custom) CustomBusStop(query, onDismiss = { custom = false }) { selected(it) }
    if (importLink) MapsStopLinkDialog(stops = allStops, onDismiss = { importLink = false }, selected = selected)
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Google map display") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The map needs Maps SDK for Android enabled in the key’s Google Cloud project, billing, and permission for this app’s package and signing certificate. Routes API alone does not enable map display.")
            Text("The map starts near the currently selected stop. Pan or zoom and tap a blue stop marker, or search by name or code. Review the highlighted stop and tap Use stop. No location permission is required.")
            Text("Map tiles load only while this picker is open. This does not refresh bus estimates or poll departures. Search and the stop list remain available without maps.")
            Text("The map key is configured in the app build. Changing the bus-estimate key does not replace the native map key.")
        } },
        confirmButton = { TextButton(onClick = { help = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = {
            val stop = pending ?: initialStop
            val target = if (stop.latitude != null && stop.longitude != null) "${stop.latitude},${stop.longitude}" else stop.address
            val url = if (BusStop.validPlaceId(stop.placeId))
                "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(stop.name) + "&query_place_id=" + Uri.encode(stop.placeId)
                else "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(target)
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }) { Text("Open in Maps") } })
}

@Composable
private fun CustomBusStop(query: String, onDismiss: () -> Unit, selected: (BusStop) -> Unit) {
    var name by rememberSaveable { mutableStateOf(query) }
    var address by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Custom bus stop") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(180) }, label = { Text("Stop name as shown in Google Maps") })
            OutlinedTextField(address, { address = it.take(300) }, label = { Text("Full address, including city") })
            Text("Use the exact boarding-stop name. A route boarding at a different stop will be rejected.")
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank() && address.isNotBlank(), onClick = {
            selected(BusStop("custom-" + java.util.UUID.randomUUID(), name.trim(), address.trim()))
        }) { Text("Use stop") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
