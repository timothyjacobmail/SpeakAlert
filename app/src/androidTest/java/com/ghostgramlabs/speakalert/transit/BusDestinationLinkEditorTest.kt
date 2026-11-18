package com.ghostgramlabs.speakalert.transit

import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.ghostgramlabs.speakalert.ui.theme.VoiceReminderTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic emulator data; never saves or schedules a reminder or calls Google. */
class BusDestinationLinkEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun emulatorOnly() = assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
    private fun readDestination() {
        compose.onNodeWithText("Choose destination from Maps link").performScrollTo().performClick()
        compose.onNodeWithText("Google Maps share link").performTextInput(
            "https://www.google.com/maps/place/Test+destination/data=!3d24.4!4d54.6")
        compose.onNodeWithText("Read link").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Destination name").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun destinationLinkRequiresConfirmationAndNeverReplacesTheBoardingStop() {
        emulatorOnly()
        val initial = BusAlert.default().journeys.first()
        val open = mutableStateOf(true)
        var chosen = initial
        compose.setContent { VoiceReminderTheme {
            if (open.value) BusJourneyDialog(initial, listOf(initial.stop), listOf("M2"),
                onDismiss = { open.value = false }) { chosen = it; open.value = false }
        } }
        readDestination()
        compose.runOnIdle { assertEquals(initial, chosen) }
        compose.onNode(hasText("Cancel") and hasAnyAncestor(
            hasAnyDescendant(hasText("Destination from Google Maps link")))).performClick()
        compose.onNodeWithText("Destination / direction").assertTextContains(BusAlert.defaultDestination)
        readDestination()
        compose.onNodeWithText("Use destination").performClick()
        compose.onNodeWithText("Destination / direction").assertTextContains("Test destination")
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            assertEquals(initial.stop, chosen.stop)
            assertEquals(initial.line, chosen.line)
            assertEquals("Test destination", chosen.destination)
            assertEquals(24.4, chosen.destinationPlace!!.latitude!!, 0.00001)
            assertEquals(54.6, chosen.destinationPlace!!.longitude!!, 0.00001)
            assertFalse(chosen.hasTimetable())
        }
    }
    @Test fun restorationRetainsDestinationIdentityAndManualEditClearsIt() {
        emulatorOnly()
        val place = BusStop("custom-target", "Marina Mall", "", placeId = "ChIJExample_destination")
        val initial = BusAlert.default().journeys.first().copy(destination = place.name, destinationPlace = place)
        var chosen: BusJourney? = null
        val restore = StateRestorationTester(compose)
        restore.setContent { VoiceReminderTheme {
            BusJourneyDialog(initial, listOf(initial.stop), listOf("M2"), onDismiss = {}) { chosen = it }
        } }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Destination / direction").performScrollTo().assertTextContains("Marina Mall")
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(place, chosen!!.destinationPlace); assertEquals(initial.stop, chosen!!.stop) }
        compose.onNodeWithText("Destination / direction").performTextReplacement("Another destination, Abu Dhabi")
        compose.onNodeWithText("Destination / direction").performImeAction()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            assertNull(chosen!!.destinationPlace)
            assertEquals("Another destination, Abu Dhabi", chosen!!.destination)
            assertEquals(initial.stop, chosen!!.stop)
        }
    }
}
