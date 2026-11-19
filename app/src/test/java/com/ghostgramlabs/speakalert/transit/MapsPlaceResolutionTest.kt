package com.ghostgramlabs.speakalert.transit

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MapsPlaceResolutionTest {
    private val id = "ChIJHSZfLdZAXj4Rt75BINb308E"
    @Test fun successfulReferenceLookupKeepsTheExactGooglePlaceIdentity() {
        val reply = JsonParser.parseString("""{"entities":[{"place":"places/$id"}]}""").asJsonObject
        assertEquals(id, MapsPlaceResolution.parse(200, reply).placeId)
    }
    @Test fun partialFailuresInvalidIdsAndUnexpectedMultiplePlacesNeverChooseAnotherStop() {
        for (json in listOf("""{"entities":[{}],"failedRequests":{"0":{"code":3}}}""",
            """{"entities":[{"place":"places/../wrong"}]}""", """{"entities":[{"place":"places/$id"},{"place":"places/another"}]}""",
            """{"entities":[{"place":"wrong/$id"}]}""")) {
            val result = MapsPlaceResolution.parse(200, JsonParser.parseString(json).asJsonObject)
            assertNull(result.placeId)
            assertNotNull(result.issue)
        }
    }
    @Test fun keyQuotaAndServiceErrorsHaveUsableManualFallbacks() {
        assertTrue(MapsPlaceResolution.parse(403, null).issue!!.contains("saved key"))
        assertTrue(MapsPlaceResolution.parse(429, null).issue!!.contains("limit"))
        assertNotNull(MapsPlaceResolution.parse(500, null).issue)
    }
    @Test fun placeBasedStopsSurviveBackupWithoutInventingCoordinatesOrAnAddress() {
        val stop = BusStop("custom-reference", "Sultan Bin Mohammed Al Qubaisi St / Al Raha St 8", "", placeId = id)
        val alert = BusAlert(listOf(BusJourney(stop, "M2", "Marina Mall, Abu Dhabi")))
        assertTrue(stop.valid())
        assertEquals(alert, BusAlert.decode(BusAlert.encode(alert)))
        assertEquals(mapOf("placeId" to id), BusGoogleLookup.origin(stop))
        assertNull(stop.latitude)
        assertNull(stop.longitude)
        assertFalse(stop.copy(placeId = "places/$id").valid())
        assertFalse(stop.copy(placeId = null).valid())
    }
    @Test fun legacyAddressAndCoordinateOriginsKeepTheirExistingBehavior() {
        val stop = BusStop("custom-old", "Old stop", "An address")
        assertTrue(stop.valid())
        assertEquals(mapOf("address" to "An address"), BusGoogleLookup.origin(stop))
        assertTrue(BusGoogleLookup.origin(BusAlert.defaultStop).containsKey("location"))
        val withBoth = BusAlert.defaultStop.copy(placeId = id)
        assertEquals(mapOf("placeId" to id), BusGoogleLookup.origin(withBoth))
        val oldJson = """Bus alerts · Old stop
[SpeakAlertBus:1]
{"journeys":[{"stop":{"id":"custom-old","name":"Old stop","address":"An address"},"line":"111","destination":"Abu Dhabi","enabled":true}],"readTime":true}"""
        assertNull(BusAlert.decode(oldJson)!!.journeys.first().stop.placeId)
    }
}
