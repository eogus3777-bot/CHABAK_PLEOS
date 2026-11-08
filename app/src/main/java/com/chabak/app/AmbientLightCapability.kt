package com.chabak.app

import ai.pleos.playground.vehicle.Vehicle
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
internal suspend fun readAmbientBrightnessCapability(context: Context): String {
    val status = if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
        "미지원 환경"
    } else if (context.checkSelfPermission("pleos.car.permission.CAR_LIGHTS") != PackageManager.PERMISSION_GRANTED) {
        "조회 권한 없음"
    } else {
        var vehicle: Vehicle? = null
        try {
            val client = Vehicle(context.applicationContext)
            vehicle = client
            client.initialize()
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine { continuation ->
                    val delivered = AtomicBoolean()
                    fun finish(value: String) {
                        if (delivered.compareAndSet(false, true)) continuation.resume(value)
                    }
                    client.getLight().checkAmbientLightBrightnessCapabilities(
                        { config, areas -> finish("${config.accessType} · 영역 ${areas.size}개") },
                        { error -> finish(if (error is SecurityException) "조회 권한 거부" else "Capability 콜백 오류") }
                    )
                }
            } ?: "Capability 응답 없음"
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            "조회 권한 거부"
        } catch (_: Exception) {
            "Vehicle SDK 연결 오류"
        } catch (_: LinkageError) {
            "Vehicle SDK 호환성 확인 필요"
        } finally {
            vehicle?.let { runCatching { it.release() } }
        }
    }
    Log.i("ChabakAmbience", "ambient_brightness_capability=$status")
    return status
}
