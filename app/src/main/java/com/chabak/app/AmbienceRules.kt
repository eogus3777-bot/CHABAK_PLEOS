package com.chabak.app

import java.time.Duration
import java.time.LocalDateTime

internal enum class TimeScene(val nameLabel: String, val greeting: String) {
    MORNING("아침", "좋은 아침이에요 ☀️"),
    DAY("낮", "오늘도 즐거운 차박 되세요!"),
    SUNSET("노을", "아름다운 저녁이에요 🌇"),
    NIGHT("밤", "편안한 밤 보내세요 🌙");

    val index: Int get() = ordinal
}

internal data class AmbienceWeather(
    val region: String, val observedAt: LocalDateTime, val temperature: Int, val label: String
) {
    fun matches(region: String, now: LocalDateTime): Boolean =
        this.region == region && region != "전국" &&
            Duration.between(observedAt, now) in Duration.ZERO..Duration.ofMinutes(90)
}

internal data class AmbienceRecommendation(val scene: TimeScene, val sound: String, val reason: String)

internal object AmbienceRules {
    fun sceneAt(hour: Int): TimeScene = when (hour) {
        in 5..10 -> TimeScene.MORNING
        in 11..16 -> TimeScene.DAY
        in 17..19 -> TimeScene.SUNSET
        else -> TimeScene.NIGHT
    }

    fun recommend(
        now: LocalDateTime,
        region: String,
        terrain: Set<String>,
        weather: AmbienceWeather?
    ): AmbienceRecommendation {
        val scene = sceneAt(now.hour)
        val season = when (now.monthValue) {
            in 3..5 -> "봄"
            in 6..8 -> "여름"
            in 9..11 -> "가을"
            else -> "겨울"
        }
        val current = weather?.takeIf { it.matches(region, now) }
        val (sound, context) = when {
            current?.label in setOf("비", "이슬비", "뇌우") -> "빗소리" to "확인된 기상 모델 강수"
            terrain.any { it in setOf("해변", "바다", "해안") } -> "파도소리" to "해안 환경"
            scene == TimeScene.NIGHT || season == "겨울" -> "백색소음" to "잔잔한 휴식"
            else -> "숲소리" to "자연 속 휴식"
        }
        return AmbienceRecommendation(scene, sound, "$season ${scene.nameLabel} · $context")
    }

    fun selectedScene(manualIndex: Int, automatic: TimeScene): TimeScene =
        TimeScene.entries.getOrNull(manualIndex) ?: automatic
}
