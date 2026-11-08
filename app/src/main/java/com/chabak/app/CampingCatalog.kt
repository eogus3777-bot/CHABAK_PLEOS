package com.chabak.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

internal const val MAX_CAMPING_RECOMMENDATIONS = 5
internal const val GOCAMPING_SOURCE = "한국관광공사 고캠핑"
internal val campingRegions = listOf("전국", "경기", "강원", "충청", "경상", "전라", "제주")
internal val campingEnvironments = listOf("숲", "산", "계곡", "바다", "강", "호수")

private val natureSeparator = Regex("[,/|·;\\s]+")
private val goCampingId = Regex("gocamping_[0-9]+")
private val campingPowerWords = Regex("전기(?!\\s*(?:자동차|차|충전))")
private val evChargingWords = Regex("충전\\s*(?:소|기)|(?:전기\\s*(?:자동차|차)|ev|급속|완속)\\s*충전", RegexOption.IGNORE_CASE)
private val shopLabels = listOf("마트", "편의점", "매점")

internal fun requestedCampingPower(query: String): Boolean = campingPowerWords.containsMatchIn(query)
internal fun requestedEvCharging(query: String): Boolean = evChargingWords.containsMatchIn(query)

internal fun Place.natureTags(): Set<String> = theme.split(natureSeparator).filter(String::isNotBlank).toSet()
internal fun Place.hasShop(): Boolean = shopLabels.any { it in services }
internal fun Place.hasToilet(): Boolean = toiletCount?.let { it > 0 } ?: (permit?.toiletAvailable == true)
internal fun registeredCampingPower(services: String): Boolean? =
    true.takeIf { services.split(',').any { it.trim() == "전기" } }

internal fun JSONObject.stringField(key: String): String = (opt(key) as? String)?.trim().orEmpty()
internal fun JSONObject.facilityCount(key: String): Int? = opt(key)?.toString()?.toIntOrNull()?.takeIf { it >= 0 }

internal fun usableWebUrl(value: String, requireHttps: Boolean = false): String? {
    val uri = try {
        URI(value)
    } catch (_: java.net.URISyntaxException) {
        return null
    }
    val supportedScheme = uri.scheme.equals("https", ignoreCase = true) ||
        (!requireHttps && uri.scheme.equals("http", ignoreCase = true))
    return value.takeIf { supportedScheme && !uri.host.isNullOrBlank() && uri.userInfo == null }
}

internal fun goCampingCollectionDay(timestamp: String): LocalDate? = try {
    OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate()
} catch (_: DateTimeParseException) {
    null
}

internal fun Place.goCampingDetailUrl(): String? = id.takeIf { live && goCampingId.matches(it) }?.let {
    "https://www.gocamping.or.kr/bsite/camp/info/read.do?c_no=${it.removePrefix("gocamping_")}&viewType=read01"
}

internal fun Place.bookingLink(): String? = usableWebUrl(reservationUrl)
internal fun Place.bookingMethodLabel(): String = if (bookingLink() != null) "등록 예약 링크 이용" else "확인 필요"

internal fun facilityAvailability(value: Boolean?, present: String = "있음", absent: String = "없음"): String =
    when (value) {
        true -> present
        false -> absent
        null -> "확인 필요"
    }

internal fun Place.toiletLabel(): String =
    toiletCount?.let { "${it}개 등록" } ?: facilityAvailability(permit?.toiletAvailable)

internal fun facilityCountLabel(count: Int?): String = count?.let { "${it}개 등록" } ?: "확인 필요"
internal fun Place.campingPowerLabel(): String = facilityAvailability(campingPower, "시설 등록 · 이용 조건 확인", "시설 없음")

internal data class CampingCriteria(
    val region: String,
    val district: String = "",
    val nature: String = "",
    val toilet: Boolean = false,
    val campingPower: Boolean = false,
    val store: Boolean = false,
    val quiet: Boolean = false,
    val evCharging: Boolean = false,
    val kind: CampingPlaceKind = CampingPlaceKind.CAMPGROUND,
    val shower: Boolean = false
) {
    fun matches(place: Place): Boolean {
        val location = "${place.name} ${place.address} ${place.detail}"
        val tags = place.natureTags()
        val natureMatches = nature.isBlank() || nature in tags || (nature == "바다" && "해변" in tags)
        return kind == place.kind && (region == "전국" || region == place.region) &&
            (district.isBlank() || location.contains(district, ignoreCase = true)) && natureMatches &&
            (!toilet || place.hasToilet()) && (!shower || (place.showerCount ?: 0) > 0) &&
            (!campingPower || place.campingPower == true) &&
            (!store || place.hasShop()) &&
            (!evCharging || place.onsiteEvCharger == true || place.nearbyEvCharger == true)
    }
}

