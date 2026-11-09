package com.chabak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
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
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun listen(test: (LinkedBlockingQueue<String>) -> Unit) {
        val received = LinkedBlockingQueue<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("packageName") == context.packageName) {
                    intent.getStringExtra("response")?.let(received::offer)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(GleoContract.RESPONSE_ACTION), Context.RECEIVER_EXPORTED)
        try { test(received) } finally { context.unregisterReceiver(receiver) }
    }

    private fun command(action: String) = Intent(action).setClassName(context.packageName, MainActivity::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    @Test fun ordinaryLauncherAndActivityRecreationDoNotCreateVoiceCallbacks() = listen { received ->
        ActivityScenario.launch<MainActivity>(command(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)).use { scenario ->
            instrumentation.waitForIdleSync()
            assertNull(received.poll(250, TimeUnit.MILLISECONDS))
            scenario.recreate()
            instrumentation.waitForIdleSync()
            assertNull(received.poll(250, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun allThreeActionsArriveColdAndThroughOnNewIntentWithoutReplay() = listen { received ->
        val observations = JSONArray()
        for (action in GleoContract.actions) {
            received.clear()
            val started = SystemClock.uptimeMillis()
            ActivityScenario.launch<MainActivity>(command(action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                val cold = JSONObject(received.poll(7, TimeUnit.SECONDS) ?: error("Missing cold callback: $action"))
                assertEquals(action, cold.getString("functionName"))
                assertEquals("asking", cold.getString("status"))
                assertTrue(SystemClock.uptimeMillis() - started < 7_000)
                scenario.recreate()
                assertNull("A consumed command must not replay on recreation", received.poll(250, TimeUnit.MILLISECONDS))
                var original: MainActivity? = null
                scenario.onActivity { original = it }
                val warm = command(action).apply {
                    when (action) {
                        GleoCampingAction.ACTION -> putExtra("region", 123)
                        GleoTripAction.SEARCH -> { putExtra("category", "MARKET"); putExtra("date", "2026-02-30") }
                        GleoTripAction.MANAGE -> putExtra("operation", "UNSUPPORTED_CONTROL")
                    }
                }
                instrumentation.runOnMainSync { context.startActivity(warm) }
                val response = JSONObject(received.poll(7, TimeUnit.SECONDS) ?: error("Missing warm callback: $action"))
                assertEquals(action, response.getString("functionName"))
                assertEquals("fail", response.getString("status"))
                assertEquals("INVALID_PARAMETERS", JSONArray(response.getString("data")).getJSONObject(0).getString("reason"))
                scenario.onActivity { assertSame("Warm delivery must reuse MainActivity", original, it) }
                observations.put(JSONObject().put("cold", cold).put("warm", response))
            }
        }
        val folder = File(context.filesDir, "qa-gleo-integration").apply { mkdirs() }
        File(folder, "cold-warm-callbacks.json").writeText(JSONObject().put("spokenVoiceTest", false)
            .put("callbacks", observations).toString(2))
    }

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
                val raw = received.poll(7, TimeUnit.SECONDS)
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
