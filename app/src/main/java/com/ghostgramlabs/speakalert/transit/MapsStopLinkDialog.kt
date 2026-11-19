package com.ghostgramlabs.speakalert.transit

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MapsStopLinkDialog(stops: List<BusStop> = emptyList(), initialText: String = "", autoRead: Boolean = false,
    readDraft: (suspend (String) -> MapsStopLink.Draft)? = null, isDestination: Boolean = false,
    routeOnly: Boolean = false, initialRoute: BusJourney? = null, onRoute: ((BusJourney) -> Unit)? = null,
    onDismiss: () -> Unit, selected: (BusStop) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf(initialText) }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf<Double?>(null) }
    var lon by rememberSaveable { mutableStateOf<Double?>(null) }
    var placeId by rememberSaveable { mutableStateOf<String?>(null) }
    var routeText by rememberSaveable { mutableStateOf(initialRoute?.let { BusAlert.encode(BusAlert(listOf(it.copy(enabled = true)))) }) }
    val route = remember(routeText) { BusAlert.decode(routeText)?.journeys?.singleOrNull() }
    var lines by rememberSaveable { mutableStateOf(initialRoute?.line.orEmpty()) }
    val reviewedRoute = route?.copy(line = lines.trim(), enabled = initialRoute?.enabled ?: true)
    var review by rememberSaveable { mutableStateOf(initialRoute != null) }
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    fun changeLink(text: String) {
        link = text.take(10_000)
        routeText = null; lines = ""; review = false; name = ""; address = ""; lat = null; lon = null; placeId = null; message = null
    }
    fun readLink() {
        scope.launch {
            busy = true; message = null
            try {
                val expanded = if (readDraft == null && onRoute != null) MapsStopLinkResolver.expand(link) else null
                if (expanded != null && MapsDirectionsLink.isDirections(expanded)) {
                    val imported = MapsDirectionsLink.parse(expanded)
                    routeText = BusAlert.encode(BusAlert(listOf(imported)))
                    return@launch
                }
                require(!routeOnly) { "This is a place link. Share directions between two places to import a bus route." }
                val draft = readDraft?.invoke(link) ?: MapsStopLinkResolver.read(link, context, stops, expanded)
                name = draft.name; address = draft.address; lat = draft.latitude; lon = draft.longitude; placeId = draft.placeId
                message = when {
                    draft.placeId != null -> if (isDestination) "Google Maps identified this exact destination. Check its name."
                        else "Google Maps identified this exact stop. Check its boarding-stop name."
                    draft.hasCoordinates -> if (isDestination) "Destination location found. Check its name."
                        else "Location found from the link or an exact catalogue match. Check the boarding-stop name."
                    else -> draft.locationIssue ?: "The exact location could not be identified. Enter its full address below."
                }
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) {
                message = e.message ?: "This link could not be read. Enter the stop details below."
            } catch (_: java.io.IOException) {
                message = "Could not expand this link. Check your connection, or enter the stop details below."
            } finally { busy = false; review = true }
        }
    }
    LaunchedEffect(initialText) {
        if (autoRead && !review && link == initialText && link.isNotBlank()) readLink()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (routeOnly || route != null) "Bus route from Google Maps" else if (isDestination) "Destination from Google Maps link" else "Stop from Google Maps link") },
        text = {
            val focusManager = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            fun finishTyping() { focusManager.clearFocus(force = true); keyboard?.hide() }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when {
                    routeOnly || route != null -> "Share or paste directions with a fixed starting point and destination. SpeakAlert checks for a bus itinerary when the reminder plays."
                    isDestination -> "In Google Maps, open the place or stop you are travelling toward, then Share and Copy link. Paste it here and review the destination."
                    initialText.isNotBlank() -> "Shared from Google Maps. Review the route or boarding stop before continuing."
                    else -> "In Google Maps, open the bus stop, tap Share, then Copy link. Paste it here and review the stop before using it."
                })
                OutlinedTextField(link, { changeLink(it) }, label = { Text("Google Maps share link") },
                    enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { finishTyping() }))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        val copied = clipboard.getText()?.text
                        if (copied.isNullOrBlank()) message = "Copy a place link in Google Maps first."
                        else changeLink(copied)
                    }) { Text("Paste link") }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"))) }
                            .onFailure { message = "Google Maps could not open. Paste a copied link instead." }
                    }) { Text("Open Google Maps") }
                }
                OutlinedButton(enabled = link.isNotBlank() && !busy, onClick = {
                    finishTyping()
                    readLink()
                }) { Text(if (busy) "Reading link…" else "Read link") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (route != null) {
                    Text("From ${route.stop.name}")
                    Text("To ${route.destination}")
                    OutlinedTextField(lines, { lines = it.take(120) }, label = { Text("Bus lines in order (optional)") },
                        placeholder = { Text("M2, 102") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        isError = reviewedRoute?.valid() == false,
                        supportingText = { Text("Separate lines with commas to require that connection. Leave blank to use the earliest-arriving bus itinerary Google returns.") },
                        keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finishTyping() }))
                } else if (review && !routeOnly) {
                    OutlinedTextField(name, { name = it.take(180) }, label = { Text(if (isDestination) "Destination name" else "Boarding-stop name") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finishTyping() }))
                    if (lat != null && lon != null) {
                        Text("Pin: $lat, $lon", style = MaterialTheme.typography.bodySmall)
                    } else if (BusStop.validPlaceId(placeId)) {
                        Text("Exact Google Maps place linked", style = MaterialTheme.typography.bodySmall)
                    } else {
                        OutlinedTextField(address, { address = it.take(300) },
                            label = { Text(if (isDestination) "Full destination address, including city" else "Full stop address, including city") }, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { finishTyping() }))
                    }
                    Text(if (isDestination) "Choose a destination reached in the direction you want. The bus line and schedule remain in your reminder draft."
                        else "Use the name shown for the bus stop, not a nearby business. This only selects a stop; the bus line and direction are set next.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (onRoute != null && (routeOnly || reviewedRoute != null)) {
                TextButton(enabled = !busy && reviewedRoute?.valid() == true, onClick = {
                    reviewedRoute?.let(onRoute)
                }) { Text("Use route") }
            } else TextButton(enabled = review && !busy && name.isNotBlank() &&
                ((lat != null && lon != null) || BusStop.validPlaceId(placeId) || address.isNotBlank()), onClick = {
                val location = if (lat != null && lon != null) "$lat, $lon" else address.trim()
                selected(BusStop("custom-" + UUID.randomUUID(), name.trim(), location, lat, lon, placeId = placeId))
            }) { Text(if (isDestination) "Use destination" else "Use stop") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
