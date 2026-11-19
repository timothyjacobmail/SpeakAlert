package com.ghostgramlabs.speakalert.transit

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/** Two fixed endpoints. Google computes fresh transit steps when the reminder runs. */
internal object MapsDirectionsLink {
    fun isDirections(uri: URI) = uri.path == "/maps/dir" || uri.path.startsWith("/maps/dir/")

    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
    private val coordinates = Regex("""^([-+]?\d+(?:\.\d+)?),\s*([-+]?\d+(?:\.\d+)?)$""")
    // Google's first named endpoint block uses longitude first, followed by latitude.
    private val originPin = Regex("""^!4m\d+!4m\d+!1m5!1m1!1s[^!]+!2m2!1d([-\d.]+)!2d([-\d.]+)(?:!|$)""")

    fun parse(uri: URI): BusJourney {
        require(isDirections(uri)) { "Copy a directions link with a starting point and destination from Google Maps." }
        val query = uri.rawQuery.orEmpty().split('&').associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        require(query["waypoints"].isNullOrBlank()) { "Choose a route with just a starting point and a destination. Transfers are found automatically." }
        val raw = uri.rawPath.removePrefix("/maps/dir").removePrefix("/").split('/')
        val endpoints = raw.takeWhile { !it.startsWith("@") && !it.startsWith("data=") && !it.startsWith("am=") }
            .dropLastWhile { it.isEmpty() }.map(::decode)
        val from: String
        val to: String
        if (query.containsKey("origin") || query.containsKey("destination")) {
            from = query["origin"].orEmpty()
            to = query["destination"].orEmpty()
        } else {
            require(endpoints.size == 2) { "Share directions between two fixed places. Transfers are found automatically." }
            from = endpoints[0]
            to = endpoints[1]
        }
        val pin = originPin.find(decode(raw.firstOrNull { it.startsWith("data=") }?.removePrefix("data=").orEmpty()))
        val origin = endpoint(from, query["origin_place_id"], pin?.groupValues?.get(2), pin?.groupValues?.get(1))
        val destination = endpoint(to, query["destination_place_id"])
        return BusJourney(origin, "", destination.name, destinationPlace = destination, connectedRoute = true)
    }

    private fun endpoint(raw: String, id: String?, dataLat: String? = null, dataLon: String? = null): BusStop {
        val label = raw.trim()
        require(label.length in 1..300 && listOf("your location", "current location", "my location").none { label.equals(it, true) }) {
            "Select a fixed starting point and destination in Maps before sharing."
        }
        val pair = coordinates.matchEntire(label)
        val lat = (pair?.groupValues?.get(1) ?: dataLat)?.toDoubleOrNull()
        val lon = (pair?.groupValues?.get(2) ?: dataLon)?.toDoubleOrNull()
        val name = if (pair != null) label else label.substringBefore(',').ifBlank { label }.take(180)
        return BusStop("custom-" + UUID.randomUUID(), name, label, lat, lon, placeId = id)
            .also { require(it.valid()) { "Google Maps supplied an invalid route endpoint." } }
    }
}
