package com.chabak.app

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

internal enum class CampingPlaceKind(val label: String) {
    CAMPGROUND("일반 캠핑장"),
    PERMITTED_UNDEVELOPED("허용된 노지 차박지"),
    PUBLIC_FREE("공공·무료 차박 시설");

    companion object {
        fun fromKey(key: String): CampingPlaceKind? = entries.firstOrNull { it.name == key }
    }
}

// A record is added only after checking the manager's published terms for this location.
internal data class CampingPermit(
    val latitude: Double,
    val longitude: Double,
    val manager: String,
    val verifiedOn: LocalDate,
    val allowedFrom: LocalDate,
    val allowedThrough: LocalDate,
    val sourceUrl: String,
    val vehicleAccessAllowed: Boolean?,
    val accessRoad: String,
    val nightParkingAllowed: Boolean?,
    val toiletAvailable: Boolean?,
    val cookingAllowed: Boolean?,
    val openFlameAllowed: Boolean?
) {
    fun covers(today: LocalDate, visitDate: LocalDate, nights: Int): Boolean =
        hasEvidence() && vehicleAccessAllowed == true && nightParkingAllowed == true &&
            verifiedOn <= today && today <= allowedThrough && visitDate >= maxOf(today, allowedFrom) &&
            nights in 1..7 && ChronoUnit.DAYS.between(visitDate, allowedThrough) >= nights

    fun toJson(): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("manager", manager)
        .put("verifiedOn", verifiedOn.toString())
        .put("allowedFrom", allowedFrom.toString())
        .put("allowedThrough", allowedThrough.toString())
        .put("sourceUrl", sourceUrl)
        .put("vehicleAccessAllowed", vehicleAccessAllowed ?: JSONObject.NULL)
        .put("accessRoad", accessRoad)
        .put("nightParkingAllowed", nightParkingAllowed ?: JSONObject.NULL)
        .put("toiletAvailable", toiletAvailable ?: JSONObject.NULL)
        .put("cookingAllowed", cookingAllowed ?: JSONObject.NULL)
        .put("openFlameAllowed", openFlameAllowed ?: JSONObject.NULL)

    private fun hasEvidence(): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            manager.isNotBlank() && usableWebUrl(sourceUrl, requireHttps = true) != null &&
            allowedFrom <= allowedThrough

    companion object {
        fun read(json: JSONObject): CampingPermit? {
            val permit = try {
                CampingPermit(
                    latitude = json.optDouble("latitude"),
                    longitude = json.optDouble("longitude"),
                    manager = json.stringField("manager"),
                    verifiedOn = LocalDate.parse(json.stringField("verifiedOn")),
                    allowedFrom = LocalDate.parse(json.stringField("allowedFrom")),
                    allowedThrough = LocalDate.parse(json.stringField("allowedThrough")),
                    sourceUrl = json.stringField("sourceUrl"),
                    vehicleAccessAllowed = json.opt("vehicleAccessAllowed") as? Boolean,
                    accessRoad = json.stringField("accessRoad"),
                    nightParkingAllowed = json.opt("nightParkingAllowed") as? Boolean,
                    toiletAvailable = json.opt("toiletAvailable") as? Boolean,
                    cookingAllowed = json.opt("cookingAllowed") as? Boolean,
                    openFlameAllowed = json.opt("openFlameAllowed") as? Boolean
                )
            } catch (_: DateTimeParseException) {
                return null
            }
            return permit.takeIf { it.hasEvidence() }
        }
    }
}

internal fun Place.isVerifiedStay(today: LocalDate, visitDate: LocalDate, nights: Int): Boolean =
    kind != CampingPlaceKind.CAMPGROUND && overnightAllowed == true &&
        permit?.covers(today, visitDate, nights) == true

internal object ReviewedCampingSites {
    private val reviewedId = Regex("reviewed_[A-Za-z0-9_-]+")

    fun load(context: Context): List<Place> = try {
        val content = context.assets.open("reviewed_camping_sites.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        decode(JSONObject(content))
    } catch (_: IOException) {
        emptyList()
    } catch (_: JSONException) {
        emptyList()
    }

    fun decode(root: JSONObject): List<Place> {
        if (root.optInt("schemaVersion") != 1) return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::readPlace) }
            .groupBy { it.id }.values.mapNotNull { it.singleOrNull() }
    }

    fun usablePlace(place: Place, records: List<Place>, today: LocalDate, visitDate: LocalDate, nights: Int): Place? {
        if (place.kind == CampingPlaceKind.CAMPGROUND) return place
        return records.firstOrNull { it.id == place.id && it.kind == place.kind && it.isVerifiedStay(today, visitDate, nights) }
    }

    private fun readPlace(item: JSONObject): Place? {
        val kind = CampingPlaceKind.fromKey(item.stringField("kind")) ?: return null
        if (kind == CampingPlaceKind.CAMPGROUND) return null
        val id = item.stringField("id")
        val name = item.stringField("name")
        val address = item.stringField("address")
        val region = item.stringField("region")
        if (!reviewedId.matches(id) || name.isBlank() || address.isBlank() || region.isBlank()) return null
        val permit = item.optJSONObject("permit")?.let(CampingPermit::read) ?: return null
        val theme = item.stringField("setting")
        return Place(
            id = id, name = name, region = region, detail = item.stringField("district"),
            picture = storedCampsitePicture(id, theme), theme = theme, services = item.stringField("facilities"),
            address = address, phone = item.stringField("phone"), toiletCount = item.facilityCount("toilets"),
            showerCount = item.facilityCount("showers"), sinkCount = item.facilityCount("sinks"),
            reservationUrl = item.stringField("reservationUrl"),
            campingPower = item.opt("campingPower") as? Boolean,
            onsiteEvCharger = item.opt("onsiteEvCharger") as? Boolean,
            nearbyEvCharger = item.opt("nearbyEvCharger") as? Boolean,
            overnightAllowed = item.opt("overnightAllowed") as? Boolean,
            kind = kind, permit = permit
        )
    }
}
