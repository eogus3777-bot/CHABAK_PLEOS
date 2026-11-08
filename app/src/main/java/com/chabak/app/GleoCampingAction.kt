package com.chabak.app

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

internal object GleoCampingAction {
    const val ACTION = "SEARCH_CAMPING_PLACE"
    private val regions = setOf("전국", "경기", "강원", "충청", "경상", "전라", "제주")
    private val environments = setOf("숲", "산", "계곡", "바다", "강", "호수")

    data class Query(
        val region: String,
        val district: String,
        val nature: String,
        val toilet: Boolean,
        val electric: Boolean,
        val store: Boolean
    )

    enum class Outcome {
        FOUND, NO_RESULTS, SERVICE_FAILURE, SUPERSEDED
    }

    fun handle(
        context: Context,
        intent: Intent,
        search: (Query, (Outcome, List<String>) -> Unit) -> Unit
    ) {
        if (intent.action != ACTION) return

        val query = try {
            val parameters = parameters(intent)
            val region = parameters.readText("region")
            if (region.isEmpty()) {
                respond(context, "asking", "A region is required to search for campsites.", "MISSING_REGION")
                return
            }
            require(region in regions)
            val district = parameters.readText("district")
            require(district.length <= 60 && district.none(Char::isISOControl))
            val nature = parameters.readText("nature")
            require(nature.isEmpty() || nature in environments)
            Query(
                region, district, nature,
                parameters.readFlag("toilet"),
                parameters.readFlag("electric"),
                parameters.readFlag("store")
            )
        } catch (_: Exception) {
            respond(context, "fail", "The campsite search parameters are invalid.", "INVALID_PARAMETERS")
            return
        }

        search(query) { outcome, places ->
            when (outcome) {
                Outcome.FOUND -> respond(
                    context, "success", "Matching registered campsites are shown in CHABAK.", "FOUND", places
                )
                Outcome.NO_RESULTS -> respond(context, "fail", "No registered campsites match the requested criteria.", "NO_RESULTS")
                Outcome.SERVICE_FAILURE -> respond(context, "fail", "The campsite recommendation service is unavailable.", "SERVICE_FAILURE")
                Outcome.SUPERSEDED -> respond(context, "fail", "A newer campsite search replaced this request.", "SUPERSEDED")
            }
        }
    }

    private fun parameters(intent: Intent): JSONObject =
        GleoContract.parameters(intent, listOf("region", "district", "nature", "toilet", "electric", "store"))

    private fun JSONObject.readText(name: String): String = GleoContract.text(this, name)
    private fun JSONObject.readFlag(name: String): Boolean = GleoContract.flag(this, name)

    private fun respond(
        context: Context, status: String, message: String, reason: String,
        places: List<String> = emptyList()
    ) {
        val details = JSONObject()
        if (places.isNotEmpty()) {
            details.put("resultCount", places.size).put("placeNames", JSONArray(places.take(3)))
        }
        GleoContract.reply(context, ACTION, status, message, reason, details)
    }
}
