package com.chabak.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object RestAreaCatalog {
    const val INFO_SOURCE = "https://www.data.go.kr/data/15076770/openapi.do"
    const val FOOD_SOURCE = "https://www.data.go.kr/data/15076643/openapi.do"

    fun load(keys: List<RestAreaKey>): List<RestArea> = keys.distinct().take(8).mapNotNull { key ->
        try {
            lookup(key)
        } catch (_: Exception) {
            null
        }
    }

    fun healthLabel(): String {
        val connection = URL("http://10.0.2.2:8086/health").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            if (connection.responseCode != 200) return "휴게소 서버 응답 확인 필요"
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (json.optString("source") != "한국도로공사") return "휴게소 출처 확인 필요"
            val info = if (json.optBoolean("infoConfigured")) "인증 설정됨" else "인증키 설정 필요"
            val food = if (json.optBoolean("foodConfigured")) "인증 설정됨" else "인증키 설정 필요"
            "도로공사 INFO $info · FOOD $food"
        } catch (_: Exception) {
            "휴게소 서버 연결 확인 필요"
        } finally {
            connection.disconnect()
        }
    }

    private fun lookup(key: RestAreaKey): RestArea? {
        val query = mapOf("code" to key.code, "roadNumber" to key.roadNumber, "direction" to key.direction)
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val connection = URL("http://10.0.2.2:8086/restareas?$query").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 12_000
            if (connection.responseCode != 200) return null
            val body = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (body.stringField("source") != "한국도로공사") return null
            RestArea.read(body.optJSONObject("area"))?.takeIf { it.key == key && it.collectedAtMillis > 0 }
        } finally {
            connection.disconnect()
        }
    }
}
