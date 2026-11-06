package com.chabak.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TripTravelInstrumentedTest {
    private val origin = TravelPoint(37.51, 127.04)
    private val camp = TravelPoint(37.47, 127.57)
    private val north = RestAreaKey("00123", "0010", "서울방향")
    private val south = RestAreaKey("00124", "0010", "부산방향")
    private val beforeEntry = RestAreaKey("00456", "0010", "서울방향")
    private val date = LocalDate.of(2026, 11, 15)

    @Test
    fun requiresBothDirectedLinksAndKeepsTheReturnEvidenceSeparate() {
        val access = listOf(
            RestAreaAccess(north, "n-entry", "n-exit"),
            RestAreaAccess(south, "s-entry", "s-exit"),
            RestAreaAccess(beforeEntry, "outside-entry", "n-exit")
        )
        val northEvidence = DirectedRoute(listOf("n-entry", "n-exit"), access)
        val southEvidence = DirectedRoute(listOf("s-entry", "s-exit"), access)
        assertTrue(DirectedRoute(listOf("n-exit", "n-entry"), access).accessibleKeys().isEmpty())
        assertEquals(listOf(north), northEvidence.accessibleKeys())
        assertEquals(listOf(south), southEvidence.accessibleKeys())
        val northArea = fixtureArea(north)
        val southArea = fixtureArea(south)
        val catalog = listOf(northArea, southArea, fixtureArea(beforeEntry))
        val northLeg = leg(TravelLeg.OUTBOUND, origin, camp, northEvidence)
        val southLeg = leg(TravelLeg.RETURN, camp, origin, southEvidence)
        assertTrue(northLeg.copy(directedRoute = null).candidates(catalog).isEmpty())
        assertEquals(listOf(northArea), northLeg.candidates(catalog))
        assertEquals(listOf(southArea), southLeg.candidates(catalog))
        assertNull(northLeg.select(southArea))
        assertNull(southLeg.select(northArea))
        val plan = TripTravel(TripOrigin("테스트 출발지", origin), camp)
        assertNull(plan.withLeg(TravelLeg.RETURN, northLeg))
        assertNull(plan.withLeg(TravelLeg.OUTBOUND, southLeg))
        assertNull(plan.withLeg(TravelLeg.OUTBOUND, northLeg.copy(direction = TravelLeg.RETURN)))
    }

    @Test
    fun migratesAnExistingVersionOneTripWithoutChangingItsChecklist() {
        val context = context()
        val database = "step057-v1-${UUID.randomUUID()}.db"
        try {
            val place = JSONObject().put("id", "old").put("name", "테스트 이전 캠핑장").put("region", "경기")
            context.openOrCreateDatabase(database, Context.MODE_PRIVATE, null).use { db ->
                db.execSQL("CREATE TABLE trips (trip_id TEXT PRIMARY KEY NOT NULL, campsite TEXT NOT NULL, visit_date TEXT NOT NULL, people INTEGER NOT NULL, nights INTEGER NOT NULL, packed TEXT NOT NULL, extra_items TEXT NOT NULL)")
                db.execSQL("CREATE TABLE trip_selection (slot INTEGER PRIMARY KEY, trip_id TEXT NOT NULL REFERENCES trips(trip_id))")
                db.execSQL("INSERT INTO trips VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("v1-trip", place.toString(), date.toString(), 3, 2, """["mat","custom_bag"]""", """["bag"]"""))
                db.execSQL("INSERT INTO trip_selection VALUES (1, 'v1-trip')")
                db.version = 1
            }
            TripStore(context, database).use { store ->
                val old = store.loadOrMigrate { error("existing trip should load") }.activeTrip
                assertEquals("v1-trip", old.tripId)
                assertEquals("old", old.place.id)
                assertEquals(date, old.visitDate)
                assertEquals(3, old.people)
                assertEquals(2, old.nights)
                assertEquals(setOf("mat", "custom_bag"), old.packedItemIds)
                assertEquals(listOf("bag"), old.extraItems)
                assertEquals(TripTravel(), old.travel)
                store.updateTrip(old.tripId) { it.copy(travel = TripTravel(TripOrigin("직접 입력한 출발지"))) }
            }
            TripStore(context, database).use {
                val restored = it.loadOrMigrate { error("migrated trip should load") }.activeTrip
                assertEquals("직접 입력한 출발지", restored.travel.origin?.label)
                assertEquals(setOf("mat", "custom_bag"), restored.packedItemIds)
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    @Test
    fun restoresBothLegsAndSelectionsAndResetsWhenAnEndpointChanges() {
        val context = context()
        val database = "step057-plan-${UUID.randomUUID()}.db"
        val northLeg = leg(TravelLeg.OUTBOUND, origin, camp, DirectedRoute(
            listOf("n-entry", "n-exit"), listOf(RestAreaAccess(north, "n-entry", "n-exit"))
        ))
        val southLeg = leg(TravelLeg.RETURN, camp, origin, DirectedRoute(
            listOf("s-entry", "s-exit"), listOf(RestAreaAccess(south, "s-entry", "s-exit"))
        ))
        var plan = TripTravel(TripOrigin("테스트 출발지", origin), camp)
        plan = requireNotNull(plan.withLeg(TravelLeg.OUTBOUND, northLeg))
        plan = requireNotNull(plan.withLeg(TravelLeg.RETURN, southLeg))
        plan = requireNotNull(plan.selectArea(TravelLeg.OUTBOUND, fixtureArea(north)))
        plan = requireNotNull(plan.selectArea(TravelLeg.RETURN, fixtureArea(south)))
        val saved = Trip("fixture", fixturePlace(), date, 2, 1, setOf("mat", "site_forest_camp"), travel = plan)
        try {
            TripStore(context, database).use { it.loadOrMigrate { saved } }
            TripStore(context, database).use { store ->
                val restored = store.loadOrMigrate { error("expected saved travel") }.activeTrip
                assertEquals(saved.travel, restored.travel)
                assertEquals(7500, restored.travel.outbound?.selectedArea?.foods?.first()?.priceWon)
                assertNull(restored.travel.homeward?.selectedArea?.foods?.last()?.priceWon)
                val sameDateTrip = store.createTrip(restored.tripId).activeTrip
                assertEquals(restored.visitDate, sameDateTrip.visitDate)
                assertEquals(TripTravel(), sameDateTrip.travel)
                val reselected = store.openTrip(restored.tripId).activeTrip
                val moved = reselected.withPlace(fixturePlace().copy(id = "different"))
                assertEquals(setOf("mat"), moved.packedItemIds)
                assertEquals(plan.origin, moved.travel.origin)
                assertNull(moved.travel.campsitePoint)
                assertNull(moved.travel.outbound)
                assertNull(moved.travel.homeward)
                val newStart = reselected.travel.withOrigin(TripOrigin("다른 출발지"))
                assertNull(newStart.outbound)
                assertNull(newStart.homeward)
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    @Test
    fun restrictsDrivingAndRejectsUnmatchedCoordinates() {
        assertEquals(TravelInteraction.UNKNOWN, travelInteraction(true, null))
        assertEquals(TravelInteraction.RESTRICTED, travelInteraction(true, true))
        assertEquals(TravelInteraction.ALLOWED, travelInteraction(true, false))
        val html = """
            <h1>테스트 캠핑장</h1><span>경기도 테스트로 1</span>
            var lng = "127.57";
            var lat = "37.47";
            var position = new naver.maps.LatLng(lat, lng);
        """.trimIndent()
        assertEquals(camp, CampsiteCoordinates.parseMapCoordinates(html, fixturePlace()))
        assertNull(CampsiteCoordinates.parseMapCoordinates(html, fixturePlace().copy(address = "경기 다른길 1")))
        assertNull(CampsiteCoordinates.parseMapCoordinates(html + """var lat = "36";""", fixturePlace()))
        assertFalse(origin.matches(camp))
    }

    private fun fixturePlace() = Place(
        "camp", "테스트 캠핑장", "경기", "", R.drawable.chabak_day_photo, "숲", "",
        address = "경기 테스트로 1"
    )

    private fun fixtureArea(key: RestAreaKey) =
        RestArea(key, "테스트 휴게소", "테스트 노선", null, listOf(RestAreaFood("테스트 메뉴", 7500), RestAreaFood("가격 미확인", null)), 100L)

    private fun leg(direction: TravelLeg, from: TravelPoint, to: TravelPoint, evidence: DirectedRoute) =
        SavedLeg(direction, from, to, 100L, 32000, 1200, emptyList(), evidence)

    private fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext
}
