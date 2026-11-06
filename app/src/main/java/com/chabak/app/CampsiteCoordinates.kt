package com.chabak.app

import java.net.HttpURLConnection
import java.net.URL

internal object CampsiteCoordinates {
    private val longitude = Regex("""\bvar\s+lng\s*=\s*"(-?\d+(?:\.\d+)?)"\s*;""")
    private val latitude = Regex("""\bvar\s+lat\s*=\s*"(-?\d+(?:\.\d+)?)"\s*;""")
    private val spaces = Regex("\\s+")

    fun forPlace(place: Place): TravelPoint? {
        place.permit?.let { return TravelPoint.from(it.latitude, it.longitude) }
        val url = place.goCampingDetailUrl() ?: return null
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            if (connection.responseCode != 200) return null
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseMapCoordinates(html, place)
        } finally {
            connection.disconnect()
        }
    }

    fun parseMapCoordinates(html: String, place: Place): TravelPoint? {
        if (place.name.isBlank() || place.address.isBlank() || "LatLng(lat, lng)" !in html) return null
        val compact = spaces.replace(html, "")
        if (!compact.contains(spaces.replace(place.name, "")) ||
            !compact.contains(spaces.replace(place.address.substringAfter(" "), ""))
        ) return null
        val lat = latitude.findAll(html).singleOrNull()?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val lon = longitude.findAll(html).singleOrNull()?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return TravelPoint.from(lat, lon)
    }
}