internal data class SuggestedPlace(val place: Place, val reasons: List<String>)
internal data class CampingList(val places: List<Place>, val updatedAt: String)
internal data class CampingRecommendations(val items: List<SuggestedPlace>, val notices: List<String> = emptyList())

internal object GoCampingCatalog {
    // The Pleos emulator reaches the existing development proxy through the host.
    private const val BASE_URL = "http://10.0.2.2:8085"

    fun search(region: String, query: String): CampingList = decodeList(
        read("/camps", mapOf("region" to region, "q" to query, "limit" to "150"))
    )

    fun coordinatesFor(place: Place): TravelPoint? {
        if (!place.live || place.kind != CampingPlaceKind.CAMPGROUND || !goCampingId.matches(place.id)) return null
        val match = decodeList(read("/camps", mapOf("id" to place.id, "limit" to "1"))).places
        return match.singleOrNull { it.id == place.id }?.coordinates
    }

    fun recommend(criteria: CampingCriteria): CampingRecommendations {
        if (criteria.evCharging) return CampingRecommendations(
            emptyList(), listOf("고캠핑 자료에서 차량 충전소를 확인할 수 없어 충전 필수 조건의 후보를 표시할 수 없어요.")
        )
        val query = mapOf(
            "region" to criteria.region,
            "district" to criteria.district,
            "nature" to criteria.nature,
            "toilet" to flag(criteria.toilet),
            "shower" to flag(criteria.shower),
            "electric" to flag(criteria.campingPower),
            "store" to flag(criteria.store),
            "quiet" to flag(criteria.quiet),
            "limit" to MAX_CAMPING_RECOMMENDATIONS.toString()
        )
        return decodeRecommendations(read("/recommend", query), criteria)
    }

    fun decodeList(body: JSONObject): CampingList {
        val items = officialItems(body)
        val timestamp = body.stringField("updatedAt")
        return CampingList(
            (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let { item -> place(item, timestamp) } },
            timestamp
        )
    }

    fun decodeRecommendations(body: JSONObject, criteria: CampingCriteria): CampingRecommendations {
        val items = officialItems(body)
        val timestamp = body.stringField("updatedAt")
        val ranked = (0 until items.length()).asSequence().mapNotNull { index ->
            val entry = items.optJSONObject(index) ?: return@mapNotNull null
            val place = entry.optJSONObject("place")?.let { place(it, timestamp) } ?: return@mapNotNull null
            if (!criteria.matches(place)) return@mapNotNull null
            val reasons = entry.optJSONArray("reasons").strings().filterNot {
                place.campingPower != true && it == "전기 시설 등록"
            }
            SuggestedPlace(place, reasons)
        }.distinctBy { it.place.id }.take(MAX_CAMPING_RECOMMENDATIONS).toList()
        return CampingRecommendations(ranked, body.optJSONArray("notices").strings())
    }

    private fun officialItems(body: JSONObject): JSONArray {
        if (body.stringField("source") != GOCAMPING_SOURCE) throw IOException("Unexpected camping data source")
        return body.getJSONArray("items")
    }

    private fun place(item: JSONObject, timestamp: String): Place? {
        val id = item.stringField("id")
        val name = item.stringField("name")
        val address = item.stringField("address")
        if (!goCampingId.matches(id) || name.isBlank() || address.isBlank()) return null
        val theme = item.stringField("setting")
        return Place(
            id = id, name = name, region = item.stringField("region").ifBlank { "전국" },
            detail = item.stringField("district"), picture = storedCampsitePicture(id, theme),
            theme = theme, services = item.stringField("facilities"), address = address,
            phone = item.stringField("phone"), toiletCount = item.facilityCount("toilets"),
            showerCount = item.facilityCount("showers"), sinkCount = item.facilityCount("sinks"),
            website = item.stringField("website"), reservationUrl = item.stringField("reservationUrl"),
            siteType = item.stringField("type"), businessStatus = item.stringField("status"), live = true,
            sourceUpdatedAt = timestamp,
            coordinates = CampsiteCoordinates.from(item.optDouble("mapY"), item.optDouble("mapX"))
        )
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }.filter(String::isNotBlank)

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun read(path: String, parameters: Map<String, String>): JSONObject {
        val query = parameters.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val connection = URL("$BASE_URL$path?$query").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 4500
            connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Camping server HTTP $status")
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
