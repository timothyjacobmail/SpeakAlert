package com.ghostgramlabs.speakalert.transit

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class M2TimetableTest {
    private val now = Instant.parse("2026-11-17T12:50:00Z")

    @Test fun weekdayAnnouncementAndMissedDeparture() {
        assertEquals("The timetable lists the next M2 at Al Hayal Street for 5:03 PM.", M2Timetable.announcement(now, null))
        val scheduled = Instant.parse("2026-11-17T13:03:00Z")
        assertEquals(scheduled, M2Timetable.nextScheduled(scheduled))
        assertEquals(Instant.parse("2026-11-17T13:26:00Z"), M2Timetable.nextScheduled(scheduled.plusSeconds(1)))
        assertTrue(M2Timetable.announcement(Instant.parse("2026-11-17T19:02:00Z"), null).endsWith("7:23 AM tomorrow."))
    }

    @Test fun fridayEveningSchedulesMondayInDubai() {
        assertEquals(Instant.parse("2026-11-23T12:50:00Z"), M2Timetable.nextWeekdayAlarm(Instant.parse("2026-11-20T12:50:00Z")))
    }

    @Test fun selectsOnlyAnUpcomingM2BoardedOnAlHayal() {
        fun step(stop: String, line: String, time: String) = """
          {"transitDetails":{"transitLine":{"nameShort":"$line"},"stopDetails":{"departureStop":{"name":"$stop"},"departureTime":"$time"}}}
        """.trimIndent()
        val correct = step("Al Hayal St / Tawjeeh Integrated Services", "M2", "2026-11-17T13:08:00Z")
        val wrongStop = step("Al Salami St / Tawjeeh Integrated Services", "M2", "2026-11-17T12:57:00Z")
        val otherLine = step("Al Hayal St / Tawjeeh Integrated Services", "111", "2026-11-17T12:55:00Z")
        val past = step("Al Hayal St / Tawjeeh Integrated Services", "M2", "2026-11-17T12:49:00Z")
        fun reply(vararg trips: String) = JsonParser.parseString(trips.joinToString(
            prefix = """{"routes":[""", postfix = "]}"
        ) { """{"legs":[{"steps":[$it]}]}""" }).asJsonObject
        assertNull(M2GoogleLookup.selectEstimate(reply(wrongStop, "$otherLine,$correct", past), now))
        val estimate = M2GoogleLookup.selectEstimate(reply(wrongStop, correct), now)
        assertEquals(Instant.parse("2026-11-17T13:08:00Z"), estimate)
        assertEquals("Google Maps estimates the next M2 at Al Hayal Street for 5:08 PM.", M2Timetable.announcement(now, estimate))
    }
}
