package com.ghostgramlabs.speakalert.transit

import org.junit.Assert.*
import org.junit.Test

class MapsShareInputTest {
    private val send = "android.intent.action.SEND"
    private val link = "https://maps.app.goo.gl/example"
    @Test fun acceptsMapsTextAndOptionalSubjectWithoutOpeningIt() {
        assertEquals(link, MapsShareInput.read(send, "text/plain", link))
        assertEquals("Bus stop\n$link", MapsShareInput.read(send, "text/plain", link, "Bus stop"))
        assertEquals("Already titled\n$link", MapsShareInput.read(send, "text/plain", "Already titled\n$link", "Other title"))
    }
    @Test fun rejectsUnsupportedActionsTypesAndMissingOrOversizedPayloads() {
        assertNull(MapsShareInput.read("android.intent.action.VIEW", "text/plain", link))
        assertNull(MapsShareInput.read("android.intent.action.SEND_MULTIPLE", "text/plain", link))
        assertNull(MapsShareInput.read(send, "image/png", link))
        assertNull(MapsShareInput.read(send, null, link))
        assertNull(MapsShareInput.read(send, "text/plain", null))
        assertNull(MapsShareInput.read(send, "text/plain", ""))
        assertNull(MapsShareInput.read(send, "text/plain", link + "x".repeat(10_000)))
    }
    @Test fun rejectsDirectionsUnrelatedTextAndLookalikeDomains() {
        for (text in listOf("hello", "https://example.com", "https://google.com.evil.test/maps/place/x",
            "https://www.google.com/maps/dir/A/B", "http://maps.google.com/?q=x")) {
            assertNull(MapsShareInput.read(send, "text/plain", text))
        }
    }
    @Test fun addingSubjectNeverTruncatesTheOriginalLink() {
        val longLink = "https://www.google.com/maps/place/" + "a".repeat(9940)
        assertEquals(longLink, MapsShareInput.read(send, "text/plain", longLink, "b".repeat(180)))
    }
}
