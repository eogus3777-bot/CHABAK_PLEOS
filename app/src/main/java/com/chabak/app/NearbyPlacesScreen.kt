package com.chabak.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun NearbyPlacesScreen(
    trip: Trip, query: NearbyQuery, result: NearbyResult?, loading: Boolean,
    search: (NearbyQuery) -> Unit, onTravel: () -> Unit
) {
    val context = LocalContext.current
    val gate = remember(context) { TravelUxGate(context) }
    var interaction by remember { mutableStateOf(TravelInteraction.UNKNOWN) }
    var ready by remember(trip.tripId) { mutableStateOf(false) }
    var feedback by remember(trip.tripId) { mutableStateOf("") }
    var selected by remember(trip.tripId) { mutableStateOf<TripPlace?>(null) }
    var navigating by remember(trip.tripId) { mutableStateOf(false) }
    val navi = remember(trip.tripId) {
        PleosTripNavigation(context) { connected, message -> ready = connected; feedback = message }
    }
    DisposableEffect(gate, navi) {
        gate.start { interaction = it }
        navi.start()
        onDispose { navi.close(); gate.close() }
    }
    LaunchedEffect(interaction) {
        if (interaction != TravelInteraction.ALLOWED) selected = null
    }
    LaunchedEffect(query) {
        feedback = if (ready) "Pleos 내비 연결됨" else "Pleos 내비 연결 확인 중"
    }
    LaunchedEffect(trip.tripId, loading, result?.tripId) {
        if (!loading && result?.tripId != trip.tripId) search(query.copy(
            window = VisitWindow(trip.visitDate), strictDates = false))
    }

    GlassPanel(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("주변 즐길거리", trip.place.name)
            Text(if (query.category == TripPlaceCategory.REST_AREA) "가는 길 · 방향별 경로 확인이 필요한 휴게소"
                else "선택한 캠핑장 기준 직선 50 km 이내 · 최대 5곳", color = Color.White, fontSize = 24.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TripPlaceCategory.entries.forEach { category ->
                    ActionButton(category.label, Modifier.weight(1f), filled = category == query.category) {
                        if (!loading) search(query.copy(category = category))
                    }
                }
            }
            if (query.category == TripPlaceCategory.MARKET) {
                Text("조회 날짜 · ${query.window.start}" +
                    if (query.window.end != query.window.start) " ~ ${query.window.end}" else "",
                    color = Gold, fontSize = 25.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("여행 날짜", Modifier.weight(1f), filled = false) {
                        if (!loading) search(query.copy(window = VisitWindow(trip.visitDate), strictDates = false))
                    }
                    ActionButton("이번 주말", Modifier.weight(1f), filled = false) {
                        if (!loading) search(query.copy(window = MarketCalendar.thisWeekend(
                            LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))), strictDates = true))
                    }
                }
            }
            if (loading) CircularProgressIndicator(color = Gold)
            result?.takeIf { it.tripId == trip.tripId && it.query == query }?.let { loaded ->
                Text(loaded.message, color = Gold, fontSize = 24.sp)
                loaded.items.forEach { match ->
                    val place = match.place
                    GlassPanel(Modifier.fillMaxWidth()) {
                        SectionTitle(place.name, place.address)
                        Text("캠핑장에서 직선 ${String.format(Locale.KOREA, "%.1f", match.kilometers)} km",
                            color = Gold, fontSize = 26.sp)
                        if (place.category == TripPlaceCategory.MARKET) {
                            Text("등록 장날 · ${place.marketRule}", color = Color.White, fontSize = 25.sp)
                            Text((if (match.matchesDates) "조회 날짜에 장날 후보 · " else "다음 장날 후보 · ") +
                                (match.nextMarketDate?.toString() ?: "확인 필요"), color = Gold, fontSize = 25.sp)
                            Text("실제 개장·휴장·일정 변경은 시장에 확인하세요.", color = Color.White, fontSize = 23.sp)
                        } else if (place.category == TripPlaceCategory.BREWERY) {
                            Text(place.program.ifBlank { "현재 체험 프로그램 확인 필요" }, color = Color.White, fontSize = 24.sp)
                            Text(place.hours.ifBlank { "운영 시간 확인 필요" }, color = Color.White, fontSize = 24.sp)
                            Text(place.reservation.ifBlank { "방문·예약 조건 확인 필요" }, color = Color.White, fontSize = 24.sp)
                        } else {
                            Text(place.program.ifBlank { "대표 음식 확인 필요" }, color = Color.White, fontSize = 24.sp)
                        }
                        Text("${place.source} · 기준 ${place.sourceDate}", color = Color.White, fontSize = 21.sp)
                        if (place.locationSourceUrl.isNotBlank()) {
                            Text("방문지 좌표 · 포천시 공식 관광 자료", color = Color.White, fontSize = 21.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton("공식 자료", Modifier.weight(1f), filled = false) {
                                if (gate.current() == TravelInteraction.ALLOWED) {
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(place.sourceUrl))) }
                                    catch (_: android.content.ActivityNotFoundException) { feedback = "공식 자료를 열 수 없어요." }
                                }
                            }
                            if (interaction == TravelInteraction.ALLOWED && place.point != null) {
                                ActionButton(if (navigating) "내비 요청 중…" else "내비 목적지", Modifier.weight(1f)) {
                                    if (!navigating && gate.current() == TravelInteraction.ALLOWED) selected = place
                                }
                            }
                        }
                    }
                }
                if (loaded.collectedAt.isNotBlank()) Text("공식 저장자료 수집 · ${loaded.collectedAt}",
                    color = Color.White, fontSize = 21.sp)
                if (loaded.coverage.isNotBlank()) Text(loaded.coverage, color = Color.White, fontSize = 21.sp)
            }
            if (query.category == TripPlaceCategory.REST_AREA) {
                ActionButton("이동 정보에서 경로 확인", Modifier.fillMaxWidth(), filled = false, onClick = onTravel)
            }
            if (query.category == TripPlaceCategory.BREWERY) {
                Text("운전자는 음주하지 마세요. 현재 체험 운영과 방문·예약 조건을 먼저 확인하세요.",
                    color = Gold, fontSize = 24.sp)
            }
            if (feedback.isNotBlank()) Text(feedback, color = Color.White, fontSize = 22.sp)
        }
    }
    selected?.let { place ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("내비 목적지를 변경할까요?") },
            text = { Text("${place.name}\n${place.address}\n현재 캠핑장 경로가 변경돼요.") },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    val point = place.point
                    if (point != null && ready && !navigating && gate.current() == TravelInteraction.ALLOWED) {
                        navigating = true
                        navi.requestDestination(place.name, place.address, point) { confirmation ->
                            navigating = false
                            feedback = "${place.name} · " + when {
                                confirmation == null -> "목적지 설정을 확인할 수 없어요."
                                confirmation.completed -> "목적지 좌표와 경로 안내를 확인했어요."
                                else -> "목적지 응답 확인 · 경로 안내 확인 필요"
                            }
                        }
                    } else feedback = "내비 연결과 차량 조작 허용 상태를 확인해 주세요."
                }) { Text("목적지 설정", fontSize = 24.sp) }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("취소", fontSize = 24.sp) } }
        )
    }
}
