package com.ghostgramlabs.speakalert.transit

import kotlin.math.*

data class StopMapPoint(val latitude: Double, val longitude: Double)

object StopPickerResults {
    fun matches(stop: BusStop, query: String): Boolean = query.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.all { word ->
            stop.name.contains(word, true) || stop.id.contains(word, true) || stop.arabicName.contains(word, true)
        }

    fun distance(stop: BusStop, point: StopMapPoint): Double {
        val lat = stop.latitude ?: return Double.MAX_VALUE
        val lon = stop.longitude ?: return Double.MAX_VALUE
        val y = (lat - point.latitude) * PI / 180
        val x = (lon - point.longitude) * PI / 180 * cos(point.latitude * PI / 180)
        return sqrt(x * x + y * y) * 6_371_000
    }

    fun ranked(stops: List<BusStop>, query: String, point: StopMapPoint, limit: Int = 60): List<BusStop> =
        stops.asSequence().filter { matches(it, query) }
            .sortedWith(compareBy<BusStop> { if (query.isNotBlank() && it.id.equals(query.trim(), true)) 0 else 1 }
                .thenBy { distance(it, point) }.thenBy { it.name })
            .take(limit).toList()
}
