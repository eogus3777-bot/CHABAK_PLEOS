package com.chabak.app

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object GleoContract {
    const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    fun parameters(intent: Intent, names: List<String>): JSONObject {
        val extras = intent.extras ?: return JSONObject()
        if (!extras.containsKey("ACTION_PARAM")) return JSONObject().apply {
            for (name in names) {
                @Suppress("DEPRECATION")
                val value = extras.get(name)
                if (value != null) put(name, value)
            }
        }
        val raw = requireNotNull(extras.getString("ACTION_PARAM"))
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
        val payload = JSONObject()
            .put("functionName", function).put("status", status).put("message", message)
            .put("data", JSONArray().put(details.put("reason", reason)).toString())
        context.sendBroadcast(Intent(RESPONSE_ACTION).apply {
            putExtra("packageName", context.packageName)
            putExtra("response", payload.toString())
        })
        Log.i("ChabakGleo", "$function status=$status reason=$reason")
    }
}
