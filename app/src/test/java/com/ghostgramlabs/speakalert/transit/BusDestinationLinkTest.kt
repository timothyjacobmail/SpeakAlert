package com.ghostgramlabs.speakalert.transit

import org.junit.Assert.*
import org.junit.Test

class BusDestinationLinkTest {
    private val place = BusStop("custom-destination", "Marina Mall", "", placeId = "ChIJExample_destination")
    @Test fun linkedDestinationSurvivesBackupAndUsesItsExactPlaceInRoutes() {
        val journey = BusAlert.default().journeys.first().copy(destination = place.name, destinationPlace = place)
        val alert = BusAlert(listOf(journey))
        assertTrue(journey.valid())
        assertEquals(alert, BusAlert.decode(BusAlert.encode(alert)))
        assertEquals(mapOf("placeId" to place.placeId), BusGoogleLookup.destination(journey))
        assertEquals(BusAlert.defaultStop, journey.stop)
        val spoken = BusAlert.announcement(journey, java.time.Instant.parse("2026-11-18T13:00:00Z"),
            java.time.Instant.parse("2026-11-18T12:59:00Z"), alert.copy(readDirection = true))
        assertTrue(spoken.contains("toward Marina Mall"))
        assertFalse(spoken.contains("ChIJ"))
        assertFalse(spoken.contains("https://"))
    }
    @Test fun coordinateDestinationKeepsItsPinAndRejectsMismatchedLabels() {
        val pin = place.copy(placeId = null, address = "24.4, 54.6", latitude = 24.4, longitude = 54.6)
        val journey = BusAlert.default().journeys.first().copy(destination = pin.name, destinationPlace = pin)
        val waypoint = BusGoogleLookup.destination(journey)
        assertEquals(mapOf("location" to mapOf("latLng" to mapOf("latitude" to 24.4, "longitude" to 54.6))), waypoint)
        assertFalse(journey.copy(destination = "Different place").valid())
        assertFalse(journey.copy(destinationPlace = pin.copy(latitude = 200.0)).valid())
    }
    @Test fun sameNameDifferentPlacesAreNotMergedIntoOneBusLookup() {
        val first = BusAlert.default().journeys.first().copy(destination = place.name, destinationPlace = place)
        val other = first.copy(destinationPlace = place.copy(placeId = "ChIJOther_destination"))
        assertNotEquals(BusGoogleLookup.lookupKey(first), BusGoogleLookup.lookupKey(other))
        val manual = first.copy(destinationPlace = null, destination = "MARINA MALL")
        assertEquals(BusGoogleLookup.lookupKey(manual), BusGoogleLookup.lookupKey(manual.copy(destination = "Marina Mall")))
    }
    @Test fun legacyTextDestinationAndOriginalTimetableKeepWorking() {
        val original = BusAlert.default().journeys.first()
        assertNull(BusAlert.decode(BusAlert.encode(BusAlert.default()))!!.journeys.first().destinationPlace)
        assertEquals(mapOf("address" to BusAlert.defaultDestination), BusGoogleLookup.destination(original))
        assertTrue(original.hasTimetable())
        val linked = original.copy(destinationPlace = place.copy(name = BusAlert.defaultDestination))
        assertTrue(linked.valid())
        assertFalse(linked.hasTimetable())
        assertTrue(original.copy(destinationPlace = null).hasTimetable())
    }
}
