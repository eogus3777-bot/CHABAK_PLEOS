package com.chabak.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

internal data class Trip(
    val tripId: String,
    val place: Place,
    val visitDate: LocalDate,
    val people: Int,
    val nights: Int,
    val packedItemIds: Set<String> = emptySet(),
    val extraItems: List<String> = emptyList(),
    val travel: TripTravel = TripTravel(),
    val energyPlan: TripEnergyPlan = TripEnergyPlan(),
    val wakeMinuteOfDay: Int? = null,
    val diary: TripDiary = TripDiary()
) {
    fun withPlace(place: Place): Trip = copy(
        place = place,
        packedItemIds = if (this.place.id == place.id) packedItemIds
        else packedItemIds.filterNot { it.startsWith("site_") }.toSet(),
        travel = if (this.place.id == place.id && this.place.address == place.address &&
            this.place.permit?.latitude == place.permit?.latitude && this.place.permit?.longitude == place.permit?.longitude
        ) travel else travel.afterCampsiteChange()
    )
}

internal data class TripCatalog(val trips: List<Trip>, val activeTripId: String) {
    val activeTrip: Trip get() = trips.first { it.tripId == activeTripId }
}

internal class TripStore(context: Context, databaseName: String = "chabak_trips.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, 5) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE trips (
                trip_id TEXT PRIMARY KEY NOT NULL,
                campsite TEXT NOT NULL,
                visit_date TEXT NOT NULL,
                people INTEGER NOT NULL CHECK (people BETWEEN 1 AND 8),
                nights INTEGER NOT NULL CHECK (nights BETWEEN 1 AND 7),
                packed TEXT NOT NULL,
                extra_items TEXT NOT NULL,
                travel TEXT NOT NULL DEFAULT '{}',
                energy_hours INTEGER NOT NULL DEFAULT 8 CHECK (energy_hours IN (6, 8, 10)),
                energy_minimum INTEGER NOT NULL DEFAULT 30 CHECK (energy_minimum BETWEEN 1 AND 100),
                energy_power_kw REAL CHECK (energy_power_kw BETWEEN 0 AND 50),
                wake_minute_of_day INTEGER CHECK (wake_minute_of_day BETWEEN 0 AND 1439),
                diary TEXT NOT NULL DEFAULT '{}'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE trip_selection (
                slot INTEGER PRIMARY KEY CHECK (slot = 1),
                trip_id TEXT NOT NULL REFERENCES trips(trip_id)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE trips ADD COLUMN travel TEXT NOT NULL DEFAULT '{}'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE trips ADD COLUMN energy_hours INTEGER NOT NULL DEFAULT 8 CHECK (energy_hours IN (6, 8, 10))")
            db.execSQL("ALTER TABLE trips ADD COLUMN energy_minimum INTEGER NOT NULL DEFAULT 30 CHECK (energy_minimum BETWEEN 1 AND 100)")
            db.execSQL("ALTER TABLE trips ADD COLUMN energy_power_kw REAL CHECK (energy_power_kw BETWEEN 0 AND 50)")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE trips ADD COLUMN wake_minute_of_day INTEGER CHECK (wake_minute_of_day BETWEEN 0 AND 1439)")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE trips ADD COLUMN diary TEXT NOT NULL DEFAULT '{}'")
        }
    }

    fun loadOrMigrate(legacyTrip: () -> Trip): TripCatalog = transaction {
        val isEmpty = rawQuery("SELECT 1 FROM trips LIMIT 1", null).use { !it.moveToFirst() }
        if (isEmpty) {
            val first = legacyTrip()
            insertOrThrow("trips", null, first.toValues())
            setActiveTrip(first.tripId)
        }
        readCatalog()
    }

    fun createTrip(fromTripId: String, startingPlace: Place? = null): TripCatalog = transaction {
        val current = readTrip(fromTripId)
        val newTrip = current.copy(
            tripId = UUID.randomUUID().toString(),
            place = startingPlace ?: current.place,
            packedItemIds = emptySet(),
            extraItems = emptyList(),
            travel = TripTravel(),
            energyPlan = TripEnergyPlan(),
            wakeMinuteOfDay = null,
            diary = TripDiary()
        )
        insertOrThrow("trips", null, newTrip.toValues())
        setActiveTrip(newTrip.tripId)
        readCatalog()
    }

    fun openTrip(tripId: String): TripCatalog = transaction {
        setActiveTrip(tripId)
        readCatalog()
    }

    fun updateTrip(tripId: String, change: (Trip) -> Trip): TripCatalog = transaction {
        val updated = change(readTrip(tripId))
        require(updated.tripId == tripId)
        update("trips", updated.toValues(), "trip_id = ?", arrayOf(tripId))
        readCatalog()
    }

    private fun <T> transaction(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = db.block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun SQLiteDatabase.setActiveTrip(tripId: String) {
        execSQL("INSERT OR REPLACE INTO trip_selection (slot, trip_id) VALUES (1, ?)", arrayOf(tripId))
    }

    private fun SQLiteDatabase.readTrip(tripId: String): Trip =
        query("trips", null, "trip_id = ?", arrayOf(tripId), null, null, null).use {
            if (!it.moveToFirst()) throw SQLiteException("Saved trip was not found")
            it.toTrip()
        }

    private fun SQLiteDatabase.readCatalog(): TripCatalog {
        val trips = query("trips", null, null, null, null, null, "rowid DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toTrip()) }
        }
        val activeId = rawQuery("SELECT trip_id FROM trip_selection WHERE slot = 1", null).use {
            if (!it.moveToFirst()) throw SQLiteException("Active trip was not found")
            it.getString(0)
        }
        return TripCatalog(trips, activeId)
    }
}

