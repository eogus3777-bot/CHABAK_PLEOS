package com.chabak.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TripPlacesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val today = LocalDate.of(2026, 11, 8)
    private val query = NearbyQuery(TripPlaceCategory.MARKET, VisitWindow(today))
    private class Replies(base: Context): ContextWrapper(base) {
        val sent = mutableListOf<Intent>()
        override fun sendBroadcast(intent: Intent) { sent += intent }
        fun last(): JSONObject {
            assertEquals(GleoContract.RESPONSE_ACTION, sent.last().action)
            assertEquals(packageName, sent.last().getStringExtra("packageName"))
            val reply = JSONObject(sent.last().getStringExtra("response")!!)
            assertTrue(reply.get("data") is String)
            assertEquals(1, JSONArray(reply.getString("data")).length())
            return reply
        }
    }
    private fun reason(reply: JSONObject) = JSONArray(reply.getString("data")).getJSONObject(0).getString("reason")
    private suspend fun handle(replies: Replies, intent: Intent, status: NearbyStatus = NearbyStatus.NO_RESULTS,
        start: () -> String = { error("Unexpected mutation") },
        energy: suspend () -> VehicleEnergy = { error("Unexpected vehicle read") }) {
        GleoTripAction.handle(replies, intent, today, today,
            search = { NearbyResult("isolated", it, status, "test fixture") }, start = start, energy = energy)
    }

    @Test fun schemaKeepsTheExistingSearchAndExactlyThreeResolvableActions() {
        val schema = JSONObject(context.assets.open("schema.json").bufferedReader().use { it.readText() })
        val calls = schema.getJSONArray("actionCalls")
        assertEquals(3, calls.length())
        val names = (0 until calls.length()).map { calls.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        assertEquals(setOf(GleoCampingAction.ACTION, GleoTripAction.SEARCH, GleoTripAction.MANAGE), names)
        for (name in names) {
            @Suppress("DEPRECATION")
            val activity = context.packageManager.resolveActivity(Intent(name).setPackage(context.packageName), 0)
            assertNotNull(activity)
        }
    }

    @Test fun argumentParsingSupportsTypedAndEncodedInputsAndRejectsTrailingContent() {
        val typed = GleoContract.parameters(Intent().putExtra("category", "MARKET").putExtra("weekend", true), listOf("category", "weekend"))
        assertTrue(typed.getBoolean("weekend"))
        val raw = JSONObject().put("category", "MARKET").put("weekend", true).toString()
        for (encoded in listOf(raw, JSONObject.quote(raw))) {
            val parsed = GleoContract.parameters(Intent().putExtra("ACTION_PARAM", encoded), emptyList())
            assertEquals(query.copy(strictDates = true), GleoTripAction.query(parsed, today.minusDays(1), today))
        }
        for (bad in listOf(raw + " junk", JSONObject.quote(raw + " junk"), "[]")) {
            try { GleoContract.parameters(Intent().putExtra("ACTION_PARAM", bad), emptyList()); fail("Invalid envelope") }
            catch (_: IllegalArgumentException) { } catch (_: org.json.JSONException) { }
        }
    }

    @Test fun callbacksDistinguishMissingInvalidEmptyRouteAndServiceFailures() = runBlocking {
        val replies = Replies(context)
        handle(replies, Intent(GleoTripAction.SEARCH))
        assertEquals("asking", replies.last().getString("status"))
        handle(replies, Intent(GleoTripAction.SEARCH).putExtra("category", "MARKET").putExtra("date", "2026-02-30"))
        assertEquals("INVALID_PARAMETERS", reason(replies.last()))
        for ((status, expected) in listOf(NearbyStatus.NO_RESULTS to "success", NearbyStatus.UNVERIFIED_ROUTE to "fail",
            NearbyStatus.NO_COORDINATES to "asking", NearbyStatus.SERVICE_FAILURE to "fail", NearbyStatus.SUPERSEDED to "fail")) {
            handle(replies, Intent(GleoTripAction.SEARCH).putExtra("category", "MARKET"), status)
            assertEquals(expected, replies.last().getString("status"))
            assertEquals(status.name, reason(replies.last()))
        }
    }

    @Test fun managementDoesNotReportBlockedStartsOrUnavailableBatteriesAsSuccess() = runBlocking {
        val replies = Replies(context)
        for (blocked in listOf("VEHICLE_RESTRICTED", "TRIP_ARCHIVED", "ANOTHER_TRIP_ACTIVE", "STORAGE_FAILURE")) {
            handle(replies, Intent(GleoTripAction.MANAGE).putExtra("operation", "START_STAY"), start = { blocked })
            assertEquals("fail", replies.last().getString("status"))
            assertEquals(blocked, reason(replies.last()))
        }
        for (issue in listOf(EnergyIssue.PERMISSION, EnergyIssue.UNSUPPORTED, EnergyIssue.CONNECTION, EnergyIssue.UNAVAILABLE, EnergyIssue.RESTRICTED)) {
            handle(replies, Intent(GleoTripAction.MANAGE).putExtra("operation", "READ_ENERGY"),
                energy = { VehicleEnergy(soc = EnergyRead.Missing(issue)) })
            val reply = replies.last()
            assertEquals("fail", reply.getString("status"))
            assertEquals(issue.name, reason(reply))
            assertFalse(JSONArray(reply.getString("data")).getJSONObject(0).has("stateOfChargePercent"))
        }
        for (invalid in listOf(-1, 101)) {
            handle(replies, Intent(GleoTripAction.MANAGE).putExtra("operation", "READ_ENERGY"),
                energy = { VehicleEnergy(soc = EnergyRead.Value(invalid)) })
            assertEquals("fail", replies.last().getString("status"))
        }
    }

    @Test fun successfulCallbackFixturesRemainSeparateFromRealVoiceVerification() = runBlocking {
        val replies = Replies(context)
        handle(replies, Intent(GleoTripAction.SEARCH).putExtra("category", "BREWERY"), NearbyStatus.FOUND)
        assertEquals("success", replies.last().getString("status"))
        assertFalse(JSONArray(replies.last().getString("data")).getJSONObject(0).getBoolean("openingConfirmed"))
        handle(replies, Intent(GleoTripAction.MANAGE).putExtra("operation", "START_STAY"), start = { "STARTED" })
        assertEquals("success", replies.last().getString("status"))
        assertFalse(JSONArray(replies.last().getString("data")).getJSONObject(0).getBoolean("vehicleControlPerformed"))
        handle(replies, Intent(GleoTripAction.MANAGE).putExtra("operation", "START_STAY"), start = { "ALREADY_STARTED" })
        assertEquals("ALREADY_STARTED", reason(replies.last()))
        handle(replies, Intent(GleoTripAction.MANAGE).putExtra("operation", "READ_ENERGY"),
            energy = { VehicleEnergy(soc = EnergyRead.Value(63)) })
        assertEquals(63, JSONArray(replies.last().getString("data")).getJSONObject(0).getInt("stateOfChargePercent"))
    }

    @Test fun serviceExceptionsReturnOfficialFailAndDateFlagsReachTheSearch() = runBlocking {
        val replies = Replies(context)
        val intent = Intent(GleoTripAction.SEARCH).putExtra("category", "MARKET").putExtra("weekend", true)
        GleoTripAction.handle(replies, intent, today.minusDays(1), today,
            search = {
                assertEquals(MarketCalendar.thisWeekend(today), it.window)
                assertTrue(it.strictDates)
                throw IllegalStateException("isolated service failure")
            }, start = { error("Unexpected start") }, energy = { error("Unexpected read") })
        assertEquals("fail", replies.last().getString("status"))
        assertEquals("SERVICE_FAILURE", reason(replies.last()))
        handle(replies, intent.putExtra("date", "2026-11-09"))
        assertEquals("INVALID_PARAMETERS", reason(replies.last()))
    }

    @Test fun legacyCampingSearchRetainsItsArgumentsAndCallbackContract() {
        val replies = Replies(context)
        val intent = Intent(GleoCampingAction.ACTION).putExtra("region", "경기").putExtra("nature", "숲").putExtra("electric", true)
        GleoCampingAction.handle(replies, intent) { request, complete ->
            assertEquals("경기", request.region); assertEquals("숲", request.nature); assertTrue(request.electric)
            complete(GleoCampingAction.Outcome.FOUND, listOf("isolated fixture"))
        }
        assertEquals(GleoCampingAction.ACTION, replies.last().getString("functionName"))
        assertEquals("success", replies.last().getString("status"))
    }

    @Test fun bundledOfficialDataNeverInventsBreweryLocations() {
        val data = JSONObject(context.assets.open("trip_places.json").bufferedReader().use { it.readText() })
        val rows = data.getJSONArray("places")
        val places = (0 until rows.length()).map { TripPlacesCatalog.decode(rows.getJSONObject(it)) }
        val breweries = places.filter { it.category == TripPlaceCategory.BREWERY }
        assertEquals(64, breweries.size)
        assertEquals(1, breweries.count { it.point != null })
        val visitor = breweries.single { it.point != null }
        assertTrue(visitor.locationSourceUrl.startsWith("https://www.pocheon.go.kr/"))
        assertEquals(37.9059956872132, visitor.point!!.latitude, 0.0000000000001)
        assertEquals(127.309904875075, visitor.point.longitude, 0.0000000000001)
        val markets = places.filter { it.category == TripPlaceCategory.MARKET }
        assertTrue(markets.size > 100)
        assertTrue(markets.all { it.point != null && it.marketDays.isNotEmpty() && it.sourceDate.isNotBlank() })
    }

    @Test fun rankingRequiresValidCoordinatesDistanceAndRegisteredDates() {
        val point = TravelPoint(37.5, 127.0)
        fun place(id: String, at: TravelPoint?, days: Set<Int>) = TripPlace(id, TripPlaceCategory.MARKET,
            "isolated fixture", "test address", at, "test fixture", "https://example.test", "2026-11-08", marketDays = days)
        val places = listOf(place("near-other-day", point, setOf(9)),
            place("date-match", TravelPoint(37.6, 127.0), setOf(8)), place("no-point", null, setOf(8)),
            place("reversed", TravelPoint(127.0, 37.5), setOf(8)), place("far", TravelPoint(35.0, 127.0), setOf(8)))
        assertEquals(listOf("date-match", "near-other-day"), TripPlacesCatalog.rank(places, point, query).map { it.place.id })
        assertEquals(listOf("date-match"), TripPlacesCatalog.rank(places, point, query.copy(strictDates = true)).map { it.place.id })
    }
}
