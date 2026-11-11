package com.chabak.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal enum class EnergyIssue(val label: String) {
    INACTIVE("조회 대기"),
    LOADING("차량 응답 대기"),
    PERMISSION("권한 승인 필요"),
    UNSUPPORTED("미지원"),
    CONNECTION("SDK 연결 실패"),
    RESTRICTED("주행 중 조회 제한"),
    UNAVAILABLE("값 확인 필요")
}

internal sealed interface EnergyRead<out T> {
    data class Value<T>(val value: T) : EnergyRead<T>
    data class Missing(val issue: EnergyIssue) : EnergyRead<Nothing>

    fun valueOrNull(): T? = (this as? Value)?.value
}

internal data class VehicleEnergy(
    val soc: EnergyRead<Int> = EnergyRead.Missing(EnergyIssue.INACTIVE),
    val capacityKwh: EnergyRead<Double> = EnergyRead.Missing(EnergyIssue.INACTIVE),
    val chargeSwitch: EnergyRead<Boolean> = EnergyRead.Missing(EnergyIssue.INACTIVE),
    val socReadAtElapsedMs: Long = 0L
)

internal class PleosEnergyReader(private val context: Context) {
    // Each protected request is reached only after checking its exact read permission.
    @SuppressLint("MissingPermission")
    suspend fun monitor(publish: (VehicleEnergy) -> Unit) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            val unsupported = EnergyRead.Missing(EnergyIssue.UNSUPPORTED)
            publish(VehicleEnergy(unsupported, unsupported, unsupported))
            return
        }
        val canReadEnergy = granted("pleos.car.permission.CAR_ENERGY")
        val canReadCapacity = granted("pleos.car.permission.CAR_INFO")
        val denied = EnergyRead.Missing(EnergyIssue.PERMISSION)
        val loading = EnergyRead.Missing(EnergyIssue.LOADING)
        var readings = VehicleEnergy(
            if (canReadEnergy) loading else denied,
            if (canReadCapacity) loading else denied,
            if (canReadEnergy) loading else denied
        )
        publish(readings)
        if (!canReadEnergy && !canReadCapacity) {
            Log.i(TAG, "read_permissions_missing")
            return
        }

        var client: Vehicle? = null
        try {
            val vehicle = Vehicle(context.applicationContext)
            client = vehicle
            vehicle.initialize()
            Log.i(TAG, "initialize_requested")
            val battery = vehicle.getEvBattery()
            coroutineScope {
                if (canReadEnergy) {
                    launch {
                        watchSoc(battery) {
                            readings = readings.copy(soc = it,
                                socReadAtElapsedMs = if (it is EnergyRead.Value) SystemClock.elapsedRealtime() else 0L)
                            publish(readings)
                        }
                    }
                    launch {
                        when (val capability = check(battery::checkEvChargeSwitchCapabilities)) {
                            is EnergyRead.Missing -> {
                                readings = readings.copy(chargeSwitch = capability)
                                publish(readings)
                            }
                            is EnergyRead.Value -> {
                                Log.i(TAG, "charge_switch_capability=readable")
                                poll({ query(battery::getEvChargeSwitchState) }) {
                                    readings = readings.copy(chargeSwitch = it)
                                    publish(readings)
                                }
                            }
                        }
                    }
                }
                if (canReadCapacity) launch {
                    poll({ readCapacity(battery) }) {
                        readings = readings.copy(capacityKwh = it)
                        publish(readings)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = EnergyRead.Missing(classify(error, EnergyIssue.CONNECTION))
            Log.i(TAG, "initialization_failed=${failure.issue}")
            publish(VehicleEnergy(
                if (canReadEnergy) failure else denied,
                if (canReadCapacity) failure else denied,
                if (canReadEnergy) failure else denied
            ))
        } catch (_: LinkageError) {
            val failure = EnergyRead.Missing(EnergyIssue.CONNECTION)
            Log.i(TAG, "initialization_failed=incompatible_sdk")
            publish(VehicleEnergy(
                if (canReadEnergy) failure else denied,
                if (canReadCapacity) failure else denied,
                if (canReadEnergy) failure else denied
            ))
        } finally {
            client?.let { runCatching { it.release() } }
            Log.i(TAG, "released")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun watchSoc(battery: EvBattery, publish: (EnergyRead<Int>) -> Unit) = coroutineScope {
        when (val capability = check(battery::checkEvBatteryStateOfChargeCapabilities)) {
            is EnergyRead.Missing -> {
                Log.i(TAG, "soc_capability=${capability.issue}")
                publish(capability)
                return@coroutineScope
            }
            is EnergyRead.Value -> Log.i(TAG, "soc_capability=readable")
        }

        val updates = Channel<EnergyRead<Int>>(Channel.CONFLATED)
        var eventsReceived = 0
        val listener = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(stateOfCharge: Int?) {
                updates.trySend(socReading(stateOfCharge))
            }

            override fun onFailed(exception: Exception) {
                // A periodic getter remains available when a listener cannot be registered.
                Log.i(TAG, "soc_subscription=${classify(exception)}")
            }
        }
        val receiver = launch {
            for (reading in updates) {
                eventsReceived++
                Log.i(TAG, "soc_change_callback=${reading is EnergyRead.Value}")
                publish(reading)
            }
        }
        var registered = false
        try {
            registered = runCatching { battery.registerEvBatteryStateOfCharge(listener) }.isSuccess
            var first = true
            while (isActive) {
                val generation = eventsReceived
                val result = when (val read = query(battery::getEvBatteryStateOfCharge)) {
                    is EnergyRead.Value -> socReading(read.value)
                    is EnergyRead.Missing -> read
                }
                if (first) {
                    Log.i(TAG, "soc_get_callback=${result is EnergyRead.Value}")
                    first = false
                }
                if (eventsReceived == generation) publish(result)
                if (result is EnergyRead.Missing &&
                    (result.issue == EnergyIssue.UNSUPPORTED || result.issue == EnergyIssue.PERMISSION)
                ) break
                delay(REFRESH_MILLIS)
            }
        } finally {
            if (registered) runCatching { battery.unregisterEvBatteryStateOfCharge(listener) }
            updates.close()
            receiver.cancel()
        }
    }

    @Suppress("DEPRECATION") // SDK 2.3.2 exposes this nominal Wh value with its own CAR_INFO gate.
    @SuppressLint("MissingPermission")
    private suspend fun readCapacity(battery: EvBattery): EnergyRead<Double> =
        when (val read = query(battery::getEvBatteryCapacity)) {
            is EnergyRead.Value -> read.value.takeIf { it.isFinite() && it > 0f }
                ?.let { EnergyRead.Value(it / 1_000.0) } ?: EnergyRead.Missing(EnergyIssue.UNAVAILABLE)
            is EnergyRead.Missing -> read
        }

    private suspend fun <T> poll(read: suspend () -> EnergyRead<T>, publish: (EnergyRead<T>) -> Unit) = coroutineScope {
        while (isActive) {
            val result = read()
            publish(result)
            if (result is EnergyRead.Missing &&
                (result.issue == EnergyIssue.UNSUPPORTED || result.issue == EnergyIssue.PERMISSION)
            ) break
            delay(REFRESH_MILLIS)
        }
    }

    private suspend fun check(
        request: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit
    ): EnergyRead<CapabilityConfig> {
        val result = query(request)
        return when {
            result is EnergyRead.Missing -> result
            result.valueOrNull()?.accessType in listOf(AccessType.READ, AccessType.READ_WRITE) -> result
            else -> EnergyRead.Missing(EnergyIssue.UNSUPPORTED)
        }
    }

    private suspend fun <T : Any> query(
        request: ((T?) -> Unit, (Exception) -> Unit) -> Unit
    ): EnergyRead<T> = try {
        withTimeout(5_000L) {
            suspendCancellableCoroutine { continuation ->
                val delivered = AtomicBoolean(false)
                fun finish(reading: EnergyRead<T>) {
                    if (delivered.compareAndSet(false, true)) continuation.resume(reading)
                }
                request(
                    { value -> finish(value?.let { EnergyRead.Value(it) } ?: EnergyRead.Missing(EnergyIssue.UNAVAILABLE)) },
                    { error -> finish(EnergyRead.Missing(classify(error))) }
                )
            }
        }
    } catch (_: TimeoutCancellationException) {
        EnergyRead.Missing(EnergyIssue.CONNECTION)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        EnergyRead.Missing(classify(error))
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun socReading(value: Int?): EnergyRead<Int> =
        value?.takeIf { it in 0..100 }?.let { EnergyRead.Value(it) } ?: EnergyRead.Missing(EnergyIssue.UNAVAILABLE)

    private fun classify(error: Exception, fallback: EnergyIssue = EnergyIssue.UNAVAILABLE): EnergyIssue {
        if (error is SecurityException) return EnergyIssue.PERMISSION
        if (error is RemoteException) return EnergyIssue.CONNECTION
        // The released AAR obfuscates VehicleException. Match only its published code names.
        return when (error.message?.trim()?.removePrefix("Error code: ")) {
            "ERROR_SECURITY_PERMISSION" -> EnergyIssue.PERMISSION
            "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA",
            "ERROR_NOT_SUPPORTED_VALUE_TYPE" -> EnergyIssue.UNSUPPORTED
            "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE" -> EnergyIssue.CONNECTION
            "ERROR_DRIVING_RESTRICTED" -> EnergyIssue.RESTRICTED
            else -> fallback
        }
    }

    private companion object {
        const val REFRESH_MILLIS = 12_000L
        const val TAG = "ChabakEnergy"
    }
}
