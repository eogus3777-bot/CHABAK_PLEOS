package com.chabak.app

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class ChabakWeatherInstrumentedTest {
    @Test
    fun retainsRealZeroAndRejectsMissingFieldsAndUnknownCodes() {
        fun body(temp: String, code: String, time: String = "2026-11-07T11:30") =
            JSONObject("""{"current":{"temperature_2m":$temp,"weather_code":$code,"time":"$time"}}""")

        val weather = requireNotNull(ChabakWeather.decode(body("0", "0"), "경기"))
        assertEquals(0, weather.temperature)
        assertTrue(weather.matches("경기", LocalDateTime.parse("2026-11-07T11:45")))
        assertFalse(weather.matches("경기", LocalDateTime.parse("2026-11-07T13:01")))
        assertFalse(weather.matches("강원", LocalDateTime.parse("2026-11-07T11:45")))
        assertNull(ChabakWeather.decode(body("null", "0"), "경기"))
        assertNull(ChabakWeather.decode(body("1", "null"), "경기"))
        assertNull(ChabakWeather.decode(body("1", "100"), "경기"))
        assertNull(ChabakWeather.decode(body("1", "0", "invalid"), "경기"))
    }

    @Test
    fun sendsOnlyOfficialCampsiteCoordinatesToWeatherProvider() {
        val point = TravelPoint(37.2, 127.3)
        val sample = Place("gocamping_12", "테스트", "경기", "", R.drawable.chabak_day_photo, "", "",
            coordinates = point)
        assertEquals(WeatherTarget.forRegion("경기"), WeatherTarget.forPlace(sample))
        assertEquals(WeatherTarget("경기", point.latitude, point.longitude, campsite = true),
            WeatherTarget.forPlace(sample.copy(live = true)))
    }

    @Test
    fun permitsCleartextOnlyForEmulatorHost() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("10.0.2.2"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
        assertFalse(policy.isCleartextTrafficPermitted("10.0.2.20"))
    }
}
