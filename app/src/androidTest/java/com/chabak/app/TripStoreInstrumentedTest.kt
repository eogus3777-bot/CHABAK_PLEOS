package com.chabak.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TripStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "trip-test-${UUID.randomUUID()}.db"
    private val legacyPrefsName = "$databaseName.legacy"
    private var store = TripStore(context, databaseName)
    private val date = LocalDate.of(2027, 2, 6)
    private val forest = Place(
        id = "forest-fixture", name = "숲 캠핑장", region = "경기", detail = "포천",
        picture = R.drawable.chabak_day_photo, theme = "숲", services = "전기, 온수",
        address = "테스트 주소 A", toiletCount = 2, showerCount = 1, live = true,
        nearbyEvCharger = true, overnightAllowed = true
    )
    private val coast = Place(
        id = "coast-fixture", name = "해변 캠핑장", region = "강원", detail = "양양",
        picture = R.drawable.chabak_evening_photo, theme = "해변", services = "",
        address = "테스트 주소 B", showerCount = 0, live = true, campingPower = false
    )

    @After
    fun closeTestStorage() {
        store.close()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(legacyPrefsName)
    }

    @Test
    fun migratesCurrentLegacyTripOnceAndPreservesPreferences() {
        val legacy = context.getSharedPreferences(legacyPrefsName, Context.MODE_PRIVATE)
        val savedPlace = JSONObject()
            .put("id", "legacy-site")
            .put("name", "저장된 캠핑장")
            .put("region", "경기")
            .put("district", "포천")
            .put("setting", "숲")
            .put("facilities", "온수, 전기")
            .put("toilets", 3)
            .put("showers", JSONObject.NULL)
            .put("live", true)
        assertTrue(legacy.edit()
            .putString("place", "legacy-site")
            .putString("selectedPlaceInfo", savedPlace.toString())
            .putString("date", date.toString())
            .putInt("people", 3)
            .putInt("nights", 2)
            .putStringSet("packed_$date", setOf("mat", "site_forest_legacy-site"))
            .putStringSet("packed_${date.plusDays(1)}", setOf("pillow"))
            .putStringSet("packed", setOf("blanket"))
            .putString("extra", "담요|여벌 옷")
            .putInt("wakeHour", 6)
            .commit())
        val before = legacy.all

        val trip = store.loadOrMigrate { readLegacyTrip(legacy.all, listOf(forest), date.plusDays(5)) }.activeTrip
        assertEquals(date, trip.visitDate)
        assertEquals(3, trip.people)
        assertEquals(2, trip.nights)
        assertEquals("legacy-site", trip.place.id)
        assertEquals(3, trip.place.toiletCount)
        assertNull(trip.place.showerCount)
        assertEquals(true, trip.place.campingPower)
        assertNull(trip.place.onsiteEvCharger)
        assertNull(trip.place.nearbyEvCharger)
        assertNull(trip.place.overnightAllowed)
        assertEquals(setOf("mat", "site_forest_legacy-site"), trip.packedItemIds)
        assertEquals(listOf("담요", "여벌 옷"), trip.extraItems)
        assertEquals(before, legacy.all)

        reopenStore()
        val catalog = store.loadOrMigrate { error("Legacy data must be imported only once") }
        assertEquals(listOf(trip), catalog.trips)
        assertEquals(trip.tripId, catalog.activeTripId)
    }

    @Test
    fun importsUndatedLegacyChecksOnlyWhenDatedChecksDoNotExist() {
        val undated = mapOf("packed" to setOf("mat"))
        assertEquals(setOf("mat"), readLegacyTrip(undated, listOf(forest), date).packedItemIds)

        val otherDate = undated + ("packed_${date.minusDays(1)}" to setOf("water"))
        assertTrue(readLegacyTrip(otherDate, listOf(forest), date).packedItemIds.isEmpty())
    }

    @Test
    fun sameDateTripsRestoreTheirOwnCampsiteAndChecklist() {
        val tripA = startTrip()
        store.updateTrip(tripA.tripId) {
            it.copy(packedItemIds = setOf("mat", "custom_물|컵"), extraItems = listOf("물|컵"))
        }
        val tripB = store.createTrip(tripA.tripId).activeTrip
        assertNotEquals(tripA.tripId, tripB.tripId)
        assertEquals(tripB.tripId, UUID.fromString(tripB.tripId).toString())
        assertEquals(tripA.visitDate, tripB.visitDate)
        assertTrue(tripB.packedItemIds.isEmpty())
        assertTrue(tripB.extraItems.isEmpty())
        val updatedB = coast.copy(onsiteEvCharger = true, nearbyEvCharger = false, overnightAllowed = false)
        store.updateTrip(tripB.tripId) {
            it.withPlace(updatedB).copy(people = 4, nights = 3, packedItemIds = setOf("water"))
        }

        reopenStore()
        val restored = store.loadOrMigrate { error("Existing trips must be restored") }
        assertEquals(2, restored.trips.size)
        assertEquals(tripB.tripId, restored.activeTripId)
        assertEquals(updatedB, restored.activeTrip.place)
        assertEquals(date, restored.activeTrip.visitDate)
        assertEquals(4, restored.activeTrip.people)
        assertEquals(3, restored.activeTrip.nights)
        assertEquals(setOf("water"), restored.activeTrip.packedItemIds)

        val restoredA = store.openTrip(tripA.tripId).activeTrip
        assertEquals(forest, restoredA.place)
        assertEquals(date, restoredA.visitDate)
        assertEquals(2, restoredA.people)
        assertEquals(1, restoredA.nights)
        assertEquals(setOf("mat", "custom_물|컵"), restoredA.packedItemIds)
        assertEquals(listOf("물|컵"), restoredA.extraItems)
    }

    @Test
    fun changingCampsiteRefreshesScopedItemsAndKeepsGeneralChecks() {
        val trip = startTrip()
        val originalItems = campsitePackItems(forest)
        assertEquals(
            setOf("site_forest_${forest.id}", "site_ev_nearby_${forest.id}"),
            originalItems.map { it.id }.toSet()
        )
        store.updateTrip(trip.tripId) {
            it.copy(packedItemIds = originalItems.map { item -> item.id }.toSet() + "mat")
        }

        val moved = store.updateTrip(trip.tripId) { it.withPlace(coast) }.activeTrip
        assertEquals(trip.tripId, moved.tripId)
        assertEquals(setOf("mat"), moved.packedItemIds)
        val newItems = campsitePackItems(moved.place)
        assertEquals(setOf(
            "site_sea_${coast.id}", "site_toilet_check_${coast.id}",
            "site_shower_alternative_${coast.id}", "site_power_alternative_${coast.id}",
            "site_ev_check_${coast.id}", "site_overnight_check_${coast.id}"
        ), newItems.map { it.id }.toSet())
        assertEquals(3, newItems.count { it.name.contains("확인 필요") })
        assertNull(moved.place.toiletCount)
        assertNull(moved.place.onsiteEvCharger)
        assertNull(moved.place.overnightAllowed)
    }

    @Test
    fun openingUnknownTripLeavesTheActiveTripIntact() {
        val trip = startTrip()
        try {
            store.openTrip(UUID.randomUUID().toString())
            fail("An unknown trip must not become active")
        } catch (_: SQLiteConstraintException) {
            val restored = store.loadOrMigrate { error("The original trip must be intact") }
            assertEquals(trip.tripId, restored.activeTripId)
            assertEquals(listOf(trip), restored.trips)
        }
    }

    private fun startTrip(): Trip = store.loadOrMigrate {
        Trip(UUID.randomUUID().toString(), forest, date, people = 2, nights = 1)
    }.activeTrip

    private fun reopenStore() {
        store.close()
        store = TripStore(context, databaseName)
    }
}
