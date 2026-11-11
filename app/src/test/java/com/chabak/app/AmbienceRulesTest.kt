package com.chabak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime

class AmbienceRulesTest {
    @Test
    fun selectsFourScenesAtTheExistingBoundaries() {
        val cases = mapOf(4 to TimeScene.NIGHT, 5 to TimeScene.MORNING, 10 to TimeScene.MORNING,
            11 to TimeScene.DAY, 16 to TimeScene.DAY, 17 to TimeScene.SUNSET,
            19 to TimeScene.SUNSET, 20 to TimeScene.NIGHT)
        cases.forEach { (hour, scene) -> assertEquals(scene, AmbienceRules.sceneAt(hour)) }
    }

    @Test
    fun usesOnlyRecentWeatherForTheSelectedTripsRegion() {
        val now = LocalDateTime.of(2026, 11, 7, 15, 0)
        val rain = AmbienceWeather("경기", now.minusMinutes(20), 9, "비")
        val coastal = setOf("바다")
        assertEquals("빗소리", AmbienceRules.recommend(now, "경기", coastal, rain).sound)
        assertEquals("파도소리", AmbienceRules.recommend(now, "강원", coastal, rain).sound)
        assertEquals("파도소리", AmbienceRules.recommend(now.plusHours(2), "경기", coastal, rain).sound)
        assertFalse(rain.copy(observedAt = now.plusMinutes(1)).matches("경기", now))
        assertFalse(rain.copy(region = "전국").matches("전국", now))
        assertEquals("백색소음", AmbienceRules.recommend(now.withMonth(12), "경기", emptySet(), null).sound)
    }

    @Test
    fun preservesManualChoiceAcrossAutomaticChanges() {
        assertEquals(TimeScene.NIGHT, AmbienceRules.selectedScene(3, TimeScene.DAY))
        assertEquals(TimeScene.NIGHT, AmbienceRules.selectedScene(3, TimeScene.MORNING))
        assertEquals(TimeScene.MORNING, AmbienceRules.selectedScene(-1, TimeScene.MORNING))
    }
}
