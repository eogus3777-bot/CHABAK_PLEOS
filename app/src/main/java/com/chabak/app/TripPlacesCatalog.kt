package com.chabak.app

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

internal enum class TripPlaceCategory(val label: String) {
    REST_AREA("휴게소"), BREWERY("양조장"), MARKET("오일장")
}

internal data class NearbyQuery(
    val category: TripPlaceCategory,
    val window: VisitWindow,
    val strictDates: Boolean = false
)

internal data class TripPlace(
    val id: String,
    val category: TripPlaceCategory,
    val name: String,
    val address: String,
    val point: TravelPoint?,
    val source: String,
    val sourceUrl: String,
    val sourceDate: String,
    val marketRule: String = "",
    val marketDays: Set<Int> = emptySet(),
    val program: String = "",
    val hours: String = "",
    val reservation: String = "",
    val locationSourceUrl: String = ""
)

internal data class NearbyMatch(
    val place: TripPlace,
    val kilometers: Double,
    val nextMarketDate: LocalDate? = null,
    val matchesDates: Boolean = false
)

internal enum class NearbyStatus {
    FOUND, NO_RESULTS, NO_COORDINATES, UNVERIFIED_ROUTE, SERVICE_FAILURE, SUPERSEDED
}

internal data class NearbyResult(
    val tripId: String,
    val query: NearbyQuery,
    val status: NearbyStatus,
    val message: String,
    val items: List<NearbyMatch> = emptyList(),
    val collectedAt: String = "",
    val coverage: String = ""
)

internal class TripPlacesCatalog(private val context: Context) {
    fun search(trip: Trip, query: NearbyQuery): NearbyResult {
        val point = CampsiteCoordinates.forPlace(trip.place)?.let {
            CampsiteCoordinates.from(it.latitude, it.longitude)
        } ?: return NearbyResult(trip.tripId, query, NearbyStatus.NO_COORDINATES,
            "선택한 캠핑장의 공식 좌표를 먼저 확인해 주세요.")
        if (query.category == TripPlaceCategory.REST_AREA) return restAreas(trip, query, point)
        val root = JSONObject(context.assets.open("trip_places.json").bufferedReader().use { it.readText() })
        require(root.getInt("schemaVersion") == 1)
        val rows = root.getJSONArray("places")
        val places = (0 until rows.length()).map { decode(rows.getJSONObject(it)) }
        val matches = rank(places, point, query)
        val message = when {
            matches.isEmpty() -> "50 km 안에서 좌표와 조건이 확인된 ${query.category.label} 후보가 없어요."
            query.category == TripPlaceCategory.MARKET -> "등록 장날 기준 후보 · 실제 휴장·변경 여부는 방문 전 확인하세요."
            else -> "공식 방문지 좌표로 거리 확인 · 운영 시간과 체험 예약은 방문 전 확인하세요."
        }
        return NearbyResult(trip.tripId, query,
            if (matches.isEmpty()) NearbyStatus.NO_RESULTS else NearbyStatus.FOUND,
            message, matches, root.getString("collectedAt"), root.getString("coverage"))
    }

    private fun restAreas(trip: Trip, query: NearbyQuery, point: TravelPoint): NearbyResult {
        val route = trip.travel.outbound?.takeIf {
            System.currentTimeMillis() - it.checkedAtMillis in 0L..900_000L && it.to.matches(point)
        }
        val keys = route?.directedRoute?.accessibleKeys().orEmpty()
        if (keys.isEmpty()) return NearbyResult(trip.tripId, query, NearbyStatus.UNVERIFIED_ROUTE,
            "경로상 확인 필요 · 현재 사용할 수 있는 방향별 진입·진출 경로 자료가 없어요.\n" + RestAreaCatalog.healthLabel())
        val areas = route?.candidates(RestAreaCatalog.load(keys)).orEmpty()
        val matches = areas.mapNotNull { area ->
            val target = area.point?.let { CampsiteCoordinates.from(it.latitude, it.longitude) } ?: return@mapNotNull null
            NearbyMatch(TripPlace(
                "rest_${area.key.code}_${area.key.direction}", TripPlaceCategory.REST_AREA,
                area.name, "${area.roadName} · ${area.key.direction}", target, "한국도로공사",
                RestAreaCatalog.INFO_SOURCE, Instant.ofEpochMilli(area.collectedAtMillis).toString(),
                program = if (area.foods.isEmpty()) "대표 음식 확인 필요"
                    else area.foods.joinToString(" · ") { it.name }
            ), distanceKilometers(point, target))
        }
        return NearbyResult(trip.tripId, query,
            if (matches.isEmpty()) NearbyStatus.SERVICE_FAILURE else NearbyStatus.FOUND,
            "최근 확인 경로 자료 · 현재 방향과 진입 가능 여부는 이동 정보에서 재확인하세요.", matches)
    }

    companion object {
        fun decode(json: JSONObject): TripPlace {
            val days = json.optJSONArray("marketDays")
            val category = TripPlaceCategory.valueOf(json.getString("category"))
            return TripPlace(
                json.getString("id"), category, json.getString("name"), json.getString("address"),
                CampsiteCoordinates.from(json.optDouble("latitude"), json.optDouble("longitude")),
                json.getString("source"), json.getString("sourceUrl"), json.getString("sourceDate"),
                json.optString("marketRule"), (0 until (days?.length() ?: 0)).map { days!!.getInt(it) }.toSet(),
                json.optString("program"), json.optString("hours"), json.optString("reservation"),
                json.optString("locationSourceUrl")
            )
        }

        fun rank(places: List<TripPlace>, point: TravelPoint, query: NearbyQuery): List<NearbyMatch> =
            places.asSequence().filter { it.category == query.category }.mapNotNull { place ->
                val target = place.point?.let { CampsiteCoordinates.from(it.latitude, it.longitude) } ?: return@mapNotNull null
                val distance = distanceKilometers(point, target)
                if (distance > 50.0) return@mapNotNull null
                val dateMatch = MarketCalendar.matches(place.marketDays, query.window)
                if (query.strictDates && query.category == TripPlaceCategory.MARKET && !dateMatch) return@mapNotNull null
                NearbyMatch(place, distance, MarketCalendar.nextDate(place.marketDays, query.window.start), dateMatch)
            }.sortedWith(compareByDescending<NearbyMatch> { it.matchesDates }.thenBy { it.kilometers })
                .take(5).toList()
    }
}
