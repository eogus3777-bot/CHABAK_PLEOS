package com.chabak.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MarketCalendarTest {
    @Test fun crossesMonthEndsWithoutInventingTheThirtyFirst() {
        val days = setOf(1, 6, 11, 16, 21, 26)
        assertEquals(LocalDate.of(2026, 12, 1), MarketCalendar.nextDate(days, LocalDate.of(2026, 11, 27)))
        assertEquals(LocalDate.of(2027, 1, 1), MarketCalendar.nextDate(days, LocalDate.of(2026, 12, 31)))
        assertEquals(LocalDate.of(2026, 12, 31), MarketCalendar.nextDate(setOf(31), LocalDate.of(2026, 11, 1)))
    }
    @Test fun handlesLeapDaysAndMissingSchedules() {
        assertEquals(LocalDate.of(2028, 2, 29), MarketCalendar.nextDate(setOf(29), LocalDate.of(2028, 2, 28)))
        assertEquals(LocalDate.of(2027, 3, 29), MarketCalendar.nextDate(setOf(29), LocalDate.of(2027, 2, 28)))
        assertNull(MarketCalendar.nextDate(emptySet(), LocalDate.of(2026, 11, 8)))
    }
    @Test fun weekendOnSundayDoesNotJumpToTheFollowingWeek() {
        val sunday = LocalDate.of(2026, 11, 8)
        assertEquals(VisitWindow(sunday), MarketCalendar.thisWeekend(sunday))
        assertEquals(VisitWindow(sunday.minusDays(1), sunday), MarketCalendar.thisWeekend(sunday.minusDays(4)))
        assertFalse(MarketCalendar.matches(setOf(7), MarketCalendar.thisWeekend(sunday)))
        assertTrue(MarketCalendar.matches(setOf(8), MarketCalendar.thisWeekend(sunday)))
    }
    @Test fun distanceUsesLatitudeThenLongitudeAndIsSymmetric() {
        val a = TravelPoint(37.0, 127.0)
        val b = TravelPoint(38.0, 127.0)
        assertEquals(111.195, distanceKilometers(a, b), 0.005)
        assertEquals(distanceKilometers(a, b), distanceKilometers(b, a), 0.000001)
        assertEquals(0.0, distanceKilometers(a, a), 0.0)
        assertTrue(distanceKilometers(a, TravelPoint(37.0, 128.0)) < 90.0)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidDay() {
        MarketCalendar.nextDate(setOf(0, 32), LocalDate.of(2026, 11, 8))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsAnUnboundedDateRange() {
        VisitWindow(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 12, 8))
    }
}
