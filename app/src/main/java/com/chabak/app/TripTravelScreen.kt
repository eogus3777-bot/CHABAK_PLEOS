package com.chabak.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val seoul = ZoneId.of("Asia/Seoul")
private val shortDate = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(seoul)
private const val ACCESS_VALIDITY_MILLIS = 15 * 60_000L

@Composable
internal fun TripTravelScreen(trip: Trip, campsite: Place?, save: (String, TripTravel) -> Boolean) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val gate = remember(ctx) { TravelUxGate(ctx) }
    var interaction by remember(gate) { mutableStateOf(TravelInteraction.UNKNOWN) }
    var ready by remember(trip.tripId) { mutableStateOf(false) }
    var status by remember(trip.tripId) { mutableStateOf("Pleos 내비 연결 상태 확인 중") }
    var busy by remember(trip.tripId) { mutableStateOf(false) }
    var draft by rememberSaveable(trip.tripId) { mutableStateOf(trip.travel.origin?.label.orEmpty()) }
    val navigation = remember(ctx, trip.tripId) {
        PleosTripNavigation(ctx) { connected, message ->
            ready = connected
            status = message
        }
    }

    DisposableEffect(gate, navigation) {
        gate.start { interaction = it }
        navigation.start()
        onDispose {
            navigation.close()
            gate.close()
        }
    }
    val editable = interaction == TravelInteraction.ALLOWED
    LaunchedEffect(editable) { if (!editable) keyboard?.hide() }

    val travel = trip.travel
    val keys = TravelLeg.entries.flatMap { direction ->
        travel.leg(direction)?.takeIf(::hasFreshAccess)?.directedRoute?.accessibleKeys().orEmpty()
    }.distinct()
    var catalog by remember(trip.tripId, keys) { mutableStateOf<List<RestArea>>(emptyList()) }
    LaunchedEffect(keys) {
        if (keys.isNotEmpty()) catalog = withContext(Dispatchers.IO) { RestAreaCatalog.load(keys) }
    }

    fun setOrigin(origin: TripOrigin) {
        if (!save(trip.tripId, travel.withOrigin(origin))) return
        draft = origin.label
        keyboard?.hide()
        status = if (origin.point == null) "출발지를 저장했어요. 내비 경로에 사용할 좌표는 확인이 필요해요." else "내비 현재 위치를 출발지로 저장했어요."
    }

    fun startNavigation(direction: TravelLeg) {
        if (!ready || busy || gate.current() != TravelInteraction.ALLOWED || campsite == null) return
        busy = true
        scope.launch {
            val camp = travel.campsitePoint ?: withContext(Dispatchers.IO) {
                runCatching { CampsiteCoordinates.forPlace(campsite) }.getOrNull()
            }
            if (camp == null || gate.current() != TravelInteraction.ALLOWED) {
                status = if (camp == null) "캠핑장 좌표를 확인할 수 없어요. 고캠핑 공식 조회에서 캠핑장을 다시 선택해 주세요."
                    else "이동 중에는 내비 요청을 제한해요."
                busy = false
                return@launch
            }
            navigation.currentLocation { location ->
                if (gate.current() != TravelInteraction.ALLOWED) {
                    status = "이동 중에는 내비 요청을 제한해요."
                    busy = false
                    return@currentLocation
                }
                var plan = travel.withCampsitePoint(camp)
                val current = location?.point
                if (direction == TravelLeg.OUTBOUND && plan.origin == null && location != null) {
                    plan = plan.withOrigin(location)
                }
                val origin = plan.origin
                val destination = if (direction == TravelLeg.OUTBOUND) camp else origin?.point
                if (destination == null || (direction == TravelLeg.RETURN && current?.matches(camp) != true)) {
                    status = "귀가 경로는 차박지의 현재 위치와 좌표가 있는 출발지가 확인될 때 요청할 수 있어요."
                    busy = false
                    return@currentLocation
                }
                val name = if (direction == TravelLeg.OUTBOUND) campsite.name else origin?.label.orEmpty()
                val address = if (direction == TravelLeg.OUTBOUND) campsite.address else origin?.label.orEmpty()
                navigation.requestDestination(name, address, destination) { confirmation ->
                    busy = false
                    if (confirmation == null) return@requestDestination
                    val route = if (confirmation.completed && current != null) SavedLeg(
                        direction, current, destination, System.currentTimeMillis(),
                        confirmation.remainingMeters, confirmation.remainingSeconds, confirmation.waypoints
                    ) else null
                    val checked = route?.let { plan.withLeg(direction, it) }
                    if (checked != null) plan = checked
                    else if (confirmation.completed) status = "내비 경로 응답을 받았어요. 이 여행에 저장할 출발지 좌표를 확인해 주세요."
                    if (save(trip.tripId, plan) && origin != null) draft = origin.label
                }
            }
        }
    }

    GlassPanel(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("여행 이동 정보", "${trip.place.name} · ${trip.visitDate}")
            RecommendationField("차박지 · ${trip.place.address.ifBlank { trip.place.name }}")
            RecommendationField("출발지 · ${travel.origin?.label ?: "직접 선택 필요"}")
            Text(status, color = Gold, fontSize = 22.sp)
            if (editable) {
                if (!busy) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InputDemo(draft, { draft = it.take(120) }, "출발지 주소 또는 장소명", Modifier.weight(1f))
                        ActionButton("출발지 저장", filled = false) {
                            val label = draft.trim()
                            if (label.isNotBlank() && gate.current() == TravelInteraction.ALLOWED) {
                                setOrigin(travel.origin?.takeIf { it.label == label } ?: TripOrigin(label))
                            }
                        }
                    }
                    if (ready) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton("내비 현재 위치 저장", Modifier.weight(1f), filled = false) {
                                if (gate.current() == TravelInteraction.ALLOWED) {
                                    busy = true
                                    navigation.currentLocation { origin ->
                                        busy = false
                                        if (origin != null && gate.current() == TravelInteraction.ALLOWED) setOrigin(origin)
                                        else status = "내비 현재 위치를 확인할 수 없어요. 출발지를 직접 입력해 주세요."
                                    }
                                }
                            }
                            if (campsite != null) {
                                ActionButton("내비 목적지 설정", Modifier.weight(1f)) { startNavigation(TravelLeg.OUTBOUND) }
                            }
                            if (campsite != null && travel.origin?.point != null) {
                                ActionButton("귀가 내비 요청", Modifier.weight(1f), filled = false) { startNavigation(TravelLeg.RETURN) }
                            }
                        }
                    }
                }
                if (campsite == null) RecommendationField("여행 기간의 차박지 이용 허용을 재확인해 주세요.")
            } else {
                RecommendationField(if (interaction == TravelInteraction.RESTRICTED) "주행 중에는 이동 정보만 표시해요."
                    else "차량 화면의 입력 허용 상태를 확인 중이에요. 확인 전에는 이동 정보만 표시해요.")
            }

            TravelLeg.entries.forEach { direction ->
                val route = travel.leg(direction)
                val fresh = route?.let(::hasFreshAccess) == true
                val candidates = if (fresh) route?.candidates(catalog).orEmpty() else emptyList()
                TravelLegDetails(direction, route)
                val saved = route?.selectedArea
                if (saved != null) RestAreaCard(saved, "저장한 휴게소", editable)
                if (editable && !busy && candidates.isNotEmpty()) {
                    candidates.filter { it.key != saved?.key }.take(4).forEach { area ->
                        RestAreaCard(area, "경로상 휴게소", true)
                        ActionButton("${area.name} 저장", filled = false) {
                            if (gate.current() == TravelInteraction.ALLOWED && route?.let(::hasFreshAccess) == true) {
                                travel.selectArea(direction, area)?.let { save(trip.tripId, it) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
            }
        }
    }
}

private fun hasFreshAccess(route: SavedLeg): Boolean = route.directedRoute != null &&
    System.currentTimeMillis() - route.checkedAtMillis in 0..ACCESS_VALIDITY_MILLIS

@Composable
private fun TravelLegDetails(direction: TravelLeg, route: SavedLeg?) {
    if (route == null) {
        SectionTitle(direction.label, "경로 검증 필요")
    } else {
        SectionTitle(direction.label, "마지막 Pleos 내비 응답 · ${shortDate.format(Instant.ofEpochMilli(route.checkedAtMillis))}")
        route.remainingMeters?.let {
            RecommendationField("응답 시 남은 거리 · ${String.format(Locale.KOREA, "%.1f", it / 1000.0)} km")
        }
        route.remainingSeconds?.let {
            RecommendationField("응답 시 남은 시간 · ${(it.toLong() + 59) / 60}분")
        }
        RecommendationField("경유지 · ${route.waypoints?.joinToString(" · ")?.ifBlank { "설정 없음" } ?: "확인 필요"}")
        if (!hasFreshAccess(route)) RecommendationField("경로 검증 필요 · 휴게소 진입 방향")
    }
}

@Composable
private fun RestAreaCard(area: RestArea, subtitle: String, editable: Boolean) {
    GlassPanel(Modifier.fillMaxWidth()) {
        SectionTitle(area.name, "$subtitle · ${area.roadName} · ${area.key.direction}")
        if (area.foods.isEmpty()) RecommendationField("대표 먹거리 · 확인 필요")
        else area.foods.take(4).forEach { food ->
            val price = food.priceWon?.let { String.format(Locale.KOREA, "%,d원", it) } ?: "가격 확인 필요"
            RecommendationField("${food.name} · $price")
        }
        RecommendationField("출처 · 한국도로공사")
        if (editable) {
            val ctx = LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("휴게소 출처", Modifier.weight(1f), filled = false) { openBrowser(ctx, RestAreaCatalog.INFO_SOURCE) }
                ActionButton("먹거리 출처", Modifier.weight(1f), filled = false) { openBrowser(ctx, RestAreaCatalog.FOOD_SOURCE) }
            }
        }
    }
}
