package com.chabak.app

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

internal enum class TravelInteraction { ALLOWED, RESTRICTED, UNKNOWN }

internal fun travelInteraction(automotive: Boolean, requiresDistractionOptimization: Boolean?): TravelInteraction =
    when {
        !automotive -> TravelInteraction.ALLOWED
        requiresDistractionOptimization == null -> TravelInteraction.UNKNOWN
        requiresDistractionOptimization -> TravelInteraction.RESTRICTED
        else -> TravelInteraction.ALLOWED
    }

internal class TravelUxGate(private val context: Context) : AutoCloseable {
    private val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var active = false

    fun start(onChange: (TravelInteraction) -> Unit) {
        active = true
        onChange(travelInteraction(automotive, null))
        if (!automotive) return
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { service, ready ->
                if (!active) return@createCar
                manager?.let { runCatching { it.unregisterListener() } }
                manager = null
                if (ready) {
                    try {
                        val restrictions = service.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        restrictions?.registerListener { state: CarUxRestrictions ->
                            if (active) onChange(travelInteraction(true, state.isRequiresDistractionOptimization))
                        }
                        manager = restrictions
                    } catch (_: RuntimeException) {
                        manager = null
                    }
                }
                onChange(current())
            }
        } catch (_: RuntimeException) {
            onChange(TravelInteraction.UNKNOWN)
        } catch (_: LinkageError) {
            onChange(TravelInteraction.UNKNOWN)
        }
    }

    fun current(): TravelInteraction {
        if (!active) return TravelInteraction.UNKNOWN
        val restriction = try { manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization }
            catch (_: RuntimeException) { null }
        return travelInteraction(automotive, restriction)
    }

    override fun close() {
        active = false
        manager?.let { runCatching { it.unregisterListener() } }
        manager = null
        car?.let { runCatching { it.disconnect() } }
        car = null
    }
}
