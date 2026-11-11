package com.chabak.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal enum class AmbientColor(val rgb: Int, val label: String) {
    CYAN(0x00E5FF, "청록"), BLUE(0x2979FF, "파랑"), PURPLE(0xAA00FF, "보라"), WHITE(0xFFFFFF, "흰색")
}

internal data class AmbientPreset(val brightness: Int, val color: AmbientColor)

internal fun TimeScene.ambientPreset(): AmbientPreset = when (this) {
    TimeScene.MORNING -> AmbientPreset(25, AmbientColor.WHITE)
    TimeScene.DAY -> AmbientPreset(65, AmbientColor.CYAN)
    TimeScene.SUNSET -> AmbientPreset(30, AmbientColor.PURPLE)
    TimeScene.NIGHT -> AmbientPreset(12, AmbientColor.BLUE)
}

internal data class AmbientZone(
    val area: SeatArea,
    val brightness: Int? = null,
    val rgb: Int? = null,
    val limits: IntRange? = null,
    val brightnessWritable: Boolean = false,
    val colorWritable: Boolean = false
) {
    val key: String get() = area.values.map { it.name }.sorted().joinToString("+")
    val label: String get() = area.values.joinToString(" · ") { seat ->
        val parts = seat.name.split('_')
        val side = when (parts.last()) { "LEFT" -> "왼쪽"; "RIGHT" -> "오른쪽"; else -> "가운데" }
        "${parts.getOrNull(1) ?: ""}열 $side"
    }.ifBlank { "전체" }
    val colorLabel: String? get() = rgb?.let { value ->
        AmbientColor.entries.firstOrNull { it.rgb == value }?.label ?: String.format(Locale.ROOT, "#%06X", value)
    }
}

internal data class AmbientLightState(
    val zones: List<AmbientZone> = emptyList(),
    val driveModeAuto: Boolean? = null,
    val status: String = "조명 조회 중"
)

internal data class AmbientApplyResult(val message: String, val verifiedChanges: Int = 0)

/** Uses the SDK's per-area capability and confirms each write with the matching getter. */
@SuppressLint("MissingPermission")
internal class PleosAmbientLight(private val context: Context) {
    // The SDK's color capability returns areas, without a supported RGB list. These four
    // presets were published and observed in the Virtual Console for this exact image.
    val colors: List<AmbientColor> = if (Build.HARDWARE == "ranchu" && Build.PRODUCT == "crp_avd" &&
        Build.DISPLAY.contains("release_connect_v2.1.0")) AmbientColor.entries else emptyList()

    private val operations = Mutex()

