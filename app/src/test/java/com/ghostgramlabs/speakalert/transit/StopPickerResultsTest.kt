package com.ghostgramlabs.speakalert.transit

import org.junit.Assert.*
import org.junit.Test

class StopPickerResultsTest {
    private val center = StopMapPoint(24.38, 54.51)
    private val near = BusStop("A", "Near stop", "Abu Dhabi", 24.3801, 54.5101, "محطة قريبة")
    private val far = BusStop("B", "Far stop", "Al Ain", 24.2, 55.7)
    @Test fun emptySearchRanksNearbyStopsBeforeFarOrMissingCoordinates() {
        val unknown = BusStop("custom-x", "Custom stop", "An address")
        assertEquals(listOf(near, far, unknown), StopPickerResults.ranked(listOf(far, unknown, near), "", center))
        assertTrue(StopPickerResults.distance(near, center) < 20)
    }
    @Test fun searchesNamesArabicCodesAndMultipleTermsBeforeRanking() {
        assertEquals(listOf(near), StopPickerResults.ranked(listOf(far, near), "NEAR stop", center))
        assertEquals(listOf(near), StopPickerResults.ranked(listOf(far, near), "قريبة", center))
        assertEquals(listOf(far), StopPickerResults.ranked(listOf(far, near), "B", center))
        assertTrue(StopPickerResults.ranked(listOf(far, near), "missing", center).isEmpty())
    }
    @Test fun exactStopCodeWinsAndResultCountIsBounded() {
        val exactFar = far.copy(id = "11700")
        val partialNear = near.copy(id = "11700B")
        assertEquals(exactFar, StopPickerResults.ranked(listOf(partialNear, exactFar), "11700", center).first())
        assertEquals(1, StopPickerResults.ranked(listOf(near, far), "", center, 1).size)
    }
}
