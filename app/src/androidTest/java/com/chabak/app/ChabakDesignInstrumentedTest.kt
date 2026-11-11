package com.chabak.app

import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChabakDesignInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun gleoPanelDoesNotPresentAnUnverifiedListeningSession() {
        compose.setContent { GleoInformation() }
        compose.onNodeWithText("Gleo 음성 연결 미검증").assertExists()
        compose.onNodeWithText("녹음 시작").assertDoesNotExist()
    }

    @Test fun restrictedRailCannotOpenParkedOnlyScreens() {
        var opened = ""
        compose.setContent { ChabakNavigationRail("home", false) { opened = it } }
        compose.onNodeWithContentDescription("메뉴 환경 조성").performClick()
        compose.runOnIdle { assertEquals("", opened) }
        compose.onNodeWithContentDescription("메뉴 스마트 에너지").performClick()
        compose.runOnIdle { assertEquals("energy", opened) }
    }

    @Test fun nodpiBackgroundKeepsItsOriginalPixelsAtTheDeviceDensity() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val background = BitmapFactory.decodeResource(resources, R.drawable.chabak2_bg_day)
        try {
            assertEquals(1672, background.width)
            assertEquals(941, background.height)
            assertTrue(background.allocationByteCount < 7 * 1024 * 1024)
        } finally { background.recycle() }
    }
}
