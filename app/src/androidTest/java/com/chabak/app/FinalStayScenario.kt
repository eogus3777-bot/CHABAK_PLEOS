package com.chabak.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** Optional live UI extension of the existing isolated-stay fixture. */
internal object FinalStayScenario {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    fun run(context: Context, store: TripStore, fixtureId: String?, gate: TravelUxGate,
            originalLighting: AmbientLightState) = runBlocking {
        val tripId = requireNotNull(fixtureId)
        val folder = File(context.filesDir, "qa-final").apply { mkdirs() }
        val evidence = JSONObject().put("transport", "installed Gleo Intent followed by real UI controls")
            .put("humanSpeechVerified", false).put("originalStateRestored", false)
        fun save() { folder.resolve("final-stay-scenario.json").writeText(evidence.toString(2)) }
        val client = PleosAmbientLight(context)
        val before = originalLighting.zones.first { it.brightness != null && it.rgb != null }
        val originalColor = client.colors.first { it.rgb == before.rgb }
        val prefs = context.getSharedPreferences("chabak_3", Context.MODE_PRIVATE)
        val scene = AmbienceRules.sceneAt(LocalDateTime.now(ZoneId.of("Asia/Seoul")).hour)
        save()
        try {
            // The pre-existing opt-in authorizes the same parked setup for Gleo and UI entry.
            awaitLighting(client, scene.ambientPreset())
            evidence.put("gleoStartWithPriorOptInAppliedLighting", true)
            screenshot(folder, "stay-gleo-auto.png")
            save()

            click("▶")
            waitFor("Nature audio playing") { hasText("재생 중") }
            delay(1_200)
            val active = context.getSystemService(AudioManager::class.java).isMusicActive
            assertTrue("Android audio output must be active", active)
            click("Ⅱ")
            waitFor("Nature audio paused") { hasText("일시정지") }
            click("■")
            waitFor("Nature audio stopped") { hasText("정지") }
            evidence.put("natureAudio", JSONObject().put("play", true).put("audioManagerActive", active)
                .put("pause", true).put("stop", true))
            save()

            click("스마트 모닝")
            screenshot(folder, "morning-active.png")
            if (context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
                click("예약 저장")
                waitFor("Morning alarm scheduled") { prefs.getLong("chabak_alarm_at", 0L) > System.currentTimeMillis() }
                val regularAlarm = prefs.getLong("chabak_alarm_at", 0L)
                click("1분 뒤 시험 알람")
                waitFor("Test alarm scheduled") { prefs.getLong("chabak_alarm_test_at", 0L) > System.currentTimeMillis() }
                assertEquals("Test alarm must preserve the regular alarm", regularAlarm, prefs.getLong("chabak_alarm_at", 0L))
                screenshot(folder, "morning-scheduled.png")
                click("예약 취소")
                waitFor("Morning alarms cancelled") {
                    prefs.getLong("chabak_alarm_at", 0L) == 0L && prefs.getLong("chabak_alarm_test_at", 0L) == 0L
                }
                evidence.put("morning", JSONObject().put("schedule", true).put("testPreservesRegular", true)
                    .put("cancel", true).put("firing", "NOT TESTED"))
            } else evidence.put("morning", JSONObject().put("status", "BLOCKED").put("reason", "Notification permission"))
            save()

            click("메뉴 환경 조성")
            val scenes = JSONArray()
            for (selected in TimeScene.entries) {
                click(selected.nameLabel)
                val actual = awaitLighting(client, selected.ambientPreset())
                screenshot(folder, "scene-${selected.name.lowercase()}.png")
                scenes.put(JSONObject().put("scene", selected.name).put("brightness", actual.brightness)
                    .put("rgb", actual.rgb).put("verified", true))
            }
            evidence.put("sceneLighting", scenes)
            click("자동 테마")
            awaitLighting(client, scene.ambientPreset())
            save()

            click("메뉴 차박 중")
            click("차박 종료")
            waitFor("First stay ended") { !store.loadOrMigrate { error("Existing fixture") }.activeTrip.diary.inProgress }
            client.apply(before.key, before.brightness, originalColor) { if (gate.isParked()) null else "P 상태 확인 필요" }
            click("메뉴 차박 중")
            click("차박 시작")
            waitFor("UI stay starts once") {
                val trip = store.loadOrMigrate { error("Existing fixture") }.activeTrip
                trip.tripId == tripId && trip.diary.inProgress && trip.diary.sessions.size == 2
            }
            awaitLighting(client, scene.ambientPreset())
            evidence.put("uiStartWithOptInAppliedLighting", true)
            screenshot(folder, "stay-ui-auto.png")
            save()

            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val activity = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED).flatMap {
                    monitor.getActivitiesInStage(it)
                }.filterIsInstance<MainActivity>().distinct().single()
                activity.recreate()
            }
            waitFor("Activity recreated") { hasText("메뉴 차박 중") }
            click("메뉴 차박 중")
            waitFor("Stay restored after recreation") { hasText("차박 종료") }
            assertEquals(2, store.loadOrMigrate { error("Existing fixture") }.activeTrip.diary.sessions.size)
            evidence.put("recreationPreservedStayAndSessionCount", true)
            evidence.put("status", "PASS")
            save()
        } finally {
            // This fixture is entered only when no user alarm exists. Cancel its two scheduled intents.
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            for ((requestCode, action) in listOf(4101 to "com.chabak.app.MORNING_RING", 4111 to "com.chabak.app.MORNING_TEST")) {
                val pending = PendingIntent.getBroadcast(context, requestCode,
                    Intent(context, ChabakMorningReceiver::class.java).setAction(action),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                if (pending != null) alarmManager.cancel(pending)
            }
            val result = client.apply(before.key, before.brightness, originalColor) {
                if (gate.isParked()) null else "P 상태 확인 필요"
            }
            val restored = client.read().zones.firstOrNull { it.key == before.key }
            evidence.put("lightingRestored", restored?.brightness == before.brightness && restored?.rgb == before.rgb)
                .put("lightingRestoreMessage", result.message)
            save()
            assertTrue("Original lighting must be restored", evidence.getBoolean("lightingRestored"))
        }
    }

    private suspend fun awaitLighting(client: PleosAmbientLight, preset: AmbientPreset): AmbientZone {
        repeat(35) {
            val actual = client.read().zones.firstOrNull { it.brightness == preset.brightness && it.rgb == preset.color.rgb }
            if (actual != null) return actual
            delay(150)
        }
        error("Expected measured lighting ${preset.brightness} / ${preset.color}")
    }

    private fun roots(): List<AccessibilityNodeInfo> = automation.windows.mapNotNull { it.root }
        .ifEmpty { listOfNotNull(automation.rootInActiveWindow) }

    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.packageName?.toString() == "com.chabak.app" &&
                (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> walk(child)?.let { return it } }
            return null
        }
        return roots().firstNotNullOfOrNull { walk(it) }
    }

    private fun hasText(label: String) = find(label) != null

    private fun click(label: String) {
        waitFor("Visible control $label") { hasText(label) }
        var node = find(label) ?: error(label)
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Click failed for $label" }
        SystemClock.sleep(180)
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(120)
        }
        error(message)
    }

    private fun screenshot(folder: File, name: String) {
        val bitmap = automation.takeScreenshot() ?: error("Screenshot unavailable")
        try { folder.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
