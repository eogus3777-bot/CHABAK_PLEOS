package com.chabak.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TripEnergyInstrumentedTest {
    @Test
    fun restoresIndependentWakeTimesAndExistingTripData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "morning-${UUID.randomUUID()}.db"
        val first = Trip(
            UUID.randomUUID().toString(),
            Place("morning_test", "Test", "경기", "", R.drawable.chabak_day_photo, "숲", ""),
            LocalDate.of(2026, 11, 8), 2, 1,
            packedItemIds = setOf("mat"), energyPlan = TripEnergyPlan(minimumPercent = 35),
            wakeMinuteOfDay = 6 * 60 + 5
        )
        try {
            val secondId = TripStore(context, database).use { store ->
                store.loadOrMigrate { first }
                val second = store.createTrip(first.tripId).activeTrip
                assertNull(second.wakeMinuteOfDay)
                store.updateTrip(second.tripId) { it.copy(wakeMinuteOfDay = 7 * 60 + 40) }
                second.tripId
            }
            TripStore(context, database).use { store ->
                assertEquals(460, store.loadOrMigrate { error("Must restore trips") }.activeTrip.wakeMinuteOfDay)
                assertEquals(first, store.openTrip(first.tripId).activeTrip)
                assertEquals(460, store.openTrip(secondId).activeTrip.wakeMinuteOfDay)
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val start = TravelPoint(37.51, 127.04)
    private val camp = TravelPoint(37.47, 127.57)

    @Test
    fun restoresIndependentEnergyPlansForSameDateTrips() {
        val database = "energy-test-${UUID.randomUUID()}.db"
        try {
            val first = TripEnergyPlan(6, 24, 0.8)
            val second = TripEnergyPlan(10, 43, 1.7)
            val (tripA, tripB) = TripStore(context, database).use { store ->
                val tripA = store.loadOrMigrate(::fixture).activeTrip
                store.updateTrip(tripA.tripId) { it.copy(energyPlan = first) }
                val tripB = store.createTrip(tripA.tripId).activeTrip
                assertEquals(TripEnergyPlan(), tripB.energyPlan)
                assertEquals(tripA.visitDate, tripB.visitDate)
                assertNotEquals(tripA.tripId, tripB.tripId)
                store.updateTrip(tripB.tripId) { it.copy(energyPlan = second) }
                tripA to tripB
            }
            TripStore(context, database).use { reopened ->
                assertEquals(first, reopened.openTrip(tripA.tripId).activeTrip.energyPlan)
                assertEquals(second, reopened.openTrip(tripB.tripId).activeTrip.energyPlan)
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    @Test
    fun migratesVersionTwoWithoutLosingRouteOrPacking() {
        val database = "energy-v2-${UUID.randomUUID()}.db"
        val trip = fixture()
        try {
            context.openOrCreateDatabase(database, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("""
                    CREATE TABLE trips (
                        trip_id TEXT PRIMARY KEY NOT NULL, campsite TEXT NOT NULL, visit_date TEXT NOT NULL,
                        people INTEGER NOT NULL, nights INTEGER NOT NULL, packed TEXT NOT NULL,
                        extra_items TEXT NOT NULL, travel TEXT NOT NULL DEFAULT '{}'
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE trip_selection (
                        slot INTEGER PRIMARY KEY, trip_id TEXT NOT NULL REFERENCES trips(trip_id)
                    )
                """.trimIndent())
                db.execSQL(
                    "INSERT INTO trips VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(
                        trip.tripId, """{"id":"camp_test","name":"캠핑장 시험"}""", trip.visitDate.toString(),
                        2, 1, """["mat"]""", "[]", trip.travel.toJson()
                    )
                )
                db.execSQL("INSERT INTO trip_selection VALUES (1, ?)", arrayOf(trip.tripId))
                db.version = 2
            }
            TripStore(context, database).use { store ->
                val migrated = store.loadOrMigrate { error("Existing trip must be retained") }.activeTrip
                assertEquals(trip.tripId, migrated.tripId)
                assertEquals(trip.travel, migrated.travel)
                assertEquals(setOf("mat"), migrated.packedItemIds)
                assertEquals(TripEnergyPlan(), migrated.energyPlan)
                assertEquals(TripDiary(), migrated.diary)
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    private fun fixture(): Trip = Trip(
        tripId = UUID.randomUUID().toString(),
        place = Place("camp_test", "캠핑장 시험", "경기", "", R.drawable.chabak_day_photo, "숲", ""),
        visitDate = LocalDate.of(2026, 11, 20),
        people = 2,
        nights = 1,
        travel = TripTravel(
            TripOrigin("테스트 출발", start), camp,
            SavedLeg(TravelLeg.OUTBOUND, start, camp, 1_000L, 20_000, 1_800, emptyList())
        )
    )
}
