package com.ghostgramlabs.speakalert.transit

import com.google.gson.JsonParser
import java.net.URI
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class BusConnectionsTest {
    private val now = Instant.parse("2026-11-18T12:50:00Z")
    private val route = MapsDirectionsLink.parse(URI("https://www.google.com/maps/dir/Office/24.4866905,54.3647146"))
    private fun ride(line: String, depart: String, arrive: String, board: String, alight: String, vehicle: String = "BUS") =
        """{"travelMode":"TRANSIT","transitDetails":{"transitLine":{"nameShort":"$line","vehicle":{"type":"$vehicle"}},
            "stopDetails":{"departureStop":{"name":"$board"},"departureTime":"2026-11-18T${depart}:00Z",
            "arrivalStop":{"name":"$alight"},"arrivalTime":"2026-11-18T${arrive}:00Z"}}}"""
    private fun walk(seconds: Int) = """{"travelMode":"WALK","staticDuration":"${seconds}s"}"""
    private fun alternative(vararg steps: String) = """{"legs":[{"steps":[${steps.joinToString(",")}]}]}"""
    private val first = ride("M2", "13:10", "13:25", "Office stop", "Exchange A")
    private fun second(depart: String, arrive: String) = ride("102", depart, arrive, "Exchange B", "City stop")
    private fun select(vararg alternatives: String, filter: String = "") = BusConnections.select(
        JsonParser.parseString("""{"routes":[${alternatives.joinToString(",")}]}""").asJsonObject, route.copy(line = filter), now)

    @Test fun bothSharedLinkVariantsImportSameEndpointsWithExactPins() {
        val prefix = "https://www.google.com/maps/dir/AQUALISTAR+INSPECTION+AND+MAINTENANCE+SERVICES+L.L.C,+Office+38-Block+A,+Abu+Dhabi/24.4866905,54.3647146/@24.4174809,54.3831806,12z/am=t/data="
        for ((short, data) in listOf("7BG9MZg9GWBfDNPg6" to "!4m10!4m9", "Z8nxxG4gHL6vDpYZ9" to "!4m11!4m10")) {
            val full = prefix + data + "!1m5!1m1!1s0x3e5e41450b01e91d:0xcc850766d5cb515e!2m2!1d54.5095537!2d24.3810487!1m1!4e1!3e3" +
                if (short.startsWith("Z")) "!5i1" else ""
            val imported = MapsDirectionsLink.parse(MapsStopLink.expand("https://maps.app.goo.gl/$short", true) { full })
            assertTrue(imported.valid())
            assertEquals(24.3810487, imported.stop.latitude!!, 0.0000001)
            assertEquals(54.5095537, imported.stop.longitude!!, 0.0000001)
            assertEquals(24.4866905, imported.destinationPlace!!.latitude!!, 0.0000001)
            assertEquals(54.3647146, imported.destinationPlace!!.longitude!!, 0.0000001)
            assertEquals(imported, BusAlert.decode(BusAlert.encode(BusAlert(listOf(imported))))!!.journeys.single())
            assertFalse(imported.hasTimetable())
        }
    }
    @Test fun requiresFixedEndpointsAndSupportsStandardMapsUrls() {
        val prefix = "https://www.google.com/maps/dir/"
        for (bad in listOf("/Destination", "A/B/C", "Your+location/B", "99,54/B", "?api=1&origin=A&destination=B&waypoints=C")) {
            assertThrows(IllegalArgumentException::class.java) { MapsDirectionsLink.parse(URI(prefix + bad)) }
        }
        val standard = MapsDirectionsLink.parse(URI(prefix + "?api=1&origin=Office&origin_place_id=ChIJabc&destination=Home"))
        assertEquals("ChIJabc", standard.stop.placeId)
        assertEquals("Home", standard.destination)
    }
    @Test fun skipsUnreachableTransferAndReadsEveryAcceptedLegInOrder() {
        val result = select(alternative(first, second("13:22", "13:35")),
            alternative(first, walk(240), second("13:27", "13:45")),
            alternative(first, walk(240), second("13:30", "14:00")), filter = "m2, 102")!!
        val words = result.announcement(BusAlert(listOf(route)), route, now)
        for (phrase in listOf("M2 at Office stop at 5:10 PM", "Exchange A at 5:25 PM", "Walk about 4 minutes",
            "102 at Exchange B at 5:30 PM", "City stop at 6:00 PM")) assertTrue(words, phrase in words)
        val relative = result.announcement(BusAlert(listOf(route), readTime = false, readMinutes = true, readStop = false), route, now)
        assertTrue(relative.contains("M2 in 20 minutes"))
        assertFalse(relative.contains("Exchange"))
        assertFalse(relative.contains("PM"))
    }
    @Test fun rejectsIncompleteMixedModeOrWrongLinePlansAndNeverStitchesAlternatives() {
        val direct = alternative(ride("X", "13:03", "13:57", "Start", "End"), walk(600))
        val complete = alternative(first, second("13:30", "14:00"))
        assertEquals(listOf("M2","102"), select(direct, complete)!!.rides.map { it.line })
        assertNull(select(alternative(first), alternative(second("13:30", "14:00")), filter = "M2, 102"))
        assertNull(select(alternative(first, ride("102", "13:30", "14:00", "A", "B", "SUBWAY"))))
        assertNull(select(alternative(first, """{"travelMode":"TRANSIT"}""")))
        assertNull(select(alternative(walk(1800), first)))
    }
}
