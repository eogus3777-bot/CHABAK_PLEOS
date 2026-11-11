package com.chabak.app

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AmbientLightInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun permissionFailureIsVisible() = runBlocking {
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
        }
        val state = PleosAmbientLight(denied).read()
        assertTrue(state.zones.isEmpty())
        assertTrue(state.status.contains("권한"))
    }

    @Test fun deniedGuardPreventsAnySdkWrite() = runBlocking {
        val result = PleosAmbientLight(context).apply(null, 50, AmbientColor.BLUE) { "사용자 확인 필요" }
        assertEquals("사용자 확인 필요", result.message)
        assertEquals(0, result.verifiedChanges)
    }

    @Test fun readSupportedSdkState() = runBlocking {
        val state = PleosAmbientLight(context).read()
        save("lights-read.json", stateJson(state))
        assumeTrue("Live ambient data unavailable: ${state.status}", state.zones.isNotEmpty())
        assertTrue(state.zones.any { it.brightness != null || it.rgb != null })
        state.zones.forEach { zone ->
            zone.brightness?.let { assertTrue(it in 0..100) }
            zone.rgb?.let { assertTrue(it in 0..0xFFFFFF) }
        }
    }

    @Test fun liveWriteReadbackAndRestore() = runBlocking {
        assumeTrue("Requires the explicit emulator lighting-write QA run",
            InstrumentationRegistry.getArguments().getString("liveLights") == "true")
        assumeTrue("This live test targets the Pleos emulator", android.os.Build.HARDWARE == "ranchu")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val gate = TravelUxGate(context)
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync { gate.start { if (it == TravelInteraction.ALLOWED) ready.countDown() } }
        val client = PleosAmbientLight(context)
        try {
            assumeTrue("Parked UX state required", ready.await(4, TimeUnit.SECONDS) && gate.isParked())
            val original = client.read()
            val zone = original.zones.firstOrNull { it.brightnessWritable && it.brightness != null && it.rgb != null }
            assumeTrue("Writable measured ambient zone required", zone != null)
            zone!!
            val originalColor = client.colors.firstOrNull { it.rgb == zone.rgb }
            assumeTrue("Known color required for exact restoration", originalColor != null)
            val targetBrightness = (if (zone.brightness == 33) 46 else 33).coerceIn(zone.limits!!)
            val targetColor = client.colors.first { it.rgb != zone.rgb }
            val evidence = JSONObject().put("before", stateJson(original)).put("restored", false)
            save("lights-write.json", evidence)
            try {
                val result = client.apply(zone.key, targetBrightness, targetColor) {
                    if (gate.isParked()) null else "P 상태 확인 필요"
                }
                val actual = client.read()
                evidence.put("result", result.message).put("verifiedChanges", result.verifiedChanges)
                    .put("targetBrightness", targetBrightness).put("targetColor", targetColor.rgb)
                    .put("after", stateJson(actual))
                save("lights-write.json", evidence)
                assertEquals(result.message, 2, result.verifiedChanges)
                val returned = actual.zones.first { it.key == zone.key }
                assertEquals(targetBrightness, returned.brightness)
                assertEquals(targetColor.rgb, returned.rgb)
            } finally {
                val result = client.apply(zone.key, zone.brightness, originalColor) {
                    if (gate.isParked()) null else "P 상태 확인 필요"
                }
                val restored = client.read().zones.firstOrNull { it.key == zone.key }
                val matches = restored?.brightness == zone.brightness && restored?.rgb == zone.rgb
                evidence.put("restored", matches).put("restoreResult", result.message)
                save("lights-write.json", evidence)
                assertTrue("Original lighting must be restored", matches)
            }
        } finally { instrumentation.runOnMainSync { gate.close() } }
    }

    private fun stateJson(state: AmbientLightState) = JSONObject().put("status", state.status)
        .put("autoByDriveMode", state.driveModeAuto ?: JSONObject.NULL)
        .put("zones", JSONArray(state.zones.map { zone -> JSONObject()
            .put("area", zone.key).put("brightness", zone.brightness ?: JSONObject.NULL)
            .put("rgb", zone.rgb ?: JSONObject.NULL).put("min", zone.limits?.first ?: JSONObject.NULL)
            .put("max", zone.limits?.last ?: JSONObject.NULL).put("brightnessWritable", zone.brightnessWritable)
            .put("colorWritable", zone.colorWritable) }))

    private fun save(name: String, data: JSONObject) {
        File(context.filesDir, "qa-final").apply { mkdirs() }.resolve(name).writeText(data.toString(2))
    }
}
