package com.ghostgramlabs.speakalert.transit

import org.junit.Assert.*
import org.junit.Test

class MapsStopLinkTest {
    private val place = "https://www.google.com/maps/place/Al+Hayal+St+%2F+Tawjeeh/@25,55,15z/data=!3d24.3799!4d54.5104"
    @Test fun readsPlaceNameAndPinWithoutNetworkOrConfusingCameraWithPin() {
        val draft = MapsStopLink.resolve(place) { error("Full links must not make requests") }
        assertEquals("Al Hayal St / Tawjeeh", draft.name)
        assertEquals(24.3799, draft.latitude!!, 0.00001)
        assertEquals(54.5104, draft.longitude!!, 0.00001)
    }
    @Test fun addressInThePlaceLabelIsPrefilledForReview() {
        val draft = MapsStopLink.resolve("https://www.google.com/maps/place/Tawjeeh+stop,+Mussafah,+Abu+Dhabi/data=!4m2!3m1!1s0x123:0x456") { null }
        assertEquals("Tawjeeh stop", draft.name)
        assertEquals("Tawjeeh stop, Mussafah, Abu Dhabi", draft.address)
        assertFalse(draft.hasCoordinates)
    }
    @Test fun catalogueFillsMissingCoordinatesOnlyForOneExactName() {
        val known = BusAlert.defaultStop
        val draft = MapsStopLink.Draft(known.name)
        assertEquals(known.latitude, MapsStopLink.fillFromCatalogue(draft, listOf(known)).latitude)
        assertFalse(MapsStopLink.fillFromCatalogue(draft, listOf(known, known.copy(id = "other-side"))).hasCoordinates)
        assertFalse(MapsStopLink.fillFromCatalogue(draft.copy(name = "Tawjeeh"), listOf(known)).hasCoordinates)
        assertFalse(MapsStopLink.fillFromCatalogue(draft.copy(address = "Another city"), listOf(known)).hasCoordinates)
    }
    @Test fun suppliedSultanBinMohammedStopLinkReadsTheBoardingPinInsteadOfTheCamera() {
        val url = "https://www.google.com/maps/place/Sultan+Bin+Mohammed+Al+Qubaisi+St+%2F+Al+Raha+St+8/@24.3739101,54.5126362,17.56z/data=!4m6!3m5!1s0x3e5e40d62d5f261d:0xc1d3f7d62041beb7!8m2!3d24.3730985!4d54.5134716!16s%2Fg%2F11rwpmxm7t"
        val draft = MapsStopLink.resolve("https://maps.app.goo.gl/TFuHrBFJaPGQW3Fo6") { url }
        assertEquals("Sultan Bin Mohammed Al Qubaisi St / Al Raha St 8", draft.name)
        assertEquals(24.3730985, draft.latitude!!, 0.0000001)
        assertEquals(54.5134716, draft.longitude!!, 0.0000001)
    }
    @Test fun cameraPositionAloneIsNeverUsedAsTheStop() {
        val draft = MapsStopLink.resolve("https://www.google.com/maps/place/Bus+stop/@25,55,15z") { error("Network") }
        assertEquals("Bus stop", draft.name)
        assertFalse(draft.hasCoordinates)
    }
    @Test fun supportsSearchLinksAndCoordinateLinksWithSharedTitle() {
        assertEquals("محطة الحافلات", MapsStopLink.resolve(
            "https://www.google.com/maps/search/?api=1&query=%D9%85%D8%AD%D8%B7%D8%A9+%D8%A7%D9%84%D8%AD%D8%A7%D9%81%D9%84%D8%A7%D8%AA") { null }.name)
        val draft = MapsStopLink.resolve("Al Hayal stop\nhttps://maps.google.com/?q=24.38%2C54.51") { null }
        assertEquals("Al Hayal stop", draft.name)
        assertEquals(24.38, draft.latitude!!, 0.00001)
    }
    @Test fun expandsOnlyShortLinksAndStopsBeforeFetchingThePlacePage() {
        var requests = 0
        val draft = MapsStopLink.resolve("https://maps.app.goo.gl/example") {
            requests++
            if (requests == 1) "https://goo.gl/maps/example" else place
        }
        assertEquals(2, requests)
        assertTrue(draft.hasCoordinates)
    }
    @Test fun rejectsNonGoogleLinksDirectionsAndUntrustedRedirects() {
        for (url in listOf("http://maps.google.com/?q=x", "https://google.com.evil.test/maps/place/x",
            "https://www.google.com@evil.test/maps/place/x", "https://www.google.com/maps/dir/A/B",
            "https://www.google.com:8443/maps/place/x", "https://example.com/maps/place/x")) {
            assertThrows(IllegalArgumentException::class.java) { MapsStopLink.resolve(url) { error("Network") } }
        }
        var requests = 0
        assertThrows(IllegalArgumentException::class.java) {
            MapsStopLink.resolve("https://maps.app.goo.gl/example") { requests++; "https://example.com/private" }
        }
        assertEquals(1, requests)
    }
    @Test fun redirectLoopsAndMissingLocationsFailWithManualEntryAvailable() {
        assertThrows(IllegalArgumentException::class.java) {
            MapsStopLink.resolve("https://maps.app.goo.gl/example") { "https://maps.app.goo.gl/example" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapsStopLink.resolve("https://maps.app.goo.gl/example") { null }
        }
        var count = 0
        assertThrows(IllegalArgumentException::class.java) {
            MapsStopLink.resolve("https://maps.app.goo.gl/example") { "https://maps.app.goo.gl/" + (++count) }
        }
        assertEquals(5, count)
    }
    @Test fun rejectsInvalidCoordinatesAndKeepsEditableName() {
        val draft = MapsStopLink.resolve("https://www.google.com/maps/place/Test/data=!3d999!4d54") { null }
        assertEquals("Test", draft.name)
        assertFalse(draft.hasCoordinates)
    }
    @Test fun placeIdOnlyLinkNeedsManualDetailsAndMalformedLinksAreRejected() {
        assertEquals("", MapsStopLink.resolve("https://maps.google.com/?q=place_id:abc") { null }.name)
        assertThrows(IllegalArgumentException::class.java) { MapsStopLink.resolve("not a link") { null } }
        assertThrows(IllegalArgumentException::class.java) { MapsStopLink.resolve("https://www.google.com/maps?q=%ZZ") { null } }
    }
}
