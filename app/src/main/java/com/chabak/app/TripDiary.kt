package com.chabak.app

import org.json.JSONArray
import org.json.JSONObject

internal const val MAX_DIARY_PHOTOS = 10

internal data class DiaryStay(
    val startedAt: Long,
    val endedAt: Long? = null,
    val campsite: String,
    val address: String
)

internal data class TripDiary(
    val note: String = "",
    val review: String = "",
    val photos: List<String> = emptyList(),
    val sessions: List<DiaryStay> = emptyList(),
    val archived: Boolean = false
) {
    val inProgress: Boolean get() = sessions.lastOrNull()?.let { it.endedAt == null } == true

    fun start(now: Long, place: Place): TripDiary =
        if (inProgress) this else copy(sessions = sessions + DiaryStay(now, campsite = place.name, address = place.address))

    fun end(now: Long): TripDiary {
        val open = sessions.lastOrNull()?.takeIf { it.endedAt == null } ?: return this
        return copy(sessions = sessions.dropLast(1) + open.copy(endedAt = now))
    }

    fun toJson(): String = JSONObject()
        .put("note", note)
        .put("review", review)
        .put("archived", archived)
        .put("photos", JSONArray(photos))
        .put("sessions", JSONArray().apply {
            sessions.forEach { stay ->
                put(JSONObject()
                    .put("start", stay.startedAt)
                    .put("end", stay.endedAt ?: JSONObject.NULL)
                    .put("campsite", stay.campsite)
                    .put("address", stay.address))
            }
        })
        .toString()

    companion object {
        fun read(raw: String): TripDiary {
            val json = JSONObject(raw)
            val photos = json.optJSONArray("photos") ?: JSONArray()
            val sessions = json.optJSONArray("sessions") ?: JSONArray()
            return TripDiary(
                note = json.optString("note"),
                review = json.optString("review"),
                archived = json.optBoolean("archived"),
                photos = List(photos.length()) { photos.getString(it) },
                sessions = List(sessions.length()) {
                    val stay = sessions.getJSONObject(it)
                    DiaryStay(
                        startedAt = stay.getLong("start"),
                        endedAt = if (stay.isNull("end")) null else stay.getLong("end"),
                        campsite = stay.optString("campsite"),
                        address = stay.optString("address")
                    )
                }
            )
        }
    }
}
