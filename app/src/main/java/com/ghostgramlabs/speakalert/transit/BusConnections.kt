package com.ghostgramlabs.speakalert.transit

import com.google.gson.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class BusRide(val line: String, val board: String, val depart: Instant,
    val alight: String, val arrive: Instant, val walkBefore: Duration)

internal data class BusConnection(val rides: List<BusRide>, val finish: Instant) {
    fun announcement(alert: BusAlert, journey: BusJourney, now: Instant): String {
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(M2Timetable.zone)
        fun time(at: Instant): String {
            val nextDay = at.atZone(M2Timetable.zone).toLocalDate() > now.atZone(M2Timetable.zone).toLocalDate()
            val minutes = Duration.between(now, at).seconds.coerceAtLeast(0).let { (it + 59) / 60 }
            return listOfNotNull(
                if (alert.readTime) "at ${clock.format(at)}" + if (nextDay) " tomorrow" else "" else null,
                if (alert.readMinutes) "in $minutes ${if (minutes == 1L) "minute" else "minutes"}" else null
            ).joinToString(", ")
        }
        return buildString {
            append("Google Maps estimates")
            if (alert.readDirection) append(" for the route to ${journey.destination}")
            append(". ")
            rides.forEachIndexed { index, ride ->
                if (index > 0 && !ride.walkBefore.isZero) {
                    val minutes = (ride.walkBefore.seconds + 59) / 60
                    append("Walk about $minutes ${if (minutes == 1L) "minute" else "minutes"} to connect. ")
                }
                append(if (index == 0) "Board bus " else "Then board bus ")
                append(ride.line)
                if (alert.readStop) append(" at ${ride.board}")
                append(" ${time(ride.depart)}. Get off")
                if (alert.readStop) append(" at ${ride.alight}")
                append(" ${time(ride.arrive)}. ")
            }
        }.trim()
    }
}

internal object BusConnections {
    fun select(reply: JsonObject, journey: BusJourney, now: Instant): BusConnection? {
        val alternatives = reply.getAsJsonArray("routes") ?: return null
        return alternatives.mapNotNull { runCatching { parse(it.asJsonObject, journey, now) }.getOrNull() }
            .minWithOrNull(compareBy<BusConnection> { it.finish }.thenBy { it.rides.size }.thenBy { it.rides.first().depart })
    }

    private fun parse(route: JsonObject, journey: BusJourney, now: Instant): BusConnection? {
        val rides = mutableListOf<BusRide>()
        var ready = now
        var walk = Duration.ZERO
        val steps = route.getAsJsonArray("legs").single().asJsonObject.getAsJsonArray("steps")
        for (item in steps) {
            val step = item.asJsonObject
            when (step.get("travelMode")?.asString) {
                "WALK" -> {
                    val seconds = step.get("staticDuration")?.asString ?: return null
                    val duration = Duration.parse("PT" + seconds.uppercase(Locale.ROOT))
                    if (duration.isNegative) return null
                    ready = ready.plus(duration)
                    walk = walk.plus(duration)
                }
                "TRANSIT" -> {
                    val transit = step.getAsJsonObject("transitDetails")
                    val line = transit.getAsJsonObject("transitLine")
                    if (line.getAsJsonObject("vehicle").get("type").asString !in
                        setOf("BUS", "INTERCITY_BUS", "TROLLEYBUS")) return null
                    val name = (line.get("nameShort") ?: line.get("name"))?.asString?.trim().orEmpty()
                    val details = transit.getAsJsonObject("stopDetails")
                    val board = details.getAsJsonObject("departureStop").get("name").asString
                    val alight = details.getAsJsonObject("arrivalStop").get("name").asString
                    val depart = Instant.parse(details.get("departureTime").asString)
                    val arrive = Instant.parse(details.get("arrivalTime").asString)
                    if (name.isBlank() || board.isBlank() || alight.isBlank() || depart < ready || arrive <= depart ||
                        (rides.isEmpty() && depart >= now.plusSeconds(3 * 3600L))) return null
                    rides.add(BusRide(name, board, depart, alight, arrive, walk))
                    ready = arrive
                    walk = Duration.ZERO
                }
                else -> return null
            }
        }
        if (rides.isEmpty()) return null
        if (journey.line.isNotBlank() && journey.linesInOrder.map { it.uppercase(Locale.ROOT) } !=
            rides.map { it.line.uppercase(Locale.ROOT) }) return null
        return BusConnection(rides, ready)
    }
}
