package com.chabak.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal object GleoTripAction {
    const val SEARCH = "SEARCH_TRIP_PLACES"
    const val MANAGE = "MANAGE_CAMPING_TRIP"
    val actions = setOf(SEARCH, MANAGE)

    fun query(json: JSONObject, tripDate: LocalDate, today: LocalDate): NearbyQuery {
        val category = TripPlaceCategory.valueOf(GleoContract.text(json, "category"))
        val date = GleoContract.text(json, "date")
        val weekend = GleoContract.flag(json, "weekend")
        require(!(weekend && date.isNotBlank()))
        val window = when {
            weekend -> MarketCalendar.thisWeekend(today)
            date.isNotBlank() -> VisitWindow(try { LocalDate.parse(date) } catch (_: java.time.DateTimeException) {
                throw IllegalArgumentException("Invalid date")
            })
            else -> VisitWindow(tripDate)
        }
        require(window.start.year in 2000..2100)
        return NearbyQuery(category, window, strictDates = weekend || date.isNotBlank())
    }

    suspend fun handle(
        context: Context, intent: Intent, tripDate: LocalDate, today: LocalDate,
        search: suspend (NearbyQuery) -> NearbyResult,
        start: () -> String,
        energy: suspend () -> VehicleEnergy,
        reply: (GleoResponse) -> Unit = { GleoContract.reply(context, intent.action!!, it) }
    ) {
        val action = intent.action ?: return
        if (action !in actions) return
        try {
            val params = GleoContract.parameters(intent, listOf("category", "date", "weekend", "operation"))
            when (action) {
                SEARCH -> {
                    if (GleoContract.text(params, "category").isBlank()) {
                        reply(GleoResponse("asking", "A place category is required.", "MISSING_CATEGORY"))
                        return
                    }
                    val request = query(params, tripDate, today)
                    val result = search(request)
                    val matchedDates = result.items.any { it.matchesDates }
                    val status = when (result.status) {
                        NearbyStatus.FOUND, NearbyStatus.NO_RESULTS -> "success"
                        NearbyStatus.NO_COORDINATES -> "asking"
                        NearbyStatus.SERVICE_FAILURE -> "fail"
                        else -> "fail"
                    }
                    val message = when (result.status) {
                        NearbyStatus.FOUND -> if (request.category == TripPlaceCategory.MARKET)
                            "Periodic market candidates are displayed using registered dates; actual opening must be checked."
                            else "Official place candidates near the selected campsite are displayed."
                        NearbyStatus.UNVERIFIED_ROUTE -> "The route direction and rest-area access links are not verified."
                        NearbyStatus.NO_COORDINATES -> "Official coordinates for the selected campsite are required."
                        NearbyStatus.NO_RESULTS -> "No verified place candidates match this search."
                        NearbyStatus.SUPERSEDED -> "The selected trip or search changed."
                        NearbyStatus.SERVICE_FAILURE -> "The official place data could not be loaded."
                    }
                    val details = JSONObject().put("resultCount", result.items.size)
                        .put("placeNames", JSONArray(result.items.map { it.place.name }.take(3)))
                        .put("dateStart", request.window.start.toString()).put("dateEnd", request.window.end.toString())
                        .put("openingConfirmed", false).put("registeredDateMatch", matchedDates)
                    reply(GleoResponse(status, message, result.status.name, details))
                }
                MANAGE -> when (GleoContract.text(params, "operation")) {
                    "START_STAY" -> {
                        val reason = start()
                        val success = reason == "STARTED" || reason == "ALREADY_STARTED"
                        val message = when (reason) {
                            "STARTED", "ALREADY_STARTED" -> "The CHABAK camping stay is active. Setup recommendations and consented lighting checks continue in CHABAK."
                            "VEHICLE_RESTRICTED" -> "The stay cannot start until parked interaction is permitted."
                            "TRIP_ARCHIVED" -> "An archived trip cannot be started."
                            "ANOTHER_TRIP_ACTIVE" -> "Another camping stay is already active."
                            "STORAGE_FAILURE" -> "CHABAK could not save the camping stay."
                            else -> "The camping stay could not be started."
                        }
                        reply(GleoResponse(if (success) "success" else "fail", message, reason,
                            JSONObject().put("operation", "START_STAY").put("vehicleControlPerformed", false)
                                .put("setup", if (reason == "STARTED") "CHECKS_PENDING" else "EXISTING_STAY")))
                    }
                    "READ_ENERGY" -> {
                        val reading = energy()
                        val soc = reading.soc.valueOrNull()?.takeIf { it in 0..100 }
                        val issue = (reading.soc as? EnergyRead.Missing)?.issue?.name ?: "UNAVAILABLE"
                        val emulator = android.os.Build.HARDWARE in setOf("ranchu", "goldfish")
                        val details = JSONObject().put("source", "Pleos Vehicle SDK")
                            .put("environment", if (emulator) "pleos_emulator" else "vehicle_sdk")
                            .put("operation", "READ_ENERGY").put("vehicleControlPerformed", false)
                        if (soc != null) details.put("stateOfChargePercent", soc)
                        reply(GleoResponse(if (soc != null) "success" else "fail",
                            if (soc != null && emulator) "The Pleos emulator Vehicle SDK reports an emulated battery charge."
                            else if (soc != null) "The Vehicle SDK reports the current battery charge."
                            else "The Vehicle SDK battery reading is unavailable.",
                            if (soc != null) "ENERGY_READ" else issue, details))
                    }
                    "" -> reply(GleoResponse("asking", "An operation is required.", "MISSING_OPERATION"))
                    else -> throw IllegalArgumentException("Unknown operation")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            reply(GleoResponse("fail", "The action parameters are invalid.", "INVALID_PARAMETERS"))
        } catch (_: org.json.JSONException) {
            reply(GleoResponse("fail", "The action parameters are invalid.", "INVALID_PARAMETERS"))
        } catch (_: Exception) {
            reply(GleoResponse("fail", "The requested operation could not complete.", "SERVICE_FAILURE"))
        }
    }
}
