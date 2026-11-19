package com.ghostgramlabs.speakalert.transit

import android.content.Context
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

internal object BusGoogleLookup {
    data class Result(val status: String, val estimate: Instant? = null, val connection: BusConnection? = null)
    suspend fun announce(context: Context, text: String): String {
        val alert = BusAlert.decode(text) ?: return "Bus alert settings could not be read. Please edit this reminder."
        val active = alert.journeys.filter { it.enabled }.distinctBy { lookupKey(it) }
        val checked = withTimeoutOrNull(25_000) {
            active.map { journey -> journey to check(context, journey) }
        }
        return (checked ?: active.map { it to Result("Lookup timed out.") }).joinToString(" ") { (journey, result) ->
            if (journey.connectedRoute) result.connection?.announcement(alert, journey, Instant.now())
                ?: "Bus route from ${journey.stop.name} to ${journey.destination}. ${result.status}"
            else BusAlert.announcement(journey, result.estimate, Instant.now(), alert)
        }
    }

    suspend fun check(context: Context, journey: BusJourney): Result = withContext(Dispatchers.IO) {
        try {
            val key = M2GoogleLookup.readKey(context) ?: return@withContext Result("No Routes API key saved. Configure Google Maps below.")
            request(context, key, journey)
        } catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (_: java.io.IOException) { Result("Could not reach Google Maps. Check your connection.") }
        catch (_: RuntimeException) { Result("Could not read the Google Maps response.") }
    }

    @Suppress("DEPRECATION")
    private fun request(context: Context, key: String, journey: BusJourney): Result {
        require(journey.valid())
        val signer = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures?.firstOrNull() ?: return Result("Could not verify app signature.")
        val sha1 = MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02X".format(it) }
        val connection = URL("https://routes.googleapis.com/directions/v2:computeRoutes").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2000
            connection.readTimeout = 2500
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Goog-Api-Key", key)
            connection.setRequestProperty("X-Android-Package", context.packageName)
            connection.setRequestProperty("X-Android-Cert", sha1)
            val now = Instant.now()
            val fields = if (journey.connectedRoute) "routes.legs.steps.travelMode,routes.legs.steps.staticDuration,routes.legs.steps.transitDetails"
                else "routes.legs.steps.transitDetails"
            connection.setRequestProperty("X-Goog-FieldMask", fields)
            val origin = origin(journey.stop)
            val body = mapOf("origin" to origin, "destination" to destination(journey),
                "departureTime" to now.toString(), "travelMode" to "TRANSIT", "transitPreferences" to mapOf("allowedTravelModes" to listOf("BUS")),
                "languageCode" to "en", "regionCode" to "AE", "computeAlternativeRoutes" to true)
            connection.outputStream.use { it.write(Gson().toJson(body).toByteArray()) }
            val code = connection.responseCode
            if (code != 200) return Result("Google Maps returned HTTP $code. Check the Routes API key and billing.")
            val reply = connection.inputStream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            if (journey.connectedRoute) {
                val connectionPlan = BusConnections.select(reply, journey, now)
                    ?: return Result(if (journey.line.isBlank()) "Google Maps returned no reachable bus itinerary."
                        else "Google Maps returned no reachable connection using ${journey.linesInOrder.joinToString(" then ")}.")
                return Result(connectionPlan.announcement(BusAlert(listOf(journey)), journey, now), connection = connectionPlan)
            }
            val estimate = selectEstimate(reply, journey, now)
            return if (estimate == null) Result("Connected. No matching first-leg bus ${journey.line} was returned at this stop. This is not a complete departure board.")
                else Result("Connected. " + BusAlert.announcement(journey, estimate, Instant.now(), BusAlert(listOf(journey))), estimate)
        } finally { connection.disconnect() }
    }

    internal fun destination(journey: BusJourney): Map<String, Any> =
        journey.destinationPlace?.let { origin(it) } ?: mapOf("address" to journey.destination)

    internal fun lookupKey(journey: BusJourney): List<Any> = listOf(journey.connectedRoute, journey.stop.id, journey.line.uppercase(),
        journey.destinationPlace?.let { origin(it) } ?: journey.destination.lowercase())

    internal fun origin(stop: BusStop): Map<String, Any> = when {
        BusStop.validPlaceId(stop.placeId) -> mapOf("placeId" to stop.placeId!!)
        stop.latitude != null && stop.longitude != null ->
            mapOf("location" to mapOf("latLng" to mapOf("latitude" to stop.latitude, "longitude" to stop.longitude)))
        else -> mapOf("address" to stop.address)
    }

    fun selectEstimate(reply: JsonObject, journey: BusJourney, now: Instant): Instant? {
        return reply.getAsJsonArray("routes")?.mapNotNull { route -> runCatching {
            val steps = route.asJsonObject.getAsJsonArray("legs")?.firstOrNull()?.asJsonObject?.getAsJsonArray("steps")
                ?: return@runCatching null
            val transit = steps.firstNotNullOfOrNull { it.asJsonObject.getAsJsonObject("transitDetails") }
                ?: return@runCatching null
            val line = transit.getAsJsonObject("transitLine") ?: return@runCatching null
            if (!journey.line.trim().equals(line.get("nameShort")?.asString, true)) return@runCatching null
            val type = line.getAsJsonObject("vehicle")?.get("type")?.asString
            if (type != null && type !in setOf("BUS", "INTERCITY_BUS", "TROLLEYBUS")) return@runCatching null
            val details = transit.getAsJsonObject("stopDetails") ?: return@runCatching null
            val stop = details.getAsJsonObject("departureStop") ?: return@runCatching null
            val coords = stop.getAsJsonObject("location")?.getAsJsonObject("latLng")
            if (!BusAlert.matchesStop(journey.stop, stop.get("name")?.asString.orEmpty(),
                    coords?.get("latitude")?.asDouble, coords?.get("longitude")?.asDouble)) return@runCatching null
            Instant.parse(details.get("departureTime").asString).takeIf { it >= now && it < now.plusSeconds(3 * 3600L) }
        }.getOrNull() }?.minOrNull()
    }
}