    suspend fun read(): AmbientLightState = operations.withLock {
        try {
            connected { readState(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AmbientLightState(status = error.lightMessage())
        } catch (_: LinkageError) {
            AmbientLightState(status = "Vehicle SDK 호환성 확인 필요")
        }
    }

    suspend fun apply(
        zoneKey: String?, brightness: Int?, color: AmbientColor?, guard: suspend () -> String?
    ): AmbientApplyResult = operations.withLock {
        guard()?.let { return@withLock AmbientApplyResult(it) }
        if (!hasWritePermission()) return@withLock AmbientApplyResult("차량 조명 쓰기 권한 승인 필요")
        try {
            connected { light ->
                val state = readState(light)
                val zone = if (zoneKey == null) state.zones.firstOrNull { it.brightnessWritable || it.colorWritable }
                    else state.zones.firstOrNull { it.key == zoneKey }
                if (zone == null) return@connected AmbientApplyResult(
                    if (state.zones.isEmpty()) state.status else "조명 변경 지원 범위 없음"
                )
                var confirmed = 0
                val messages = mutableListOf<String>()
                if (brightness != null) {
                    val limit = zone.limits
                    if (!zone.brightnessWritable || limit == null) {
                        messages += "밝기 변경 미지원"
                    } else {
                        val value = brightness.coerceIn(limit)
                        val result = setAndConfirm("brightness", value, zone.brightness, guard,
                            set = { ok, fail -> light.setAmbientLightBrightness(zone.area, value, { _, v -> ok(v) }, fail) },
                            get = { ok, fail -> light.getAmbientLightBrightness(zone.area, ok, fail) })
                        messages += "밝기 ${result.message}"
                        confirmed += result.verifiedChanges
                    }
                }
                if (color != null) {
                    if (!zone.colorWritable || color !in colors) {
                        messages += "색상 변경 범위 확인 필요"
                    } else if (state.driveModeAuto == true) {
                        messages += "차량 주행 모드 자동 색상 사용 중"
                    } else {
                        val result = setAndConfirm("color", color.rgb, zone.rgb, guard,
                            set = { ok, fail -> light.setAmbientLightBrightnessColor(zone.area, color.rgb, { _, v -> ok(v) }, fail) },
                            get = { ok, fail -> light.getAmbientLightBrightnessColor(zone.area, ok, fail) })
                        messages += "색상 ${result.message}"
                        confirmed += result.verifiedChanges
                    }
                }
                AmbientApplyResult(messages.joinToString(" · "), confirmed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AmbientApplyResult(error.lightMessage())
        } catch (_: LinkageError) {
            AmbientApplyResult("Vehicle SDK 호환성 확인 필요")
        }
    }

    private suspend fun setAndConfirm(
        property: String, value: Int, current: Int?, guard: suspend () -> String?,
        set: (ok: (Int?) -> Unit, fail: (Exception) -> Unit) -> Unit,
        get: (ok: (Int?) -> Unit, fail: (Exception) -> Unit) -> Unit
    ): AmbientApplyResult {
        if (value == current) return AmbientApplyResult("현재 값 유지")
        guard()?.let { return AmbientApplyResult(it) }
        val result = request(set)
        result.exceptionOrNull()?.let {
            Log.i(TAG, "write_$property=failed ${it.lightMessage()}")
            return AmbientApplyResult(it.lightMessage())
        }
        Log.i(TAG, "write_$property=callback target=$value")
        repeat(3) { attempt ->
            if (attempt > 0) delay(170)
            val readback = request(get)
            if (readback.getOrNull() == value) {
                Log.i(TAG, "readback_$property=verified value=$value")
                return AmbientApplyResult("적용 확인", 1)
            }
        }
        Log.i(TAG, "readback_$property=unverified")
        return AmbientApplyResult("변경 재조회 확인 필요")
    }

    private suspend fun readState(light: Light): AmbientLightState {
        val brightness = capability<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, fail ->
            light.checkAmbientLightBrightnessCapabilities({ config, bounds -> ok(config to bounds) }, fail)
        }
        val color = capability<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            light.checkAmbientLightBrightnessColorCapabilities({ config, areas -> ok(config to areas) }, fail)
        }
        val bright = brightness.getOrNull()?.takeIf { it.first.accessType.canRead() }
        val rgb = color.getOrNull()?.takeIf { it.first.accessType.canRead() }
        val zones = (bright?.second.orEmpty().keys + rgb?.second.orEmpty()).distinctBy { AmbientZone(it).key }
        if (zones.isEmpty()) return AmbientLightState(status = (brightness.exceptionOrNull()
            ?: color.exceptionOrNull())?.lightMessage() ?: "앰비언트 조명 미지원")

        val readings = zones.map { area ->
            val key = AmbientZone(area).key
            val bounds = bright?.second?.entries?.firstOrNull { AmbientZone(it.key).key == key }?.value
            val colorArea = rgb?.second?.firstOrNull { AmbientZone(it).key == key }
            val limits = bounds?.let {
                val min = it.minValue
                val max = it.maxValue
                if (min != null && max != null && min in 0..100 && max in min..100) min..max else null
            }
            val brightnessValue = if (bounds != null) request<Int?> { ok, fail ->
                light.getAmbientLightBrightness(area, ok, fail)
            }.getOrNull()?.takeIf { it in 0..100 } else null
            val colorValue = if (colorArea != null) request<Int?> { ok, fail ->
                light.getAmbientLightBrightnessColor(colorArea, ok, fail)
            }.getOrNull()?.takeIf { it in 0..0xFFFFFF } else null
            AmbientZone(area, brightnessValue, colorValue, limits,
                brightnessWritable = hasWritePermission() && limits != null && bright?.first?.accessType == AccessType.READ_WRITE,
                colorWritable = hasWritePermission() && colorArea != null && rgb?.first?.accessType == AccessType.READ_WRITE)
        }
        val autoCapability = request<CapabilityConfig> { ok, fail ->
            light.checkAmbientColorAutoByDriveModeEnabledCapabilities(ok, fail)
        }.getOrNull()
        Log.i(TAG, "auto_by_drive_mode_capability=${autoCapability?.accessType}")
        val driveModeAuto = if (autoCapability?.accessType?.canRead() == true) request<Boolean?> { ok, fail ->
            light.getAmbientColorAutoByDriveModeEnabled(ok, fail)
        }.getOrNull() else null
        val status = if (readings.any { it.brightness != null || it.rgb != null }) "SDK 조회" else "조명 값 조회 실패"
        Log.i(TAG, "read zones=${readings.size} brightnessAccess=${bright?.first?.accessType} colorAccess=${rgb?.first?.accessType} " +
            "brightness=${readings.map { it.brightness }} rgb=${readings.map { it.rgb }} bounds=${readings.map { it.limits }} auto=$driveModeAuto")
        return AmbientLightState(readings, driveModeAuto, status)
    }

    private suspend fun <T> connected(block: suspend (Light) -> T): T {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) throw LightFailure("차량 조명 미지원 환경")
        if (context.checkSelfPermission("pleos.car.permission.CAR_LIGHTS") != PackageManager.PERMISSION_GRANTED)
            throw LightFailure("차량 조명 권한 승인 필요")
        val client = Vehicle(context.applicationContext)
        try {
            client.initialize()
            return block(client.getLight())
        } finally {
            runCatching { client.release() }
        }
    }

    private suspend fun <T> request(call: ((T) -> Unit, (Exception) -> Unit) -> Unit): Result<T> =
        withTimeoutOrNull(1_200) {
            suspendCancellableCoroutine { continuation ->
                val delivered = AtomicBoolean()
                fun finish(result: Result<T>) {
                    if (delivered.compareAndSet(false, true)) continuation.resume(result)
                }
                try {
                    call({ finish(Result.success(it)) }, { finish(Result.failure(it)) })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    finish(Result.failure(error))
                }
            }
        } ?: Result.failure(LightFailure("조명 SDK 응답 지연"))

    private suspend fun <T> capability(call: ((T) -> Unit, (Exception) -> Unit) -> Unit): Result<T> {
        val first = request(call)
        val error = first.exceptionOrNull()
        if (error is LightFailure || error?.message?.contains("ERROR_SERVICE_CONNECTION_FAILURE") == true) {
            delay(120)
            return request(call)
        }
        return first
    }

    private fun AccessType.canRead() = this == AccessType.READ || this == AccessType.READ_WRITE

    private fun hasWritePermission() = context.checkSelfPermission("pleos.car.permission.CONTROL_CAR_LIGHTS") ==
        PackageManager.PERMISSION_GRANTED

    private fun Throwable.lightMessage(): String = when {
        this is LightFailure -> reason
        this is SecurityException || "ERROR_SECURITY_PERMISSION" in message.orEmpty() -> "조명 변경 권한 승인 필요"
        "ERROR_DRIVING_RESTRICTED" in message.orEmpty() -> "차량 조작 제한"
        "ERROR_NOT_SUPPORTED" in message.orEmpty() -> "차량 조명 미지원"
        else -> "조명 SDK 요청 실패"
    }

    private class LightFailure(val reason: String) : Exception(reason)

    private companion object { const val TAG = "ChabakLights" }
}
