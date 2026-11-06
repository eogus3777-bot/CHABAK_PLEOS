package com.chabak.app

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

internal data class NavigationConfirmation(
    val completed: Boolean,
    val remainingMeters: Int?,
    val remainingSeconds: Int?,
    val waypoints: List<String>?
)

internal class PleosTripNavigation(
    context: Context,
    private val onState: (Boolean, String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var closed = false
    private var ready = false
    private var locationRequest: ((TripOrigin?) -> Unit)? = null
    private var route: PendingRoute? = null

    private class PendingRoute(val target: TravelPoint, val callback: (NavigationConfirmation?) -> Unit) {
        var started = false
        var changed = false
        var completed = false
        var destination: DestinationInfo? = null
        var requestedWaypoints = false
    }

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = dispatch {
            val point = TravelPoint.from(currentLocationInfo.latitude, currentLocationInfo.longitude)
            Log.i(TAG, "current_location_callback valid=${point != null}")
            finishLocation(point?.let {
                TripOrigin(currentLocationInfo.address.orEmpty().ifBlank { "내비 현재 위치" }, it)
            })
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = dispatch {
            val request = route ?: return@dispatch
            if (request.started) return@dispatch
            request.started = true
            Log.i(TAG, "route_started")
            call { getDestinationInfo() }
            listOf(2_000L, 5_000L).forEach { delay ->
                main.postDelayed({
                    if (route === request && !request.completed) call { getDestinationInfo() }
                }, delay)
            }
        }

        override fun onDestinationChanged() = dispatch {
            val request = route ?: return@dispatch
            request.changed = true
            Log.i(TAG, "destination_changed")
            call { getDestinationInfo() }
        }

        override fun onRoutingCompleted() = dispatch {
            val request = route ?: return@dispatch
            request.completed = true
            Log.i(TAG, "routing_completed")
            call { getDestinationInfo() }
            readWaypointsIfReady(request)
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = dispatch {
            val request = route ?: return@dispatch
            if (!(request.changed || request.completed || request.started)) return@dispatch
            val actual = TravelPoint.from(destinationInfo.latitude, destinationInfo.longitude)
            val display = TravelPoint.from(
                destinationInfo.displayLatitude ?: Double.NaN, destinationInfo.displayLongitude ?: Double.NaN
            )
            val matched = listOfNotNull(actual, display).any(request.target::matches)
            Log.i(TAG, "destination_callback matched=$matched")
            if (!matched) {
                request.destination = null
                return@dispatch
            }
            request.destination = destinationInfo
            if (request.completed) readWaypointsIfReady(request) else call { getRouteStateInfo() }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = dispatch {
            val request = route ?: return@dispatch
            if (request.destination == null || request.completed) return@dispatch
            Log.i(TAG, "route_state_callback mode=${routeStateInfo.naviMode} routing=${routeStateInfo.isRouting}")
            if (routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING && !routeStateInfo.isRouting) {
                // Some navigation versions report active guidance without the completion event.
                request.completed = true
                readWaypointsIfReady(request)
            }
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = dispatch {
            val request = route ?: return@dispatch
            if (!request.requestedWaypoints) return@dispatch
            Log.i(TAG, "waypoint_callback count=${waypointInfoList.size}")
            finishRoute(request, waypointInfoList.map { it.name.ifBlank { "이름 확인 필요" } })
        }

        override fun onError(errorCode: NaviErrorCode) = dispatch {
            if (locationRequest == null && route == null) return@dispatch
            Log.i(TAG, "navi_error=$errorCode")
            onState(ready, "내비 요청을 처리하지 못했어요. ($errorCode)")
            finishLocation(null)
            route?.let { finishRoute(it, null, reportTimeout = false) }
        }
    }

    fun start() {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            onState(false, "Pleos 내비를 사용할 수 없는 환경이에요.")
            return
        }
        if (listOf("NAVI_ROUTE", "NAVI_ROUTE_SEARCH").any {
                appContext.checkSelfPermission("pleos.car.permission.$it") != PackageManager.PERMISSION_GRANTED
            }) {
            Log.i(TAG, "navi_permission_missing")
            onState(false, "Pleos 내비 권한 확인이 필요해요.")
            return
        }
        onState(false, "Pleos 내비에 연결하는 중이에요.")
        try {
            val sdk = NaviHelper(appContext)
            helper = sdk
            sdk.initialize(
                onConnected = { dispatch {
                    sdk.addListener(listener)
                    ready = true
                    Log.i(TAG, "sdk_connected")
                    onState(true, "Pleos 내비 연결됨")
                } },
                onFailed = { error -> dispatch {
                    ready = false
                    Log.i(TAG, "sdk_initialization_failed=${error.javaClass.simpleName}")
                    onState(false, "Pleos 내비 서비스에 연결할 수 없어요.")
                } }
            )
        } catch (_: RuntimeException) {
            onState(false, "Pleos 내비 서비스에 연결할 수 없어요.")
        }
    }

    fun currentLocation(result: (TripOrigin?) -> Unit) {
        if (!ready || locationRequest != null || route != null) {
            result(null)
            return
        }
        locationRequest = result
        if (!call { getCurrentLocationInfo() }) {
            finishLocation(null)
            return
        }
        main.postDelayed({ if (locationRequest === result) finishLocation(null) }, 4_000)
    }

    fun requestDestination(name: String, address: String, point: TravelPoint, result: (NavigationConfirmation?) -> Unit) {
        if (!ready || route != null || locationRequest != null) {
            result(null)
            return
        }
        val request = PendingRoute(point, result)
        route = request
        onState(true, "목적지 설정 응답을 기다리는 중이에요.")
        val sent = call {
            requestRoute(RouteInfo(
                latitude = point.latitude, longitude = point.longitude,
                displayLatitude = null, displayLongitude = null,
                poiName = name, poiId = "", address = address, poiSubId = "0"
            ))
        }
        if (!sent) {
            finishRoute(request, null, reportTimeout = false)
            return
        }
        Log.i(TAG, "destination_requested")
        main.postDelayed({ if (route === request) finishRoute(request, null) }, 24_000)
    }

    private fun readWaypointsIfReady(request: PendingRoute) {
        if (request.destination == null || !request.completed || request.requestedWaypoints) return
        request.requestedWaypoints = true
        if (!call { getWaypointInfo() }) {
            finishRoute(request, null)
            return
        }
        main.postDelayed({ if (route === request) finishRoute(request, null) }, 2_500)
    }

    private fun finishLocation(result: TripOrigin?) {
        val callback = locationRequest
        locationRequest = null
        callback?.invoke(result)
    }

    private fun finishRoute(request: PendingRoute, waypoints: List<String>?, reportTimeout: Boolean = true) {
        if (route !== request) return
        route = null
        val info = request.destination
        if (info == null) {
            if (reportTimeout) {
                Log.i(TAG, "destination_unconfirmed")
                onState(ready, "목적지 설정을 확인할 수 없어요.")
            }
            request.callback(null)
            return
        }
        onState(ready, if (request.completed) "내비 목적지와 경로 안내를 확인했어요." else "내비 목적지 응답을 확인했어요. 경로 검증 필요")
        request.callback(NavigationConfirmation(
            request.completed, info.remainDistance.takeIf { it > 0 },
            info.remainTime.takeIf { it > 0 }, waypoints
        ))
    }

    private fun call(block: NaviHelper.() -> Unit): Boolean = try {
        val sdk = helper
        if (sdk == null || closed) false else {
            sdk.block()
            true
        }
    } catch (error: RuntimeException) {
        Log.i(TAG, "navi_call_failed=${error.javaClass.simpleName}")
        onState(ready, "Pleos 내비 요청을 보낼 수 없어요.")
        false
    }

    private fun dispatch(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        locationRequest = null
        route = null
        helper?.let {
            if (ready) runCatching { it.removeListener(listener) }
            runCatching { it.release() }
        }
        helper = null
    }

    private companion object {
        const val TAG = "ChabakTripNavi"
    }
}
