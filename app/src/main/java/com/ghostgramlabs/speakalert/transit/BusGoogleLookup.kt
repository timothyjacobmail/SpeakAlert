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

object BusGoogleLookup {
    data class Result(val status: String, val estimate: Instant? = null)
    suspend fun announce(context: Context, text: String): String {
        val alert = BusAlert.decode(text) ?: return "Bus alert settings could not be read. Please edit this reminder."
        val active = alert.journeys.filter { it.enabled }.distinctBy {
            listOf(it.stop.id, it.line.uppercase(), it.destination.lowercase())
        }
        val checked = withTimeoutOrNull(25_000) {
            active.map { journey -> journey to check(context, journey) }
        }
        return (checked ?: active.map { it to Result("Lookup timed out.") }).joinToString(" ") { (journey, result) ->
            BusAlert.announcement(journey, result.estimate, Instant.now(), alert)
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
            connection.setRequestProperty("X-Goog-FieldMask", "routes.legs.steps.transitDetails")
            val stop = journey.stop
            val origin = if (stop.latitude != null && stop.longitude != null)
                mapOf("location" to mapOf("latLng" to mapOf("latitude" to stop.latitude, "longitude" to stop.longitude)))
                else mapOf("address" to stop.address)
            val body = mapOf("origin" to origin, "destination" to mapOf("address" to journey.destination),
                "travelMode" to "TRANSIT", "transitPreferences" to mapOf("allowedTravelModes" to listOf("BUS")),
                "languageCode" to "en", "regionCode" to "AE", "computeAlternativeRoutes" to true)
            connection.outputStream.use { it.write(Gson().toJson(body).toByteArray()) }
            val code = connection.responseCode
            if (code != 200) return Result("Google Maps returned HTTP $code. Check the Routes API key and billing.")
            val estimate = connection.inputStream.bufferedReader().use {
                selectEstimate(JsonParser.parseReader(it).asJsonObject, journey, Instant.now())
            }
            return if (estimate == null) Result("Connected. No matching first-leg bus ${journey.line} was returned at this stop. This is not a complete departure board.")
                else Result("Connected. " + BusAlert.announcement(journey, estimate, Instant.now(), BusAlert(listOf(journey))), estimate)
        } finally { connection.disconnect() }
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
