package com.ghostgramlabs.speakalert.transit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import javax.net.ssl.HttpsURLConnection

internal object MapsStopLinkResolver {
    suspend fun read(text: String, context: Context? = null, stops: List<BusStop> = emptyList()): MapsStopLink.Draft = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val parsed = MapsStopLink.resolve(text) { uri ->
            job.ensureActive()
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 2500
                connection.readTimeout = 2500
                // Expansion itself uses no key, cookies or page scraping.
                if (connection.responseCode in setOf(301, 302, 303, 307, 308))
                    connection.getHeaderField("Location") else null
            } finally { connection.disconnect() }
        }
        val draft = MapsStopLink.fillFromCatalogue(parsed, stops)
        if (draft.hasLocation || context == null) return@withContext draft
        val key = M2GoogleLookup.readKey(context)
            ?: return@withContext draft.copy(locationIssue =
                "This link needs a Google Maps lookup. Add a key for automatic identification, or enter the full address.")
        job.ensureActive()
        val result = try {
            MapsPlaceResolution.request(context, key, MapsStopLink.link(text).toASCIIString())
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (_: java.io.IOException) { MapsPlaceResolution.Result(issue =
            "Could not retrieve this place's location. Check your connection or enter the full address.") }
        catch (_: RuntimeException) { MapsPlaceResolution.Result(issue =
            "Google's place location could not be read. Retry or enter the full address.") }
        draft.copy(placeId = result.placeId, locationIssue = result.issue)
    }
}
