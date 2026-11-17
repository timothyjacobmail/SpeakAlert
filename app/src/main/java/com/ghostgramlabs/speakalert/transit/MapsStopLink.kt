package com.ghostgramlabs.speakalert.transit

import java.net.URI
import java.net.URLDecoder

/** Reads explicit place data only. A map camera position is not a boarding stop. */
object MapsStopLink {
    data class Draft(val name: String = "", val latitude: Double? = null, val longitude: Double? = null, val address: String = "") {
        val hasCoordinates: Boolean get() = latitude != null && longitude != null
    }
    private val number = "[-+]?(?:[0-9]+(?:\\.[0-9]+)?)"
    private val pin = Regex("!3d($number)!4d($number)")
    private val coordinateQuery = Regex("^(?:loc:)?($number),\\s*($number)(?:\\s*\\((.+)\\))?$")
    private val hosts = setOf("google.com", "www.google.com", "maps.google.com",
        "google.ae", "www.google.ae", "maps.google.ae",
        "google.co.uk", "www.google.co.uk", "google.co.in", "www.google.co.in")

    fun link(text: String): URI {
        require(text.length <= 10_000) { "The link is too long." }
        val raw = Regex("https://[^\\s<>\\\"]+", RegexOption.IGNORE_CASE).find(text)?.value
            ?.trimEnd('.', ',', ';') ?: throw IllegalArgumentException("Paste a Google Maps HTTPS link.")
        return checked(runCatching { URI(raw) }.getOrElse {
            throw IllegalArgumentException("This link could not be read. Copy it again from Google Maps.")
        })
    }

    private fun checked(uri: URI): URI {
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        require(uri.scheme.equals("https", true) && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            ((host in hosts && (path == "/maps" || path.startsWith("/maps/") || host.startsWith("maps."))) ||
                (host == "maps.app.goo.gl" && path.length > 1) || (host == "goo.gl" && path.startsWith("/maps/")))) {
            "Use a Google Maps place or stop link."
        }
        require(!path.startsWith("/maps/dir")) { "This is a directions link. Share the bus stop's place instead." }
        return uri
    }

    fun isShort(uri: URI) = uri.host?.lowercase() in setOf("maps.app.goo.gl", "goo.gl")

    /** Redirects are validated before the caller makes another request. Full links need no network. */
    fun resolve(text: String, redirect: (URI) -> String?): Draft {
        var uri = link(text)
        if (isShort(uri)) {
            var hops = 0
            val visited = mutableSetOf<URI>()
            while (isShort(uri) && hops++ < 5) {
                require(visited.add(uri)) { "The short link could not be expanded. Enter the stop details below." }
                val next = redirect(uri)
                    ?: throw IllegalArgumentException("The short link could not be expanded. Enter the stop details below.")
                uri = checked(uri.resolve(next))
            }
            require(!isShort(uri)) { "The short link has too many redirects. Enter the stop details below." }
        }
        return parse(uri).let { draft ->
            // Google Maps may share a place title on a separate line before the link.
            val title = text.lineSequence().takeWhile { !it.contains("https://", true) }
                .joinToString(" ").trim().take(180)
            if (draft.name.isBlank() && title.isNotBlank()) draft.copy(name = title) else draft
        }
    }

    fun fillFromCatalogue(draft: Draft, stops: List<BusStop>): Draft {
        if (draft.hasCoordinates || draft.name.isBlank() || draft.address.isNotBlank()) return draft
        val match = stops.filter { it.latitude != null && it.longitude != null &&
            it.name.trim().equals(draft.name.trim(), true) }.distinctBy { it.id }.singleOrNull() ?: return draft
        return draft.copy(latitude = match.latitude, longitude = match.longitude,
            address = draft.address.ifBlank { match.address })
    }

    fun parse(uri: URI): Draft {
        checked(uri)
        fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) decode(parts[0]) to decode(parts[1]) else null
        }.toMap()
        val q = query["query"] ?: query["q"].orEmpty()
        val qCoordinates = coordinateQuery.matchEntire(q)
        val placeName = decode(uri.rawPath.orEmpty().substringAfter("/maps/place/", "").substringBefore('/')).trim()
            .takeUnless { it.startsWith("0x") || coordinateQuery.matches(it) }.orEmpty()
        val label = (placeName.ifBlank {
            if (qCoordinates != null) qCoordinates.groupValues[3]
            else q.takeUnless { it.startsWith("place_id:") || it.startsWith("loc:") }.orEmpty()
        })
        val address = label.takeIf { it.contains(',') }.orEmpty().take(300)
        val name = label.substringBefore(',').trim().take(180)
        val pinMatch = pin.findAll(decode(uri.toASCIIString())).lastOrNull()
        val lat = (pinMatch?.groupValues?.get(1) ?: qCoordinates?.groupValues?.get(1))?.toDoubleOrNull()
        val lon = (pinMatch?.groupValues?.get(2) ?: qCoordinates?.groupValues?.get(2))?.toDoubleOrNull()
        val valid = lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0
        return Draft(name, lat.takeIf { valid }, lon.takeIf { valid }, address)
    }
}
