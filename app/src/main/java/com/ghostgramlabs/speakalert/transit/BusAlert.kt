package com.ghostgramlabs.speakalert.transit

import com.google.gson.Gson
import androidx.annotation.Keep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

@Keep
data class BusStop(val id: String, val name: String, val address: String,
    val latitude: Double? = null, val longitude: Double? = null, val arabicName: String = "", val placeId: String? = null) {
    val codeLabel: String get() = if (id.startsWith("custom-")) "Custom stop" else "Stop $id"
    fun valid() = id.isNotBlank() && id.length <= 80 && name.isNotBlank() && name.length <= 180 && (address.isNotBlank() || validPlaceId(placeId)) && address.length <= 300 &&
        (placeId == null || validPlaceId(placeId)) &&
        ((latitude == null && longitude == null) || (latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0))
    companion object {
        fun validPlaceId(value: String?) = value != null && value.matches(Regex("[A-Za-z0-9_-]{1,2048}"))
    }
}

@Keep
data class BusJourney(val stop: BusStop, val line: String, val destination: String,
    val enabled: Boolean = true, val offlineM2: Boolean = false, val destinationPlace: BusStop? = null) {
    fun valid() = stop.valid() && line.isNotBlank() && line.length <= 24 &&
        destination.isNotBlank() && destination.length <= 240 &&
        (destinationPlace == null || (destinationPlace.valid() && destination == destinationPlace.name))
    fun hasTimetable() = destinationPlace == null && offlineM2 && stop == BusAlert.defaultStop && line.equals("M2", true) &&
        destination == BusAlert.defaultDestination
}

@Keep
data class BusAlert(val journeys: List<BusJourney>, val readTime: Boolean = true,
    val readMinutes: Boolean = false, val readStop: Boolean = true, val readDirection: Boolean = false) {
    fun valid() = journeys.size in 1..8 && journeys.all { it.valid() } && journeys.any { it.enabled } &&
        (readTime || readMinutes)
    companion object {
        private const val PREFIX = "Bus alerts · "
        private const val MARKER = "\n[SpeakAlertBus:1]\n"
        val defaultStop = BusStop("11700", "Al Hayal St / Tawjeeh Integrated Services",
            "Al Hayal St / Tawjeeh Integrated Services bus stop, Mussafah, Abu Dhabi, UAE", 24.3799795, 54.5104999)
        const val defaultDestination = "Mazyad Mall, Mohamed Bin Zayed City, Abu Dhabi, UAE"
        fun default() = BusAlert(listOf(BusJourney(defaultStop, "M2", defaultDestination, offlineM2 = true)))
        fun isToken(text: String?) = text == M2Timetable.TOKEN ||
            (text?.startsWith(PREFIX) == true && text.contains("\n[SpeakAlertBus:"))
        fun decode(text: String?): BusAlert? {
            if (text == M2Timetable.TOKEN) return default()
            if (!isToken(text) || text == null || text.length > 32000) return null
            return runCatching { Gson().fromJson(text.substringAfter(MARKER), BusAlert::class.java).takeIf { it.valid() } }.getOrNull()
        }
        fun encode(alert: BusAlert): String {
            require(alert.valid())
            val summary = alert.journeys.filter { it.enabled }.joinToString("; ") { "${it.line} at ${it.stop.name}" }
            return PREFIX + summary + MARKER + Gson().toJson(alert)
        }
        fun display(text: String?): String? = if (isToken(text) && text != M2Timetable.TOKEN)
            text?.substringBefore("\n[SpeakAlertBus:") else text

        fun matchesStop(stop: BusStop, name: String, latitude: Double?, longitude: Double?): Boolean {
            fun normalized(s: String) = s.lowercase(Locale.ROOT).replace(Regex("\\bstreet\\b"), "st")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            val nameMatches = normalized(name) == normalized(stop.name) ||
                (stop == defaultStop && "al hayal" in normalized(name) &&
                    listOf("tawjeeh", "as salami", "al salami").any { it in normalized(name) })
            if (!nameMatches) return false
            if (stop.latitude != null && stop.longitude != null && latitude != null && longitude != null) {
                val radians = Math.PI / 180
                val dy = (latitude - stop.latitude) * radians
                val dx = (longitude - stop.longitude) * radians * cos(stop.latitude * radians)
                return sqrt(dx * dx + dy * dy) * 6_371_000 < 90
            }
            return true
        }

        fun announcement(journey: BusJourney, departure: Instant?, now: Instant, options: BusAlert,
            zone: ZoneId = M2Timetable.zone): String {
            val stop = if (options.readStop) " at ${journey.stop.name.take(90)}" else ""
            val direction = if (options.readDirection) " toward ${journey.destination.take(90)}" else ""
            val next = departure?.takeIf { it >= now } ?: if (journey.hasTimetable()) M2Timetable.nextScheduled(now) else null
            if (next == null) return "No matching Google Maps estimate is available for bus ${journey.line}$stop$direction."
            val source = if (departure != null && departure >= now) "Google Maps estimates" else "The timetable lists"
            val details = mutableListOf<String>()
            if (options.readTime) {
                val day = next.atZone(zone).toLocalDate()
                val suffix = if (day > now.atZone(zone).toLocalDate()) " tomorrow" else ""
                details.add("at " + DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(next.atZone(zone)) + suffix)
            }
            if (options.readMinutes) {
                val minutes = ((next.epochSecond - now.epochSecond + 59) / 60).coerceAtLeast(0)
                details.add(if (minutes == 0L) "now" else "in $minutes ${if (minutes == 1L) "minute" else "minutes"}")
            }
            return "$source bus ${journey.line}$stop$direction ${details.joinToString(", ")}."
        }
    }
}