internal fun readLegacyTrip(
    values: Map<String, *>,
    referencePlaces: List<Place>,
    defaultDate: LocalDate
): Trip {
    val savedPlace = (values["selectedPlaceInfo"] as? String)?.let { raw ->
        try {
            JSONObject(raw).toPlace()
        } catch (_: JSONException) {
            null
        }
    }
    val pickedId = values["place"] as? String
    val place = savedPlace?.takeIf { pickedId == null || it.id == pickedId }
        ?: referencePlaces.firstOrNull { it.id == pickedId }
        ?: referencePlaces.first()
    val visitDate = (values["date"] as? String)?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    } ?: defaultDate

    val previousChecks = values["packed_$visitDate"] ?: values["packed"].takeUnless {
        values.keys.any { key -> key.startsWith("packed_") }
    }
    return Trip(
        tripId = UUID.randomUUID().toString(),
        place = place,
        visitDate = visitDate,
        people = (values["people"] as? Int ?: 2).coerceIn(1, 8),
        nights = (values["nights"] as? Int ?: 1).coerceIn(1, 7),
        packedItemIds = (previousChecks as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
        extraItems = (values["extra"] as? String)?.split('|')?.filter { it.isNotBlank() }.orEmpty()
    )
}

private fun Trip.toValues() = ContentValues().apply {
    put("trip_id", tripId)
    put("campsite", place.toJson().toString())
    put("visit_date", visitDate.toString())
    put("people", people)
    put("nights", nights)
    put("packed", JSONArray(packedItemIds.toList()).toString())
    put("extra_items", JSONArray(extraItems).toString())
    put("travel", travel.toJson())
    put("energy_hours", energyPlan.overnightHours)
    put("energy_minimum", energyPlan.minimumPercent)
    put("energy_power_kw", energyPlan.estimatedPowerKw)
    put("wake_minute_of_day", wakeMinuteOfDay)
    put("diary", diary.toJson())
}

