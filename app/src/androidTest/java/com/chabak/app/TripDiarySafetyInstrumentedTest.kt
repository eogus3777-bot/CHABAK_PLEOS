package com.chabak.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class TripDiarySafetyInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val trip = Trip(
        "isolated-ui-test",
        Place("fixture", "검증용 여행", "경기", "", R.drawable.day, "숲", ""),
        LocalDate.of(2026, 11, 7), 2, 1
    )

    @Test
    fun removesEditingAndSharingControlsWhenVehicleBecomesRestricted() {
        val allowed = mutableStateOf(true)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                TripDiaryScreen(trip, emptyList(), allowed.value, { _, _ -> true }) { allowed.value }
            }
        }
        compose.onNodeWithText("메모 저장").assertExists()
        compose.runOnIdle { allowed.value = false }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("메모 저장").assertDoesNotExist()
        compose.onNodeWithText("사진 추가 · 0/10").assertDoesNotExist()
    }

    @Test
    fun checksCurrentVehiclePermissionAgainBeforeSaving() {
        val allowed = AtomicBoolean(true)
        var saves = 0
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                TripDiaryScreen(trip, emptyList(), true, { _, _ -> saves++; true }) { allowed.get() }
            }
        }
        compose.runOnIdle { allowed.set(false) }
        compose.onNodeWithText("메모 저장").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
