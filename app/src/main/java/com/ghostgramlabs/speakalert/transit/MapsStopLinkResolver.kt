package com.ghostgramlabs.speakalert.transit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import javax.net.ssl.HttpsURLConnection

internal object MapsStopLinkResolver {
    suspend fun read(text: String): MapsStopLink.Draft = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        MapsStopLink.resolve(text) { uri ->
            job.ensureActive()
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 2500
                connection.readTimeout = 2500
                // No API key, cookies, HTML scraping or Routes/Places requests.
                if (connection.responseCode in setOf(301, 302, 303, 307, 308))
                    connection.getHeaderField("Location") else null
            } finally { connection.disconnect() }
        }
    }
}
