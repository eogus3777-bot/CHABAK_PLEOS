package com.chabak.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.abs

internal data class TravelPoint(val latitude: Double, val longitude: Double) {
    // Only used to correlate two SDK responses for the same endpoint.
    fun matches(other: TravelPoint): Boolean =
        abs(latitude - other.latitude) <= 0.0001 && abs(longitude - other.longitude) <= 0.0001

    fun toJson(): JSONObject = JSONObject().put("latitude", latitude).put("longitude", longitude)

    companion object {
        fun from(latitude: Double, longitude: Double): TravelPoint? =
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0 && (latitude != 0.0 || longitude != 0.0)) {
                TravelPoint(latitude, longitude)
            } else null

        fun read(json: JSONObject?): TravelPoint? =
            json?.let { from(it.optDouble("latitude"), it.optDouble("longitude")) }
    }
}

internal data class TripOrigin(val label: String, val point: TravelPoint? = null)

internal enum class TravelLeg(val label: String) {
    OUTBOUND("가는 길"),
    RETURN("귀가")
}

internal data class RestAreaKey(val code: String, val roadNumber: String, val direction: String) {
    fun toJson(): JSONObject = JSONObject().put("code", code).put("roadNumber", roadNumber).put("direction", direction)

    companion object {
        fun read(json: JSONObject?): RestAreaKey? {
            json ?: return null
            val code = json.stringField("code")
            val roadNumber = json.stringField("roadNumber")
            val direction = json.stringField("direction")
            if (code.isBlank() || roadNumber.isBlank() || direction.isBlank()) return null
            return RestAreaKey(code, roadNumber, direction)
        }
    }
}

internal data class RestAreaAccess(val key: RestAreaKey, val entranceLink: String, val exitLink: String)

internal data class DirectedRoute(val links: List<String>, val restAreaAccesses: List<RestAreaAccess>) {
    fun accessibleKeys(): List<RestAreaKey> = restAreaAccesses.mapNotNull { access ->
        val entry = links.indexOf(access.entranceLink)
        val exit = links.indexOf(access.exitLink)
        access.key.takeIf {
            access.entranceLink.isNotBlank() && access.exitLink.isNotBlank() && entry >= 0 && exit > entry
        }
    }.distinct()
}

internal data class RestAreaFood(val name: String, val priceWon: Int?)

internal data class RestArea(
    val key: RestAreaKey,
    val name: String,
    val roadName: String,
    val point: TravelPoint?,
    val foods: List<RestAreaFood>,
    val collectedAtMillis: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key.toJson())
        .put("name", name)
        .put("roadName", roadName)
        .put("point", point?.toJson())
        .put("foods", JSONArray(foods.map { JSONObject().put("name", it.name).put("priceWon", it.priceWon) }))
        .put("collectedAtMillis", collectedAtMillis)

    companion object {
        fun read(json: JSONObject?): RestArea? {
            json ?: return null
            val key = RestAreaKey.read(json.optJSONObject("key")) ?: return null
            val name = json.stringField("name")
            val roadName = json.stringField("roadName")
            if (name.isBlank() || roadName.isBlank()) return null
            val foods = json.optJSONArray("foods").objects().mapNotNull {
                val food = it.stringField("name")
                if (food.isBlank()) null else RestAreaFood(food, it.facilityCount("priceWon")?.takeIf { price -> price > 0 })
            }
            return RestArea(key, name, roadName, TravelPoint.read(json.optJSONObject("point")), foods, json.optLong("collectedAtMillis"))
        }
    }
}

