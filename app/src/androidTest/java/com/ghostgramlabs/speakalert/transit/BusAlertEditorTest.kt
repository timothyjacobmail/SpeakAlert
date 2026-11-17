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
        compose.onNodeWithText("St 15 / St 6").performClick()
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