private fun Cursor.toTrip(): Trip {
    fun text(column: String) = getString(getColumnIndexOrThrow(column))
    fun strings(column: String): List<String> = JSONArray(text(column)).let { array ->
        List(array.length()) { array.getString(it) }
    }
    return Trip(
        tripId = text("trip_id"),
        place = JSONObject(text("campsite")).toPlace(),
        visitDate = LocalDate.parse(text("visit_date")),
        people = getInt(getColumnIndexOrThrow("people")),
        nights = getInt(getColumnIndexOrThrow("nights")),
        packedItemIds = strings("packed").toSet(),
        extraItems = strings("extra_items"),
        travel = TripTravel.read(text("travel")),
        energyPlan = TripEnergyPlan(
            overnightHours = getInt(getColumnIndexOrThrow("energy_hours")),
            minimumPercent = getInt(getColumnIndexOrThrow("energy_minimum")),
            estimatedPowerKw = getColumnIndexOrThrow("energy_power_kw").let { if (isNull(it)) null else getDouble(it) }
        ),
        wakeMinuteOfDay = getColumnIndexOrThrow("wake_minute_of_day").let { if (isNull(it)) null else getInt(it) },
        diary = TripDiary.read(text("diary"))
    )
}

private fun Place.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("region", region)
    .put("district", detail)
    .put("setting", theme)
    .put("facilities", services)
    .put("address", address)
    .put("phone", phone)
    .put("toilets", toiletCount ?: JSONObject.NULL)
    .put("showers", showerCount ?: JSONObject.NULL)
    .put("sinks", sinkCount ?: JSONObject.NULL)
    .put("website", website)
    .put("reservationUrl", reservationUrl)
    .put("type", siteType)
    .put("status", businessStatus)
    .put("live", live)
    .put("campingPower", campingPower ?: JSONObject.NULL)
    .put("onsiteEvCharger", onsiteEvCharger ?: JSONObject.NULL)
    .put("nearbyEvCharger", nearbyEvCharger ?: JSONObject.NULL)
    .put("overnightAllowed", overnightAllowed ?: JSONObject.NULL)
    .put("kind", kind.name)
    .put("permit", permit?.toJson() ?: JSONObject.NULL)
    .put("sourceUpdatedAt", sourceUpdatedAt)
    .put("coordinates", coordinates?.toJson() ?: JSONObject.NULL)

private fun JSONObject.toPlace(): Place {
    val id = optString("id")
    val name = optString("name")
    if (id.isBlank() || name.isBlank()) throw JSONException("Missing saved campsite identity")
    val theme = optString("setting")
    val services = optString("facilities")
    val kind = if (has("kind")) {
        CampingPlaceKind.fromKey(optString("kind")) ?: throw JSONException("Unknown campsite type")
    } else CampingPlaceKind.CAMPGROUND
    return Place(
        id = id,
        name = name,
        region = optString("region", "전국"),
        detail = optString("district"),
        picture = storedCampsitePicture(id, theme),
        theme = theme,
        services = services,
        address = optString("address"),
        phone = optString("phone"),
        toiletCount = facilityCount("toilets"),
        showerCount = facilityCount("showers"),
        sinkCount = facilityCount("sinks"),
        website = optString("website"),
        reservationUrl = optString("reservationUrl"),
        siteType = optString("type"),
        businessStatus = optString("status"),
        live = optBoolean("live"),
        campingPower = if (has("campingPower")) opt("campingPower") as? Boolean else registeredCampingPower(services),
        onsiteEvCharger = opt("onsiteEvCharger") as? Boolean,
        nearbyEvCharger = opt("nearbyEvCharger") as? Boolean,
        overnightAllowed = opt("overnightAllowed") as? Boolean,
        kind = kind,
        permit = optJSONObject("permit")?.let(CampingPermit::read),
        sourceUpdatedAt = stringField("sourceUpdatedAt"),
        coordinates = optJSONObject("coordinates")?.let {
            CampsiteCoordinates.from(it.optDouble("latitude"), it.optDouble("longitude"))
        }
    )
}