internal data class SavedLeg(
    val direction: TravelLeg,
    val from: TravelPoint,
    val to: TravelPoint,
    val checkedAtMillis: Long,
    val remainingMeters: Int?,
    val remainingSeconds: Int?,
    val waypoints: List<String>?,
    val directedRoute: DirectedRoute? = null,
    val selectedArea: RestArea? = null
) {
    fun candidates(catalog: List<RestArea>): List<RestArea> {
        val byKey = catalog.groupBy { it.key }
        return directedRoute?.accessibleKeys().orEmpty().mapNotNull { byKey[it]?.singleOrNull() }
    }

    fun select(area: RestArea): SavedLeg? =
        if (area.key in directedRoute?.accessibleKeys().orEmpty()) copy(selectedArea = area) else null

    fun toJson(): JSONObject = JSONObject()
        .put("direction", direction.name)
        .put("from", from.toJson())
        .put("to", to.toJson())
        .put("checkedAtMillis", checkedAtMillis)
        .put("remainingMeters", remainingMeters)
        .put("remainingSeconds", remainingSeconds)
        .put("waypoints", waypoints?.let { JSONArray(it) })
        .put("links", directedRoute?.let { JSONArray(it.links) })
        .put("accesses", directedRoute?.let { evidence ->
            JSONArray(evidence.restAreaAccesses.map {
                JSONObject().put("key", it.key.toJson()).put("entrance", it.entranceLink).put("exit", it.exitLink)
            })
        })
        .put("selectedArea", selectedArea?.toJson())

    companion object {
        fun read(json: JSONObject?): SavedLeg? {
            json ?: return null
            val direction = TravelLeg.entries.firstOrNull { it.name == json.stringField("direction") } ?: return null
            val from = TravelPoint.read(json.optJSONObject("from")) ?: return null
            val to = TravelPoint.read(json.optJSONObject("to")) ?: return null
            val checkedAt = json.optLong("checkedAtMillis")
            if (checkedAt <= 0) return null
            val evidence = json.optJSONArray("links")?.let { links ->
                DirectedRoute(links.strings(), json.optJSONArray("accesses").objects().mapNotNull {
                    val key = RestAreaKey.read(it.optJSONObject("key")) ?: return@mapNotNull null
                    RestAreaAccess(key, it.stringField("entrance"), it.stringField("exit"))
                })
            }
            val result = SavedLeg(
                direction, from, to, checkedAt, json.facilityCount("remainingMeters"), json.facilityCount("remainingSeconds"),
                json.optJSONArray("waypoints")?.strings(), evidence
            )
            val selected = RestArea.read(json.optJSONObject("selectedArea"))
            return selected?.let(result::select) ?: result
        }
    }
}

internal data class TripTravel(
    val origin: TripOrigin? = null,
    val campsitePoint: TravelPoint? = null,
    val outbound: SavedLeg? = null,
    val homeward: SavedLeg? = null
) {
    fun withOrigin(origin: TripOrigin?): TripTravel =
        if (this.origin == origin) this else copy(origin = origin, outbound = null, homeward = null)

    fun withCampsitePoint(point: TravelPoint?): TripTravel =
        if (campsitePoint == point) this else copy(campsitePoint = point, outbound = null, homeward = null)

    fun afterCampsiteChange(): TripTravel = TripTravel(origin = origin)

    fun leg(direction: TravelLeg): SavedLeg? = if (direction == TravelLeg.OUTBOUND) outbound else homeward

    fun endpoints(direction: TravelLeg): Pair<TravelPoint, TravelPoint>? {
        val start = origin?.point ?: return null
        val end = campsitePoint ?: return null
        return if (direction == TravelLeg.OUTBOUND) start to end else end to start
    }

    fun withLeg(direction: TravelLeg, route: SavedLeg): TripTravel? {
        val (start, end) = endpoints(direction) ?: return null
        if (route.direction != direction || !start.matches(route.from) || !end.matches(route.to)) return null
        return if (direction == TravelLeg.OUTBOUND) copy(outbound = route) else copy(homeward = route)
    }

    fun selectArea(direction: TravelLeg, area: RestArea): TripTravel? =
        leg(direction)?.select(area)?.let { withLeg(direction, it) }

    fun toJson(): String = JSONObject()
        .put("origin", origin?.let { JSONObject().put("label", it.label).put("point", it.point?.toJson()) })
        .put("campsitePoint", campsitePoint?.toJson())
        .put("outbound", outbound?.toJson())
        .put("homeward", homeward?.toJson())
        .toString()

    companion object {
        fun read(serialized: String): TripTravel = try {
            val json = JSONObject(serialized)
            val origin = json.optJSONObject("origin")?.let {
                val label = it.stringField("label")
                if (label.isBlank()) null else TripOrigin(label, TravelPoint.read(it.optJSONObject("point")))
            }
            val base = TripTravel(origin, TravelPoint.read(json.optJSONObject("campsitePoint")))
            val withOutbound = SavedLeg.read(json.optJSONObject("outbound"))?.let { base.withLeg(TravelLeg.OUTBOUND, it) } ?: base
            SavedLeg.read(json.optJSONObject("homeward"))?.let { withOutbound.withLeg(TravelLeg.RETURN, it) } ?: withOutbound
        } catch (_: JSONException) {
            TripTravel()
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { opt(it) as? String }.filter(String::isNotBlank)
