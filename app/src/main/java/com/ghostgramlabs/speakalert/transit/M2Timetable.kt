package com.ghostgramlabs.speakalert.transit

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object M2Timetable {
    const val TOKEN = "Next M2 departure · Al Hayal St / Tawjeeh Integrated Services (11700)"
    const val TITLE = "M2 at Al Hayal Street"
    val zone: ZoneId = ZoneId.of("Asia/Dubai")
    val weekdays = setOf(1, 2, 3, 4, 5)

    // Abu Dhabi Mobility, BusServiceM2.pdf (20 March 2026), stop 11700, pages 4–6.
    // The weekday and weekend rows for this stop have the same departures.
    private val departures = """
        07:23 07:57 08:40 09:10 09:30 10:00 10:30 11:30 12:27 13:26 14:29
        15:28 16:33 17:03 17:26 18:10 18:44 19:14 19:38 20:04 20:45 22:17 23:01
    """.trim().split(Regex("\\s+")).map(LocalTime::parse)
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun nextScheduled(now: Instant): Instant {
        val local = now.atZone(zone)
        val next = departures.firstOrNull { !it.isBefore(local.toLocalTime()) }
        val day = if (next == null) local.toLocalDate().plusDays(1) else local.toLocalDate()
        return day.atTime(next ?: departures.first()).atZone(zone).toInstant()
    }

    fun nextWeekdayAlarm(now: Instant): Instant {
        val local = now.atZone(zone)
        var target = local.toLocalDate().atTime(16, 50).atZone(zone)
        while (!target.isAfter(local) || target.dayOfWeek.value !in weekdays) {
            target = target.plusDays(1)
        }
        return target.toInstant()
    }

    fun announcement(now: Instant, estimate: Instant?): String {
        val departure = estimate?.takeUnless { it.isBefore(now) } ?: nextScheduled(now)
        val local = departure.atZone(zone)
        val tomorrow = if (local.toLocalDate() > now.atZone(zone).toLocalDate()) " tomorrow" else ""
        val source = if (departure == estimate) "Google Maps estimates" else "The timetable lists"
        return "$source the next M2 at Al Hayal Street for ${clock.format(local)}$tomorrow."
    }
}
