package com.ghostgramlabs.speakalert.transit

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun M2ReminderOptions() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(M2GoogleLookup.configured(context)) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Text("Al Hayal St / Tawjeeh Integrated Services · Stop 11700")
    TextButton(onClick = { open = true }) {
        Text(if (configured) "Google Maps lookup enabled" else "Use Google Maps estimates")
    }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    if (configured) {
        TextButton(enabled = !checking, onClick = {
            checking = true
            scope.launch {
                try { status = M2GoogleLookup.check(context).status }
                finally { checking = false }
            }
        }) { Text(if (checking) "Checking Google Maps…" else "Test connection") }
        if (status.isNotEmpty()) Text(status)
    }
    if (open) {
        fun close() { draft = ""; error = false; open = false }
        AlertDialog(
            onDismissRequest = { close() },
            title = { Text("Google Maps estimates") },
            text = {
                Column {
                    Text("Optional. Supply your own Routes API key to check this bus trip with Google when the reminder plays. Google may bill your project. The official timetable is used when an estimate is unavailable.")
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it; error = false },
                        label = { Text("Routes API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = error
                    )
                    if (error) Text("Could not save the key on this device.")
                    if (configured) TextButton(onClick = {
                        M2GoogleLookup.setKey(context, "")
                        configured = false
                        close()
                    }) { Text("Use timetable only") }
                }
            },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank(), onClick = {
                    try {
                        M2GoogleLookup.setKey(context, draft)
                        status = "Key saved on this phone. Tap Test connection to verify it."
                        configured = true
                        close()
                    } catch (_: java.io.IOException) { error = true }
                }) { Text("Save key") }
            },
            dismissButton = { TextButton(onClick = { close() }) { Text("Close") } }
        )
    }
}
