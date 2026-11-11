package com.chabak.app

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class GleoResponse(
    val status: String,
    val message: String,
    val reason: String,
    val details: JSONObject = JSONObject()
)

internal object GleoContract {
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    val actions = setOf(GleoCampingAction.ACTION, GleoTripAction.SEARCH, GleoTripAction.MANAGE)
    private val parameterNames = mapOf(
        GleoCampingAction.ACTION to listOf("region", "district", "nature", "toilet", "electric", "store"),
        GleoTripAction.SEARCH to listOf("category", "date", "weekend"),
        GleoTripAction.MANAGE to listOf("operation")
    )

    // The public interface has no documented request ID. Only coalesce identical in-flight inputs.
    fun fingerprint(intent: Intent): String? = runCatching {
        val names = parameterNames.getValue(intent.action!!)
        val json = parameters(intent, names)
        intent.action + names.joinToString { name ->
            val value = if (name in setOf("toilet", "electric", "store", "weekend")) flag(json, name)
                else text(json, name)
            JSONArray().put(name).put(value).toString()
        }
    }.getOrNull()

    fun parameters(intent: Intent, names: List<String>): JSONObject {
        val extras = intent.extras ?: return JSONObject()
        if (!extras.containsKey("ACTION_PARAM")) return JSONObject().apply {
            for (name in names) {
                @Suppress("DEPRECATION")
                val value = extras.get(name)
                if (extras.containsKey(name)) put(name, value ?: JSONObject.NULL)
            }
        }
        @Suppress("DEPRECATION")
        val raw = extras.get("ACTION_PARAM") as? String ?: throw IllegalArgumentException("Expected parameter text")
        require(raw.length <= 4096)
        val parser = JSONTokener(raw)
        val first = parser.nextValue()
        require(parser.nextClean() == 0.toChar())
        return when (first) {
            is JSONObject -> first
            is String -> {
                val inner = JSONTokener(first)
                val value = inner.nextValue()
                require(value is JSONObject && inner.nextClean() == 0.toChar())
                value
            }
            else -> throw IllegalArgumentException("Expected parameter object")
        }
    }

    fun text(json: JSONObject, name: String): String {
        if (!json.has(name)) return ""
        return (json.get(name) as? String ?: throw IllegalArgumentException("Expected text")).trim().also { require(it.length <= 256) }
    }

    fun flag(json: JSONObject, name: String): Boolean {
        if (!json.has(name)) return false
        return json.get(name) as? Boolean ?: throw IllegalArgumentException("Expected flag")
    }

    fun reply(context: Context, function: String, status: String, message: String, reason: String, details: JSONObject = JSONObject()) {
        require(function in actions && status in setOf("success", "asking", "fail"))
        val payload = JSONObject()
            .put("functionName", function).put("status", status).put("message", message)
            .put("mode", "voice")
            .put("data", JSONArray().put(JSONObject(details.toString()).put("reason", reason)).toString())
        runCatching {
            context.sendBroadcast(Intent(RESPONSE_ACTION).apply {
                putExtra("packageName", context.packageName)
                putExtra("response", payload.toString())
            })
        }.onSuccess {
            Log.i("ChabakGleo", "$function status=$status reason=$reason")
        }.onFailure {
            Log.e("ChabakGleo", "$function callback_transport_failed")
        }
    }

    fun reply(context: Context, function: String, response: GleoResponse) =
        reply(context, function, response.status, response.message, response.reason, response.details)
}
