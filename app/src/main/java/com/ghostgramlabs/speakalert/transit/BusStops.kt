package com.ghostgramlabs.speakalert.transit

import android.content.Context
import com.google.gson.JsonParser

object BusStops {
    @Volatile private var cache: List<BusStop>? = null
    fun load(context: Context): List<BusStop> = cache ?: synchronized(this) {
        cache ?: run {
            val entries = context.assets.open("transit/abu-dhabi-stops.json").bufferedReader().use {
                JsonParser.parseReader(it).asJsonArray
            }
            (listOf(BusAlert.defaultStop) + entries.mapNotNull { raw ->
                runCatching {
                    val o = raw.asJsonObject
                    val name = o.get("NAME").asString
                    BusStop(o.get("CODE").asString, name, "$name, Abu Dhabi Emirate, UAE",
                        o.get("LATITUDE").asDouble, o.get("LONGITUDE").asDouble,
                        o.get("NAME_AR")?.takeUnless { it.isJsonNull }?.asString.orEmpty()).takeIf { it.valid() }
                }.getOrNull()
            }).distinctBy { it.id }.sortedBy { it.name }.also { cache = it }
        }
    }
    fun search(stops: List<BusStop>, query: String): List<BusStop> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return stops.filter { stop -> words.all { word ->
            stop.name.contains(word, true) || stop.id.contains(word, true) || stop.arabicName.contains(word, true)
        } }.take(60)
    }
}
