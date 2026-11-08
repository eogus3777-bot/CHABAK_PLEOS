package com.chabak.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

internal data class WeatherTarget(
    val region: String, val latitude: Double, val longitude: Double, val campsite: Boolean = false
) {
    companion object {
        fun forPlace(place: Place): WeatherTarget = place.coordinates
            ?.takeIf { place.live && place.kind == CampingPlaceKind.CAMPGROUND }
            ?.let { WeatherTarget(place.region, it.latitude, it.longitude, campsite = true) }
            ?: forRegion(place.region)

        fun forRegion(region: String): WeatherTarget {
            val (latitude, longitude) = when (region) {
                "강원" -> 37.8813 to 127.7298
                "충청" -> 36.6424 to 127.4890
                "경상" -> 35.8714 to 128.6014
                "전라" -> 35.8242 to 127.1480
                "제주" -> 33.4996 to 126.5312
                "경기" -> 37.4138 to 127.5183
                else -> 37.5665 to 126.9780
            }
            return WeatherTarget(region, latitude, longitude)
        }
    }

    val label: String get() = when {
        campsite -> "캠핑장 인근"
        region in setOf("강원", "충청", "경상", "전라", "제주", "경기") -> "$region 대표지점"
        else -> "서울 대표지점"
    }
}

internal data class WeatherReading(
    val target: WeatherTarget, val weather: AmbienceWeather, val fetchedAt: LocalDateTime
)

internal object ChabakWeather {
    fun read(target: WeatherTarget, now: () -> LocalDateTime): WeatherReading? {
        val endpoint = "https://api.open-meteo.com/v1/forecast?latitude=${target.latitude}" +
            "&longitude=${target.longitude}&current=temperature_2m,weather_code&timezone=Asia%2FSeoul"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 4_500
            connection.readTimeout = 4_500
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val fetchedAt = now()
            decode(json, target.region)?.let { WeatherReading(target, it, fetchedAt) }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun decode(json: JSONObject, region: String): AmbienceWeather? {
        val current = json.optJSONObject("current") ?: return null
        val temp = current.optDouble("temperature_2m").takeIf(Double::isFinite) ?: return null
        val time = try {
            LocalDateTime.parse(current.optString("time"))
        } catch (_: DateTimeParseException) {
            return null
        }
        val label = when (current.optInt("weather_code", -1)) {
            0 -> "맑음"
            1, 2 -> "구름 조금"
            3 -> "흐림"
            45, 48 -> "안개"
            51, 53, 55, 56, 57 -> "이슬비"
            61, 63, 65, 66, 67, 80, 81, 82 -> "비"
            71, 73, 75, 77, 85, 86 -> "눈"
            95, 96, 99 -> "뇌우"
            else -> return null
        }
        return AmbienceWeather(region, time, temp.toInt(), label)
    }
}
