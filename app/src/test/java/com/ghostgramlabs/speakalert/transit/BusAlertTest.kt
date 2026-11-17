package com.ghostgramlabs.speakalert.transit

import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BusAlertTest {
    private val now = Instant.parse("2026-11-17T12:50:00Z")
    private val custom = BusJourney(BusStop("custom-a", "Central Bus Station", "Central Bus Station, Abu Dhabi, UAE"), "111", "Marina Mall, Abu Dhabi")
    @Test fun payloadRoundTripsMultipleStopsAndPreservesTheLegacyM2() {
        val alert = BusAlert(listOf(BusAlert.default().journeys.first(), custom), readMinutes = true)
        val encoded = BusAlert.encode(alert)
        assertEquals(alert, BusAlert.decode(encoded))
        assertEquals(BusAlert.default(), BusAlert.decode(M2Timetable.TOKEN))
        assertTrue(BusAlert.display(encoded)!!.contains("111 at Central Bus Station"))
        assertFalse(BusAlert.display(encoded)!!.contains("SpeakAlertBus"))
        assertEquals("Normal reminder", BusAlert.display("Normal reminder"))
        assertNull(BusAlert.decode("Normal reminder"))
        assertNull(BusAlert.decode("Bus alerts · damaged\n[SpeakAlertBus:1]\n{}"))
        assertTrue(BusAlert.isToken("Bus alerts · unknown\n[SpeakAlertBus:2]\n{}"))
        assertNull(BusAlert.decode("Bus alerts · unknown\n[SpeakAlertBus:2]\n{}"))
    }
    @Test fun validatesBoundsAndNeverUsesM2TimesForAnotherStopOrLine() {
        assertFalse(BusAlert(emptyList()).valid())
        assertFalse(BusAlert(List(9) { custom }).valid())
        assertFalse(BusAlert(listOf(custom.copy(enabled = false))).valid())
        assertFalse(BusAlert(listOf(custom), readTime = false, readMinutes = false).valid())
        assertFalse(custom.copy(stop = custom.stop.copy(latitude = 200.0, longitude = 50.0)).valid())
        assertFalse(custom.copy(offlineM2 = true).hasTimetable())
        assertFalse(BusAlert.default().journeys.first().copy(line = "111").hasTimetable())
        assertTrue(BusAlert.announcement(custom, null, now, BusAlert(listOf(custom))).startsWith("No matching Google Maps estimate"))
        assertTrue(BusAlert.announcement(BusAlert.default().journeys.first(), null, now, BusAlert.default()).contains("5:03 PM"))
    }
    @Test fun speechOptionsControlTimeCountdownStopAndDirection() {
        val departure = now.plusSeconds(61)
        val alert = BusAlert(listOf(custom), readTime = false, readMinutes = true, readStop = false, readDirection = true)
        val text = BusAlert.announcement(custom, departure, now, alert)
        assertTrue(text.contains("in 2 minutes"))
        assertFalse(text.contains("Central Bus Station"))
        assertFalse(text.contains("PM"))
        assertTrue(text.contains("toward Marina Mall"))
        assertTrue(BusAlert.announcement(custom, now, now, alert).contains("now."))
    }
    @Test fun googleSelectionRejectsTransfersOtherLinesWrongStopsAndPastTimes() {
        fun step(name: String = "Central Bus Station", line: String = "111", time: String = "2026-11-17T13:00:00Z") =
            """{"transitDetails":{"transitLine":{"nameShort":"$line","vehicle":{"type":"BUS"}},"stopDetails":{"departureStop":{"name":"$name"},"departureTime":"$time"}}}"""
        fun reply(vararg steps: String) = JsonParser.parseString("""{"routes":[{"legs":[{"steps":[${steps.joinToString(",")}]}]}]}""").asJsonObject
        assertEquals(now.plusSeconds(600), BusGoogleLookup.selectEstimate(reply(step()), custom, now))
        assertNull(BusGoogleLookup.selectEstimate(reply(step("Another station")), custom, now))
        assertNull(BusGoogleLookup.selectEstimate(reply(step(line = "M2"), step()), custom, now))
        assertNull(BusGoogleLookup.selectEstimate(reply(step(time = "2026-11-17T12:49:00Z")), custom, now))
        assertNull(BusGoogleLookup.selectEstimate(reply(step(time = "2026-11-17T16:00:00Z")), custom, now))
        assertTrue(BusAlert.matchesStop(custom.stop, "Central Bus Station", null, null))
        assertFalse(BusAlert.matchesStop(custom.stop, "Other Bus Station", null, null))
    }
}
