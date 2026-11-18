package com.ghostgramlabs.speakalert.transit

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Isolated emulator only. Does not save, schedule, speak, or request bus estimates. */
class MapsBusShareActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun share(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun emulatorOnly() = assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))

    @Test fun sharesheetResolvesAndSharedStopBecomesAnUnsavedBusDraft() {
        emulatorOnly()
        val intent = share("https://www.google.com/maps/place/Al+Hayal+St+%2F+Tawjeeh+Integrated+Services/data=!3d24.3799795!4d54.5104999")
        val receivers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(receivers.any { it.activityInfo.name == MapsBusShareActivity::class.java.name })
        ActivityScenario.launch<MapsBusShareActivity>(intent).use { activity ->
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Boarding-stop name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Google Maps share link").assertIsNotFocused()
            compose.onNodeWithText("Use stop").performClick()
            compose.onNodeWithText("Bus line and direction").assertIsDisplayed()
            compose.onNodeWithText("Bus line").performTextInput("111")
            compose.onNodeWithText("Destination / direction").performTextInput("Marina Mall, Abu Dhabi")
            compose.onNodeWithText("Destination / direction").performImeAction()
            activity.recreate()
            compose.onNodeWithText("Bus line").assertTextContains("111")
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Bus 111").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Bus 111").performScrollTo().assertIsDisplayed()
            activity.recreate()
            compose.onNodeWithText("Bus 111").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun sharedDirectionsRetainLineOrderInDraftAcrossRotation() {
        emulatorOnly()
        val link = "https://www.google.com/maps/dir/Office/24.4866905,54.3647146"
        ActivityScenario.launch<MapsBusShareActivity>(share(link)).use { activity ->
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Bus lines in order (optional)").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Bus lines in order (optional)").performScrollTo().performTextInput("M2, 102")
            compose.onNodeWithText("Bus lines in order (optional)").performImeAction()
            activity.recreate()
            compose.onNodeWithText("Use route").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Bus route · M2 → 102").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Edit route").performScrollTo().performClick()
            compose.onNodeWithText("Bus lines in order (optional)").performScrollTo().assertTextContains("M2, 102")
        }
    }

    @Test fun unrelatedSharedTextShowsHelpAndCanBeCancelled() {
        emulatorOnly()
        ActivityScenario.launch<MapsBusShareActivity>(share("Unrelated test text")).use {
            compose.onNodeWithText("Share a Google Maps bus stop").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
        }
    }
}
