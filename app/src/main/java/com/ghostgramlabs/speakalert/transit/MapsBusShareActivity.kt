package com.ghostgramlabs.speakalert.transit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ghostgramlabs.speakalert.VoiceReminderApp
import com.ghostgramlabs.speakalert.ui.addedit.AddEditReminderScreen
import com.ghostgramlabs.speakalert.ui.theme.VoiceReminderTheme
import com.ghostgramlabs.speakalert.util.AppLocale
import com.ghostgramlabs.speakalert.util.ThemePrefs
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A separate standard activity keeps an existing editor's draft and ViewModel intact. */
class MapsBusShareActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrapContext(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyWindowTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedText = runCatching {
            MapsShareInput.read(intent.action, intent.type,
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT), intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT))
        }.getOrNull()
        setContent {
            val app = application as VoiceReminderApp
            val theme by app.container.settingsRepository.themeMode.collectAsState(initial = ThemePrefs.cached(this))
            VoiceReminderTheme(darkTheme = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SharedStopDraft(sharedText, close = { finish() })
                }
            }
        }
    }
}

@Composable
private fun SharedStopDraft(sharedText: String?, close: () -> Unit) {
    if (sharedText == null) {
        AlertDialog(onDismissRequest = close, title = { Text("Share a Google Maps bus stop") },
            text = { Text("Open the actual boarding stop in Google Maps, tap Share, then choose SpeakAlert. Directions and unrelated text cannot be imported as a stop.") },
            confirmButton = { TextButton(onClick = close) { Text("Close") } })
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val stops by produceState<List<BusStop>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { (listOf(BusAlert.defaultStop) + BusStops.load(context)).distinctBy { it.id } }
                .getOrDefault(listOf(BusAlert.defaultStop))
        }
    }
    var stopJson by rememberSaveable { mutableStateOf<String?>(null) }
    var alertText by rememberSaveable { mutableStateOf<String?>(null) }
    val stop = remember(stopJson) {
        stopJson?.let { runCatching { Gson().fromJson(it, BusStop::class.java).takeIf { item -> item.valid() } }.getOrNull() }
    }
    val journey = remember(alertText) { BusAlert.decode(alertText)?.journeys?.singleOrNull() }
    when {
        journey != null -> AddEditReminderScreen(navigateBack = close, onNavigateUp = close, sharedBusJourney = journey)
        stops == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        stop != null -> BusJourneyDialog(
            initial = BusJourney(stop, "", ""), stops = stops.orEmpty(), knownLines = emptyList(),
            title = "Bus line and direction", confirmLabel = "Continue", onDismiss = close,
            onApply = { alertText = BusAlert.encode(BusAlert(listOf(it))) })
        else -> MapsStopLinkDialog(stops = stops.orEmpty(), initialText = sharedText, autoRead = true,
            onDismiss = close, selected = { stopJson = Gson().toJson(it) })
    }
}
