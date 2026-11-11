package com.chabak.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AmbientSelectionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sdkConnectionFailureClearsPendingSelectionWithoutReportingSuccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val denyConnection = AtomicBoolean(false)
        val deniedBindObserved = AtomicBoolean(false)
        val disconnected = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
                if (denyConnection.get()) {
                    deniedBindObserved.set(true)
                    return false
                }
                return super.bindService(service, connection, flags)
            }
        }
        val controller = PleosAmbientLight(disconnected)
        val original = runBlocking { controller.read() }.zones.firstOrNull { it.colorWritable && it.rgb != null }
        assumeTrue("A measured writable emulator color is required", original != null)
        original!!
        val target = controller.colors.first { it.rgb != original.rgb }
        compose.setContent {
            Column { AmbientLightPanel(controller, TimeScene.DAY, false, {}, guard = { null }) }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(target.label).fetchSemanticsNodes().isNotEmpty()
        }
        try {
            denyConnection.set(true)
            compose.onNodeWithText(target.label).performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("조명 SDK", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(deniedBindObserved.get())
            assertTrue(compose.onAllNodesWithText(target.label).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("적용 확인", substring = true).fetchSemanticsNodes().isEmpty())
            val actual = runBlocking { PleosAmbientLight(context).read() }.zones.first { it.key == original.key }
            assertEquals(original.rgb, actual.rgb)
            val folder = File(context.filesDir, "qa-ambient-selection").apply { mkdirs() }
            folder.resolve("sdk-failure.json").writeText(JSONObject().put("status", "PASS")
                .put("bindingFailureInjected", true).put("sdkErrorDisplayed", true)
                .put("noSuccessfulSelectionDisplayed", true).put("actualRgbUnchanged", actual.rgb).toString(2))
        } finally {
            denyConnection.set(false)
        }
    }

    @Test fun pendingSelectionSurvivesRecompositionAndRevertsWhenTheWriteIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = PleosAmbientLight(context)
        val before = runBlocking { controller.read() }
        val zone = before.zones.firstOrNull { it.colorWritable && it.rgb != null }
        assumeTrue("A measured writable emulator color is required", zone != null)
        zone!!
        val original = controller.colors.firstOrNull { it.rgb == zone.rgb }
        assumeTrue("A supported starting color is required", original != null)
        original!!
        val target = controller.colors.first { it != original }
        val releaseGuard = CompletableDeferred<Unit>()
        var recompose by mutableStateOf(false)
        compose.setContent {
            Column {
                AmbientLightPanel(controller, TimeScene.DAY, recompose, {}, guard = {
                    releaseGuard.await()
                    "테스트 조작 제한"
                })
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(original.label).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(original.label).assertIsSelected()
        compose.onNodeWithText(target.label).performClick()
        controller.colors.forEach { color ->
            val button = compose.onNodeWithText(color.label).assertIsNotEnabled()
            if (color == target) button.assertIsSelected() else button.assertIsNotSelected()
        }
        compose.onNodeWithText("${target.label} 적용 중").assertExists()
        compose.onNodeWithText("${zone.brightness}% · ${zone.colorLabel}").assertExists()
        compose.runOnIdle { recompose = true }
        compose.onNodeWithText(target.label).assertIsSelected()
        releaseGuard.complete(Unit)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("테스트 조작 제한").fetchSemanticsNodes().isNotEmpty()
        }
        controller.colors.forEach { color ->
            val button = compose.onNodeWithText(color.label).assertIsEnabled()
            if (color == original) button.assertIsSelected() else button.assertIsNotSelected()
        }
        val actual = runBlocking { controller.read() }.zones.first { it.key == zone.key }
        assertEquals(zone.rgb, actual.rgb)
        val folder = File(context.filesDir, "qa-ambient-selection").apply { mkdirs() }
        folder.resolve("pending-failure.json").writeText(JSONObject()
            .put("status", "PASS").put("pendingSelectionMoved", true)
            .put("previousSelectionCleared", true).put("recompositionPreservedSelection", true)
            .put("pendingButtonsDisabled", true).put("rejectedWriteRevertedSelection", true)
            .put("actualRgbUnchanged", actual.rgb).put("guardRejectionInjected", true).toString(2))
    }
}
