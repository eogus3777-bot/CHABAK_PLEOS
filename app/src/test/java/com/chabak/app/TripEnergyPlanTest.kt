package com.chabak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEnergyPlanTest {
    @Test
    fun calculatesUseRemainingReserveAndRuntimeFromKnownInputs() {
        val plan = TripEnergyPlan(8, 30, 1.2)
        val estimate = requireNotNull(plan.estimate(soc = 75, capacityKwh = 72.0))
        assertEquals(9.6, estimate.useKwh, 0.0001)
        assertEquals(61.6667, requireNotNull(estimate.endPercent), 0.0001)
        assertEquals(22.8, requireNotNull(estimate.minimumMarginKwh), 0.0001)
        assertEquals(27.0, requireNotNull(estimate.hoursUntilMinimum), 0.0001)
    }

    @Test
    fun doesNotInventBatteryEstimatesWhenMeasurementsAreMissing() {
        val plan = TripEnergyPlan(6, 40, 2.0)
        assertEquals(StayEnergyEstimate(12.0), plan.estimate(null, 72.0))
        assertEquals(StayEnergyEstimate(12.0), plan.estimate(75, null))
        assertEquals(StayEnergyEstimate(12.0), plan.estimate(75, Double.NaN))
        assertNull(TripEnergyPlan().estimate(75, 72.0))
    }

    @Test
    fun clampsEmptyBatteryAndRetainsTheEnergyShortfall() {
        val estimate = requireNotNull(TripEnergyPlan(10, 30, 2.0).estimate(20, 72.0))
        assertEquals(0.0, requireNotNull(estimate.endPercent), 0.0)
        assertEquals(-27.2, requireNotNull(estimate.minimumMarginKwh), 0.0001)
        assertEquals(0.0, requireNotNull(estimate.hoursUntilMinimum), 0.0)
        assertNull(TripEnergyPlan(10, 30, 0.0).estimate(75, 72.0)?.hoursUntilMinimum)
    }

    @Test
    fun warnsAtAndBelowTheSavedMinimumOnlyForValidSoc() {
        val plan = TripEnergyPlan(minimumPercent = 35)
        assertTrue(plan.warnsAt(35))
        assertTrue(plan.warnsAt(20))
        assertFalse(plan.warnsAt(36))
        assertFalse(plan.warnsAt(null))
        assertFalse(plan.warnsAt(-1))
    }
}
