package com.chabak.app

internal data class TripEnergyPlan(
    val overnightHours: Int = 8,
    val minimumPercent: Int = 30,
    val estimatedPowerKw: Double? = null
) {
    init {
        require(overnightHours in hours && minimumPercent in 1..100)
        require(estimatedPowerKw == null || estimatedPowerKw in 0.0..MAX_POWER_KW)
    }

    fun availablePercent(soc: Int?): Int? =
        soc?.takeIf { it in 0..100 }?.let { (it - minimumPercent).coerceAtLeast(0) }

    // Only call with a verified power input, or with an explicitly simulated scenario.
    fun estimate(soc: Int?, capacityKwh: Double?): StayEnergyEstimate? {
        val powerKw = estimatedPowerKw ?: return null
        val use = powerKw * overnightHours
        if (soc == null || soc !in 0..100 || capacityKwh == null || !capacityKwh.isFinite() || capacityKwh <= 0) {
            return null
        }
        val endKwh = capacityKwh * (soc / 100.0) - use
        val minimumKwh = capacityKwh * (minimumPercent / 100.0)
        return StayEnergyEstimate(
            useKwh = use,
            endPercent = (endKwh / capacityKwh * 100).coerceAtLeast(0.0),
            minimumMarginKwh = endKwh - minimumKwh,
            hoursUntilMinimum = if (powerKw > 0) {
                ((capacityKwh * (soc / 100.0) - minimumKwh).coerceAtLeast(0.0) / powerKw).takeIf(Double::isFinite)
            } else null
        )
    }

    fun warnsAt(soc: Int?): Boolean = soc != null && soc in 0..minimumPercent

    companion object {
        val hours = listOf(6, 8, 10)
        const val MAX_POWER_KW = 50.0
    }
}

internal data class StayEnergyEstimate(
    val useKwh: Double,
    val endPercent: Double,
    val minimumMarginKwh: Double,
    val hoursUntilMinimum: Double? = null
)
