package com.ghostgramlabs.speakalert.transit

import android.content.Context
import android.content.pm.PackageManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** One request per reference-only import. Does not run during playback or in the background. */
internal object MapsPlaceResolution {
    data class Result(val placeId: String? = null, val issue: String? = null)

    @Suppress("DEPRECATION")
    fun request(context: Context, key: String, mapsUrl: String): Result {
        val signature = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            .signatures?.firstOrNull()
        val connection = URL("https://mapstools.googleapis.com/v1:resolveMapsUrls").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2500
            connection.readTimeout = 3500
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Goog-Api-Key", key)
            connection.setRequestProperty("X-Android-Package", context.packageName)
            signature?.let {
                val sha1 = MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).joinToString("") { byte -> "%02X".format(byte) }
                connection.setRequestProperty("X-Android-Cert", sha1)
            }
            connection.outputStream.use { it.write(Gson().toJson(mapOf("urls" to listOf(mapsUrl))).toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code != 200) return parse(code, null)
            val json = connection.inputStream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            return parse(code, json)
        } finally { connection.disconnect() }
    }

    fun parse(code: Int, body: JsonObject?): Result {
        if (code == 429) return Result(issue = "Google's link lookup limit was reached. Try later or enter the full address.")
        if (code == 401 || code == 403) return Result(issue =
            "The saved key could not identify this place. Check Maps Experience agent tools access, or enter the full address.")
        if (code != 200) return Result(issue = "Google could not identify this place right now. Retry or enter the full address.")
        val place = runCatching { body?.getAsJsonArray("entities")?.singleOrNull()?.asJsonObject?.get("place")?.asString }.getOrNull()
        val id = place?.takeIf { it.startsWith("places/") }?.removePrefix("places/")
        return if (BusStop.validPlaceId(id)) Result(placeId = id)
            else Result(issue = "Google could not identify a unique place from this link. Enter the full address.")
    }
}
