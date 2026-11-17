package com.ghostgramlabs.speakalert.transit

import android.content.Context
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

internal object M2GoogleLookup {
    private fun keyFile(context: Context) = File(context.noBackupFilesDir, "m2-routes-key")
    fun configured(context: Context) = keyFile(context).isFile

    fun setKey(context: Context, value: String) {
        val file = android.util.AtomicFile(keyFile(context))
        if (value.isBlank()) {
            file.delete()
            return
        }
        val stream = file.startWrite()
        try {
            stream.write(value.trim().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    suspend fun announce(context: Context): String = withContext(Dispatchers.IO) {
        val estimate = try {
            val file = keyFile(context)
            if (file.isFile) request(context, android.util.AtomicFile(file).readFully().decodeToString()) else null
        } catch (_: java.io.IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        M2Timetable.announcement(Instant.now(), estimate)
    }

    // Restrict the user's key in Google Cloud to Routes API and this Android package/certificate.
    @Suppress("DEPRECATION")
    private fun request(context: Context, key: String): Instant? {
        val signatures = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        val signer = signatures?.firstOrNull() ?: return null
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
            val body = mapOf(
                "origin" to mapOf("address" to "Al Hayal St / Tawjeeh Integrated Services bus stop, Mussafah, Abu Dhabi, UAE"),
                "destination" to mapOf("address" to "Mazyad Mall, Mohamed Bin Zayed City, Abu Dhabi, UAE"),
                "travelMode" to "TRANSIT", "transitPreferences" to mapOf("allowedTravelModes" to listOf("BUS")),
                "languageCode" to "en", "regionCode" to "AE", "computeAlternativeRoutes" to true
            )
            connection.outputStream.use { it.write(Gson().toJson(body).toByteArray()) }
            if (connection.responseCode != 200) return null
            return connection.inputStream.bufferedReader().use { reader ->
                selectEstimate(JsonParser.parseReader(reader).asJsonObject, Instant.now())
            }
        } finally {
            connection.disconnect()
        }
    }

    // A route can walk to a different boarding stop or transfer to M2 later.
    // Only the first transit leg of each alternative can represent this departure.
    internal fun selectEstimate(reply: JsonObject, now: Instant): Instant? {
        val routes = reply.getAsJsonArray("routes") ?: return null
        return routes.mapNotNull { route ->
            val steps = route.asJsonObject.getAsJsonArray("legs")?.firstOrNull()
                ?.asJsonObject?.getAsJsonArray("steps") ?: return@mapNotNull null
            val transit = steps.firstNotNullOfOrNull { it.asJsonObject.getAsJsonObject("transitDetails") }
                ?: return@mapNotNull null
            val line = transit.getAsJsonObject("transitLine")?.get("nameShort")?.asString
            if (!"M2".equals(line, ignoreCase = true)) return@mapNotNull null
            val details = transit.getAsJsonObject("stopDetails") ?: return@mapNotNull null
            val stop = details.getAsJsonObject("departureStop")?.get("name")?.asString.orEmpty().lowercase()
            if ("al hayal" !in stop || !("tawjeeh" in stop || "as salami" in stop || "al salami" in stop)) return@mapNotNull null
            val time = details.get("departureTime")?.asString ?: return@mapNotNull null
            runCatching { Instant.parse(time) }.getOrNull()?.takeIf { it >= now && it < now.plusSeconds(3 * 3600L) }
        }.minOrNull()
    }
}
