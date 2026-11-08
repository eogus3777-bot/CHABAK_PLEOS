package com.chabak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TripDiaryInstrumentedTest {
    @Test
    fun restoresDiaryWithoutCopyingItToAnotherTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "diary-test-${UUID.randomUUID()}.db"
        val trip = Trip(
            UUID.randomUUID().toString(),
            Place("diary_test", "테스트 캠핑장", "경기", "", R.drawable.chabak_day_photo, "숲", "",
                address = "시험 주소"),
            LocalDate.of(2026, 11, 8), 2, 1,
            packedItemIds = setOf("mat"), energyPlan = TripEnergyPlan(minimumPercent = 41),
            wakeMinuteOfDay = 390
        )
        val diary = TripDiary("한 줄", "여행 후기", listOf("content://fixture/1", "content://fixture/2"))
            .start(1_000L, trip.place).end(2_000L)
        try {
            val secondId = TripStore(context, database).use { store ->
                store.loadOrMigrate { trip }
                store.updateTrip(trip.tripId) { it.copy(diary = diary) }
                val second = store.createTrip(trip.tripId).activeTrip
                assertEquals(TripDiary(), second.diary)
                store.updateTrip(second.tripId) { it.copy(diary = TripDiary(note = "다른 여행")) }
                second.tripId
            }
            TripStore(context, database).use { store ->
                assertEquals("다른 여행", store.loadOrMigrate { error("Must reopen") }.activeTrip.diary.note)
                assertEquals(trip.copy(diary = diary), store.openTrip(trip.tripId).activeTrip)
                assertEquals("다른 여행", store.openTrip(secondId).activeTrip.diary.note)
            }
        } finally {
            context.deleteDatabase(database)
        }
    }

    @Test
    fun recordsOnlyUserStayEventsAndIgnoresRepeatedActions() {
        val place = Place("diary_test", "테스트 캠핑장", "경기", "", R.drawable.chabak_day_photo, "숲", "")
        val blank = TripDiary.read("{}")
        assertEquals(blank, blank.end(900L))
        val started = blank.start(1_000L, place)
        assertTrue(started.inProgress)
        assertEquals(started, started.start(1_100L, place))
        val ended = started.end(2_000L)
        assertFalse(ended.inProgress)
        assertEquals(ended, ended.end(2_100L))
        assertEquals(listOf(1_000L, 3_000L), ended.start(3_000L, place).sessions.map { it.startedAt })
        assertEquals(ended, TripDiary.read(ended.toJson()))
    }
}
