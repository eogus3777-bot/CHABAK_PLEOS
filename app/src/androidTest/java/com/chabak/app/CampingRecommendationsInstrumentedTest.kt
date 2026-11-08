package com.chabak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CampingRecommendationsInstrumentedTest {
    private val today = LocalDate.of(2027, 6, 10)

    @Test
    fun recommendsAtMostFiveDistinctCampgroundsMatchingTheSelectedConditions() {
        val items = JSONArray()
            .put(entry(camp("ref_example")))
            .put(entry(camp("gocamping_1").put("facilities", "전기차 충전소")))
            .put(entry(camp("gocamping_2").put("setting", "산")))
            .put(entry(camp("gocamping_3").put("toilets", 0)))
            .put(entry(camp("gocamping_10")))
        for (id in 10..16) items.put(entry(camp("gocamping_$id")))
        val response = JSONObject()
            .put("source", GOCAMPING_SOURCE)
            .put("updatedAt", "2026-11-06T15:00:00+00:00")
            .put("items", items)

        val criteria = CampingCriteria("경기", nature = "숲", toilet = true, campingPower = true)
        val result = GoCampingCatalog.decodeRecommendations(response, criteria).items
        assertEquals((10..14).map { "gocamping_$it" }, result.map { it.place.id })
        val first = result.first().place
        assertEquals("테스트 주소", first.address)
        assertEquals(3, first.toiletCount)
        assertEquals(0, first.showerCount)
        assertNull(first.sinkCount)
        assertEquals(true, first.campingPower)
        assertNull(first.onsiteEvCharger)
        assertNull(first.nearbyEvCharger)
        assertNull(first.overnightAllowed)
        assertEquals("https://example.test/reserve", first.bookingLink())
        assertTrue(first.goCampingDetailUrl().orEmpty().contains("c_no=10"))
        assertEquals(LocalDate.of(2026, 11, 7), goCampingCollectionDay(first.sourceUpdatedAt))
        assertFalse(criteria.copy(evCharging = true).matches(first))
    }

    @Test
    fun showerRequirementRejectsZeroAndUnknownCounts() {
        val criteria = CampingCriteria("경기", shower = true)
        val items = JSONArray()
            .put(entry(camp("gocamping_1")))
            .put(entry(camp("gocamping_2").put("showers", JSONObject.NULL)))
            .put(entry(camp("gocamping_3").put("showers", 1)))
        val response = JSONObject().put("source", GOCAMPING_SOURCE).put("items", items)
        val result = GoCampingCatalog.decodeRecommendations(response, criteria).items
        assertEquals(listOf("gocamping_3"), result.map { it.place.id })
    }

    @Test
    fun rejectsOtherSourcesAndSeparatesCampingPowerFromVehicleCharging() {
        try {
            GoCampingCatalog.decodeList(JSONObject().put("source", "fixture").put("items", JSONArray()))
            fail("An unrecognized source must not be presented as GoCamping data")
        } catch (_: IOException) {
            assertNull(registeredCampingPower("온수,전기차 충전소"))
            assertEquals(true, registeredCampingPower("온수, 전기"))
        }
        assertFalse(requestedCampingPower("전기차 충전소가 있는 캠핑장"))
        assertTrue(requestedEvCharging("전기차 충전소가 있는 캠핑장"))
        assertTrue(requestedCampingPower("캠핑용 전기와 차량 충전소"))
        assertTrue(requestedEvCharging("캠핑용 전기와 차량 충전소"))
    }

    @Test
    fun reviewedSiteRequiresAnActivePermissionThatCoversTheWholeStay() {
        val item = reviewedItem().put("region", "서울")
        val root = JSONObject().put("schemaVersion", 1).put("items", JSONArray().put(item))
        val places = ReviewedCampingSites.decode(root)
        val place = places.single()
        assertEquals(CampingPlaceKind.PERMITTED_UNDEVELOPED, place.kind)
        assertTrue(CampingCriteria("전국", kind = place.kind).matches(place))
        assertEquals("테스트 관리기관", place.permit?.manager)
        assertEquals(false, place.permit?.cookingAllowed)
        assertNull(place.permit?.openFlameAllowed)
        assertTrue(place.isVerifiedStay(today, today.plusDays(1), 2))
        assertFalse(place.isVerifiedStay(today, today.plusDays(2), 2))
        assertFalse(place.isVerifiedStay(today.plusDays(4), today.plusDays(4), 1))
        assertFalse(place.isVerifiedStay(today.minusDays(1), today, 1))
        assertFalse(place.copy(overnightAllowed = null).isVerifiedStay(today, today, 1))
        assertFalse(place.copy(permit = place.permit?.copy(nightParkingAllowed = false)).isVerifiedStay(today, today, 1))
        assertFalse(place.copy(permit = place.permit?.copy(vehicleAccessAllowed = null)).isVerifiedStay(today, today, 1))
        assertNull(ReviewedCampingSites.usablePlace(place, emptyList(), today, today, 1))
        assertEquals(place, ReviewedCampingSites.usablePlace(place, places, today, today, 1))

        item.getJSONObject("permit").put("sourceUrl", "http://example.test/terms")
        assertTrue(ReviewedCampingSites.decode(root).isEmpty())
    }

    @Test
    fun savesOfficialCoordinatesWithTheSelectedTripWithoutLosingOlderTrips() {
        val point = TravelPoint(37.123456789012, 127.123456789012)
        val item = camp("gocamping_10").put("mapX", point.longitude.toString()).put("mapY", point.latitude)
        val response = JSONObject().put("source", GOCAMPING_SOURCE).put("items", JSONArray().put(entry(item)))
        val place = GoCampingCatalog.decodeRecommendations(response, CampingCriteria("경기")).items.single().place
        assertEquals(point, place.coordinates)
        item.put("mapX", point.latitude).put("mapY", point.longitude)
        assertNull(GoCampingCatalog.decodeRecommendations(response, CampingCriteria("경기")).items.single().place.coordinates)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "gocamping-coordinates-${UUID.randomUUID()}.db"
        val legacy = Trip(UUID.randomUUID().toString(), place.copy(coordinates = null), today, people = 2, nights = 1, packedItemIds = setOf("mat"))
        try {
            val selectedId = TripStore(context, database).use { store ->
                store.loadOrMigrate { legacy }
                store.createTrip(legacy.tripId, place).activeTripId
            }
            TripStore(context, database).use { store ->
                val restored = store.loadOrMigrate { error("Must restore the database") }
                assertEquals(selectedId, restored.activeTripId)
                assertEquals(point, restored.activeTrip.place.coordinates)
                assertEquals(legacy, store.openTrip(legacy.tripId).activeTrip)
                store.updateTrip(legacy.tripId) { it.copy(travel = it.travel.withCampsitePoint(point)) }
            }
            TripStore(context, database).use { store ->
                val restored = store.loadOrMigrate { error("Must restore the database") }.activeTrip
                assertEquals(legacy.tripId, restored.tripId)
                assertEquals(point, restored.travel.campsitePoint)
                assertEquals(legacy.packedItemIds, restored.packedItemIds)
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    @Test
    fun savesReviewedEvidenceAndRestoresItsTypeWithTheTrip() {
        val record = reviewedItem().put("kind", CampingPlaceKind.PUBLIC_FREE.name)
        val place = ReviewedCampingSites.decode(JSONObject()
            .put("schemaVersion", 1).put("items", JSONArray().put(record))).single()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "step056-test-${UUID.randomUUID()}.db"
        val trip = Trip(UUID.randomUUID().toString(), place, today, people = 2, nights = 1)
        try {
            TripStore(context, database).use { assertEquals(trip, it.loadOrMigrate { trip }.activeTrip) }
            TripStore(context, database).use { store ->
                val restored = store.loadOrMigrate { error("The existing trip must be restored") }.activeTrip
                assertEquals(trip, restored)
                assertNull(restored.place.nearbyEvCharger)
                assertEquals(true, restored.place.overnightAllowed)
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    @Test
    fun checklistUsesPeopleNightsSeasonAndOnlyMatchingCurrentObservation() {
        val place = GoCampingCatalog.decodeList(JSONObject()
            .put("source", GOCAMPING_SOURCE)
            .put("items", JSONArray().put(camp("gocamping_10")))).places.single()
        val trip = Trip(UUID.randomUUID().toString(), place, today, people = 3, nights = 2)
        val weather = PackingObservation("경기", today, 4, "비")
        val items = tripPackItems(trip, place, weather, today)
        assertEquals("식수 약 18L (예비 포함)", items.first { it.id == "water" }.name)
        assertEquals("베개 3개", items.first { it.id == "pillow" }.name)
        assertTrue(items.any { it.id == "summer" })
        assertTrue(items.any { it.id == "site_forest_gocamping_10" && it.description.isNotBlank() })
        assertTrue(items.any { it.id == "rain" })
        assertTrue(items.any { it.id == "cold" })
        assertTrue(tripPackItems(trip.copy(visitDate = today.plusDays(1)), place, weather, today).none { it.group == "날씨" })
        assertTrue(tripPackItems(trip, place, weather.copy(region = "강원"), today).none { it.group == "날씨" })
        assertTrue(tripPackItems(trip, null, weather, today).none { it.group == "장소" || it.group == "날씨" })
    }

    private fun camp(id: String) = JSONObject()
        .put("id", id).put("name", "테스트 캠핑장").put("address", "테스트 주소")
        .put("region", "경기").put("setting", "산,숲").put("facilities", "전기,온수")
        .put("toilets", 3).put("showers", 0).put("sinks", JSONObject.NULL)
        .put("reservationUrl", "https://example.test/reserve")
        .put("onsiteEvCharger", true).put("overnightAllowed", true)

    private fun entry(place: JSONObject) = JSONObject().put("place", place)
        .put("reasons", JSONArray().put("전기 시설 등록"))

    private fun reviewedItem() = JSONObject()
        .put("id", "reviewed_fixture").put("name", "테스트 허용 장소").put("address", "테스트 주소")
        .put("region", "경기").put("setting", "숲").put("kind", CampingPlaceKind.PERMITTED_UNDEVELOPED.name)
        .put("overnightAllowed", true)
        .put("permit", JSONObject()
            .put("latitude", 37.0).put("longitude", 127.0).put("manager", "테스트 관리기관")
            .put("verifiedOn", today.toString()).put("allowedFrom", today.toString())
            .put("allowedThrough", today.plusDays(3).toString()).put("sourceUrl", "https://example.test/terms")
            .put("vehicleAccessAllowed", true).put("accessRoad", "테스트 도로")
            .put("nightParkingAllowed", true).put("toiletAvailable", false).put("cookingAllowed", false))
}
