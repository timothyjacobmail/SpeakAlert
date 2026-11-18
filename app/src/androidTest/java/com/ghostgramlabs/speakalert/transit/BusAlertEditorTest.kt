package com.ghostgramlabs.speakalert.transit

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ghostgramlabs.speakalert.ui.theme.VoiceReminderTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic editor only. Does not create reminders, play speech or call Google. */
class BusAlertEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "bus-ui").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        File(folder, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap!!.recycle()
    }

    @Test fun stopSelectionRequiresConfirmationAndCancelLeavesTheOriginalStop() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val original = BusAlert.defaultStop
        val alternative = BusStop("near-test", "Near test stop", "Abu Dhabi", 24.38, 54.51)
        val open = mutableStateOf(true)
        var chosen = original
        compose.setContent { VoiceReminderTheme {
            if (open.value) BusStopPicker(listOf(original, alternative), original,
                onDismiss = { open.value = false }) { chosen = it; open.value = false }
        } }
        compose.onNodeWithText("Search name or stop code").performTextInput("Near")
        compose.onNodeWithText("Use stop").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Near test stop").performClick()
        compose.runOnIdle { assertEquals(original, chosen) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(original, chosen); open.value = true }
        compose.onNodeWithText("Search name or stop code").performTextInput("Near")
        compose.onNodeWithText("Near test stop").performClick()
        compose.onNodeWithText("Use stop").performClick()
        compose.runOnIdle { assertEquals(alternative, chosen) }
    }

    @Test fun importMapsPlaceLinkRequiresReviewAndRetainsThePin() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        var chosen: BusStop? = null
        val open = mutableStateOf(true)
        compose.setContent { VoiceReminderTheme {
            if (open.value) MapsStopLinkDialog(onDismiss = { open.value = false }) {
                chosen = it; open.value = false
            }
        } }
        compose.onNodeWithText("Use stop").assertIsNotEnabled()
        compose.onNodeWithText("Google Maps share link").performTextInput(
            "https://www.google.com/maps/place/Synthetic+boarding+stop/data=!3d24.38!4d54.51")
        compose.onNodeWithText("Read link").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Boarding-stop name").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertNull(chosen) }
        compose.onNodeWithText("Boarding-stop name").performScrollTo().assertTextContains("Synthetic boarding stop")
        screenshot("maps-link-review")
        compose.onNodeWithText("Use stop").performClick()
        compose.runOnIdle {
            assertEquals("Synthetic boarding stop", chosen!!.name)
            assertEquals(24.38, chosen!!.latitude!!, 0.00001)
            assertEquals(54.51, chosen!!.longitude!!, 0.00001)
            assertTrue(chosen!!.valid())
        }
    }

    @Test fun referenceOnlyStopCanBeUsedWithoutTypingAnAddressAndSurvivesRestoration() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        var chosen: BusStop? = null
        var requests = 0
        val restore = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restore.setContent { VoiceReminderTheme {
            MapsStopLinkDialog(initialText = "https://maps.app.goo.gl/u91FnqJ3EJKYwT536", autoRead = true,
                readDraft = {
                    requests++
                    MapsStopLink.Draft(name = "Sultan Bin Mohammed Al Qubaisi St / Al Raha St 8",
                        placeId = "ChIJHSZfLdZAXj4Rt75BINb308E")
                }, onDismiss = {}) { chosen = it }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Exact Google Maps place linked").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Full stop address, including city").assertDoesNotExist()
        compose.onNodeWithText("Use stop").assertIsEnabled()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Exact Google Maps place linked").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests); assertNull(chosen) }
        screenshot("reference-only-stop")
        compose.onNodeWithText("Use stop").performClick()
        compose.runOnIdle {
            assertTrue(chosen!!.valid())
            assertEquals("ChIJHSZfLdZAXj4Rt75BINb308E", chosen!!.placeId)
            assertEquals("", chosen!!.address)
        }
    }

    @Test fun chooseAStopAddAnotherLineAndCustomizeSpeechWithoutLosingExistingBus() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val state = mutableStateOf(BusAlert.default())
        compose.setContent { VoiceReminderTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BusReminderOptions(state.value) { state.value = it }
            }
        } }
        compose.onNodeWithText("Add bus / stop").performScrollTo().performClick()
        compose.onNodeWithText("Bus line").performTextInput("111")
        compose.onNodeWithText("Destination / direction").performTextInput("Marina Mall, Abu Dhabi")
        compose.onNodeWithText("Al Hayal St / Tawjeeh Integrated Services\nStop 11700").performClick()
        compose.onNodeWithText("Search name or stop code").performTextInput("11700")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("St 15 / St 6").fetchSemanticsNodes().isNotEmpty() }
        screenshot("stop-search")
        compose.onNodeWithText("St 15 / St 6").performScrollTo().performClick()
        compose.onNodeWithText("Use stop").performClick()
        screenshot("bus-form")
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            assertEquals(2, state.value.journeys.size)
            assertEquals("M2", state.value.journeys.first().line)
            assertEquals("111", state.value.journeys.last().line)
            assertEquals("11700B", state.value.journeys.last().stop.id)
            assertFalse(state.value.journeys.last().hasTimetable())
        }
        compose.onNodeWithText("Bus 111").performScrollTo().assertExists()
        screenshot("multiple-buses")
        compose.onNodeWithText("Minutes remaining").performScrollTo().performClick()
        compose.onNodeWithText("Departure time").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(state.value.readMinutes); assertFalse(state.value.readTime) }
        compose.onNodeWithText("Minutes remaining").assertIsNotEnabled()
        compose.onNodeWithText("Stop name").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(state.value.readStop) }
        screenshot("speech-options")
        compose.onAllNodesWithText("Edit bus").onLast().performScrollTo().performClick()
        compose.onNodeWithText("Bus line").performTextClearance()
        compose.onNodeWithText("Bus line").performTextInput("101")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("111", state.value.journeys.last().line) }
    }
}
