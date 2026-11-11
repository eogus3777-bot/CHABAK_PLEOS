package com.chabak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Real installed-Activity Intent test; not a Gleo spoken-command test. */
@RunWith(AndroidJUnit4::class)
class GleoInstalledStayInstrumentedTest {
    @Test fun startStayUsesTheExistingFunctionOnceAndRestoresTheOriginalTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("chabak_3", Context.MODE_PRIVATE)
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.containsKey("repairOriginalTrip")) {
            repairInterruptedProbe(context, arguments.getString("repairOriginalTrip")!!,
                arguments.getString("repairTemporaryTrip")!!)
            return
        }
        val finalScenario = arguments.getString("finalScenario") == "true"
        val keys = setOf("morning_stay_trip", "morning_alarm_trip", "morning_test_trip", "morning_last_trip",
            "chabak_alarm_at", "chabak_alarm_test_at") + if (finalScenario) setOf(
            "autoStayLights", "natureThemeIndex", "natureSound", "natureVolume", "genre", "wakeHour", "wakeMinute",
            "wakeSound", "morningFade", "morningLevel", "sunriseForegroundOptIn", "morningEnabled") else emptySet()
        val beforePrefs = prefs.all.filterKeys { it in keys }
        // Never cancel a user's active stay or scheduled morning alarm for this test.
        assumeTrue("An existing stay or morning alarm must be left untouched",
            prefs.getString("morning_stay_trip", null) == null &&
                prefs.getLong("chabak_alarm_at", 0L) == 0L && prefs.getLong("chabak_alarm_test_at", 0L) == 0L)
        val store = TripStore(context)
        var fixtureId: String? = null
        val gate = TravelUxGate(context)
        val replies = LinkedBlockingQueue<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("packageName") != context.packageName) return
                val raw = intent.getStringExtra("response") ?: return
                if (JSONObject(raw).optString("functionName") == GleoTripAction.MANAGE) replies.offer(raw)
            }
        }
        context.registerReceiver(receiver, IntentFilter(GleoContract.RESPONSE_ACTION), Context.RECEIVER_EXPORTED)
        val original = store.loadOrMigrate { error("The installed app must already have its normal trip") }
        try {
            assumeTrue("An existing recorded stay must be left untouched", original.trips.none { it.diary.inProgress })
            val ready = CountDownLatch(1)
            instrumentation.runOnMainSync {
                gate.start { if (it == TravelInteraction.ALLOWED) ready.countDown() }
            }
            assumeTrue("A real permitted interaction state is required", ready.await(4, TimeUnit.SECONDS))
            val journalFolder = File(context.filesDir, "qa-gleo-only").apply { mkdirs() }
            val journal = File(journalFolder, "stay-test-journal.json")
            val journalData = JSONObject().put("originalTripId", original.activeTripId)
                .put("preferences", JSONObject(beforePrefs)).put("restored", false)
            journal.writeText(journalData.toString(2))
            fixtureId = store.createTrip(original.activeTripId).activeTripId
            val beforeLighting = if (finalScenario) runBlocking { PleosAmbientLight(context).read() } else null
            if (finalScenario) assertTrue(prefs.edit().putBoolean("autoStayLights", true).commit())
            journalData.put("temporaryTripId", fixtureId)
            journal.writeText(journalData.toString(2))
            assertFalse(original.trips.any { it.tripId == fixtureId })
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            instrumentation.waitForIdleSync()

            fun start(): JSONObject {
                replies.clear()
                instrumentation.runOnMainSync {
                    context.startActivity(Intent(GleoTripAction.MANAGE).setClass(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("operation", "START_STAY"))
                }
                return JSONObject(replies.poll(7, TimeUnit.SECONDS) ?: error("The installed Activity did not reply"))
            }
            fun details(reply: JSONObject) = JSONArray(reply.getString("data")).getJSONObject(0)
            var first = start()
            // The Activity's own read-only UX service may finish connecting after the separate gate.
            repeat(8) {
                if (details(first).getString("reason") == "VEHICLE_RESTRICTED") {
                    SystemClock.sleep(200)
                    first = start()
                }
            }
            assertEquals("success", first.getString("status"))
            assertEquals("STARTED", details(first).getString("reason"))
            assertFalse(details(first).getBoolean("vehicleControlPerformed"))
            val recorded = store.loadOrMigrate { error("Existing database") }.activeTrip
            assertEquals(fixtureId, recorded.tripId)
            assertTrue(recorded.diary.inProgress)
            assertEquals(1, recorded.diary.sessions.size)
            val second = start()
            assertEquals("ALREADY_STARTED", details(second).getString("reason"))
            assertEquals(1, store.loadOrMigrate { error("Existing database") }.activeTrip.diary.sessions.size)
            val proof = JSONObject().put("spokenVoiceTest", false).put("transport", "installed MainActivity Intent and Android receiver")
                .put("temporaryTrip", true).put("vehicleControlPerformed", false)
                .put("first", first).put("repeated", second)
            val folder = File(context.filesDir, "qa-gleo-only").apply { mkdirs() }
            File(folder, "start-stay.json").writeText(proof.toString(2))
            if (finalScenario) FinalStayScenario.run(context, store, fixtureId, gate, beforeLighting!!)
        } finally {
            // AAOS may pause the Activity after a stay; cleanup must not depend on Scenario.close().
            val closeFailure = runCatching {
                instrumentation.runOnMainSync {
                    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                    listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED).flatMap {
                        monitor.getActivitiesInStage(it)
                    }.filterIsInstance<MainActivity>().distinct().forEach { it.finishAndRemoveTask() }
                    gate.close()
                }
                instrumentation.waitForIdleSync()
            }.exceptionOrNull()
            try {
                context.unregisterReceiver(receiver)
                fixtureId?.let { temporary ->
                // Delete only the newly generated test row, after restoring the original selection.
                check(original.trips.none { it.tripId == temporary })
                store.openTrip(original.activeTripId)
                assertEquals(1, store.writableDatabase.delete("trips", "trip_id = ?", arrayOf(temporary)))
                val editor = prefs.edit()
                for (key in keys) {
                    when (val value = beforePrefs[key]) {
                        null -> editor.remove(key)
                        is String -> editor.putString(key, value)
                        is Long -> editor.putLong(key, value)
                        is Int -> editor.putInt(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        else -> error("Unexpected existing morning preference type")
                    }
                }
                assertTrue(editor.commit())
                val restored = store.loadOrMigrate { error("Original database must remain") }
                assertEquals(original.activeTripId, restored.activeTripId)
                assertEquals(original.trips.toSet(), restored.trips.toSet())
                assertEquals(beforePrefs, prefs.all.filterKeys { it in keys })
                val journal = File(context.filesDir, "qa-gleo-only/stay-test-journal.json")
                journal.writeText(JSONObject(journal.readText()).put("restored", true).toString(2))
                val proof = File(context.filesDir, "qa-gleo-only/start-stay.json")
                if (proof.exists()) proof.writeText(JSONObject(proof.readText()).put("originalStateRestored", true).toString(2))
                val scenarioProof = File(context.filesDir, "qa-final/final-stay-scenario.json")
                if (finalScenario && scenarioProof.exists()) scenarioProof.writeText(
                    JSONObject(scenarioProof.readText()).put("originalStateRestored", true).toString(2))
                }
            } finally {
                store.close()
            }
            closeFailure?.let { throw it }
        }
    }

    /** Recovery is restricted to a uniquely matching temporary copy with exactly one test stay. */
    private fun repairInterruptedProbe(context: Context, originalId: String, temporaryId: String) {
        check(originalId != temporaryId)
        val prefs = context.getSharedPreferences("chabak_3", Context.MODE_PRIVATE)
        TripStore(context).use { store ->
            val catalog = store.loadOrMigrate { error("Existing database required") }
            val original = catalog.trips.single { it.tripId == originalId }
            val temporary = catalog.trips.single { it.tripId == temporaryId }
            check(temporary.diary.inProgress && temporary.diary.sessions.size == 1)
            check(!original.diary.inProgress && prefs.getString("morning_stay_trip", null) == temporaryId)
            check(prefs.getLong("chabak_alarm_at", 0L) == 0L && prefs.getLong("chabak_alarm_test_at", 0L) == 0L)
            val matches = catalog.trips.filter { it.tripId != temporaryId && it.place == temporary.place &&
                it.visitDate == temporary.visitDate && it.people == temporary.people && it.nights == temporary.nights }
            check(matches.single().tripId == originalId)
            store.openTrip(originalId)
            check(store.writableDatabase.delete("trips", "trip_id = ?", arrayOf(temporaryId)) == 1)
            check(prefs.edit().remove("morning_stay_trip").commit())
            check(store.loadOrMigrate { error("Existing database") }.activeTripId == originalId)
        }
    }
}
