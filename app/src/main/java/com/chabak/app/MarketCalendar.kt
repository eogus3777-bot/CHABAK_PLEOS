package com.chabak.app

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.*

internal data class VisitWindow(val start: LocalDate, val end: LocalDate = start) {
    init { require(!end.isBefore(start) && !end.isAfter(start.plusDays(6))) }
}

internal object MarketCalendar {
    fun nextDate(days: Set<Int>, from: LocalDate): LocalDate? {
        require(days.all { it in 1..31 })
        return (0L..366L).asSequence().map(from::plusDays).firstOrNull { it.dayOfMonth in days }
    }

    fun matches(days: Set<Int>, window: VisitWindow): Boolean =
        nextDate(days, window.start)?.let { !it.isAfter(window.end) } == true

    fun thisWeekend(today: LocalDate): VisitWindow {
        val offset = (DayOfWeek.SATURDAY.value - today.dayOfWeek.value + 7) % 7
        if (today.dayOfWeek == DayOfWeek.SUNDAY) return VisitWindow(today)
        val saturday = today.plusDays(offset.toLong())
        return VisitWindow(saturday, saturday.plusDays(1))
    }
}

internal fun distanceKilometers(a: TravelPoint, b: TravelPoint): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
        cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 6371.0088 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
