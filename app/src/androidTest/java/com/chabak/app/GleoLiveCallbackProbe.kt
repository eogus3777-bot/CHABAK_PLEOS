package com.chabak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Receives real broadcasts from the installed activity; this is not a spoken Gleo test. */
@RunWith(AndroidJUnit4::class)
class GleoLiveCallbackProbe {
    @Test fun installedActivitySendsCallbacksToAnAndroidReceiver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val received = LinkedBlockingQueue<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra("response")?.let(received::offer)
            }
        }
        val observations = JSONArray()
        context.registerReceiver(receiver, IntentFilter(GleoContract.RESPONSE_ACTION), Context.RECEIVER_EXPORTED)
        try {
            for ((action, args) in listOf(
                GleoTripAction.SEARCH to emptyMap(),
                GleoTripAction.SEARCH to mapOf("category" to "MARKET", "date" to "2026-02-30"),
                GleoTripAction.MANAGE to mapOf("operation" to "READ_ENERGY")
            )) {
                received.clear()
                val intent = Intent(action).setClassName(context.packageName, "com.chabak.app.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                args.forEach { (key, value) -> intent.putExtra(key, value) }
                instrumentation.runOnMainSync { context.startActivity(intent) }
                val raw = received.poll(25, TimeUnit.SECONDS)
                assertNotNull("The installed activity must send a callback broadcast", raw)
                val reply = JSONObject(raw!!)
                assertEquals(action, reply.getString("functionName"))
                assertTrue(reply.get("data") is String)
                val data = JSONArray(reply.getString("data")).getJSONObject(0)
                when {
                    args.isEmpty() -> assertEquals("asking", reply.getString("status"))
                    args.containsKey("date") -> assertEquals("INVALID_PARAMETERS", data.getString("reason"))
                    data.has("stateOfChargePercent") -> {
                        assertEquals("success", reply.getString("status"))
                        assertTrue(data.getInt("stateOfChargePercent") in 0..100)
                        if (android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) {
                            assertEquals("pleos_emulator", data.getString("environment"))
                            assertTrue(reply.getString("message").contains("emulated"))
                        }
                    }
                    else -> assertEquals("fail", reply.getString("status"))
                }
                observations.put(reply)
            }
            val proof = JSONObject().put("transport", "installed activity -> Android dynamic test receiver")
                .put("spokenVoiceTest", false).put("callbacks", observations)
            val folder = File(context.filesDir, "qa-step261").apply { mkdirs() }
            File(folder, "callbacks.json").writeText(proof.toString(2))
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
