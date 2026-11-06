package com.chabak.app

import java.time.LocalDate

internal data class PackItem(val id: String, val name: String, val group: String, val description: String = "")

internal data class PackingObservation(
    val region: String,
    val observedOn: LocalDate,
    val temperature: Int,
    val description: String
) {
    fun forTrip(trip: Trip, today: LocalDate): PackingObservation? = takeIf {
        region == trip.place.region && region != "전국" && observedOn == today && observedOn == trip.visitDate
    }
}

internal fun Trip.packingSeason(): String = when (visitDate.monthValue) {
    3, 4, 5 -> "봄"
    6, 7, 8 -> "여름"
    9, 10, 11 -> "가을"
    else -> "겨울"
}

internal fun tripPackItems(trip: Trip, place: Place?, observation: PackingObservation?, today: LocalDate): List<PackItem> {
    val days = trip.nights + 1
    val basic = listOf(
        PackItem("mat", "차박 매트", "잠자리", "접은 좌석의 틈과 높이 차를 줄여 주세요."),
        PackItem("pillow", "베개 ${trip.people}개", "잠자리", "한 사람에 하나씩 준비해 주세요."),
        PackItem("blanket", "침구 ${trip.people}인분", "잠자리", "각자 쓸 침낭이나 담요를 준비해 주세요."),
        PackItem("water", "식수 약 ${trip.people * days * 2}L (예비 포함)", "먹거리", "인원과 방문 일수로 계산한 준비량이에요. 활동량과 보급 계획에 맞춰 조절하세요."),
        PackItem("food", "식사 / 간식 ${trip.people}명 · ${days}일분", "먹거리", "도착일과 귀가일에 먹을 식사도 챙겨 주세요."),
        PackItem("light", "충전식 랜턴", "기타", "밤에 이동할 때 쓸 수 있도록 출발 전에 충전하세요."),
        PackItem("tissue", "휴지 · 쓰레기봉투", "기타", "현장 배출 규칙을 확인하고 수거할 봉투를 챙기세요."),
        PackItem("charger", "충전 케이블 · 보조배터리", "기타", "휴대기기용 케이블과 미리 충전한 보조배터리를 챙기세요.")
    )
    val seasonal = when (trip.packingSeason()) {
        "겨울" -> listOf(
            PackItem("winter", "방한 침낭 · 보온 담요", "계절", "방문지의 야간 기온에 맞는 침낭을 골라 주세요."),
            PackItem("gloves", "장갑 · 모자 · 여벌 양말", "계절", "젖었을 때 교체할 옷을 따로 보관하세요.")
        )
        "여름" -> listOf(
            PackItem("summer", "모기 기피용품", "계절", "해 질 무렵 야외 활동에 대비하세요."),
            PackItem("sun", "자외선 차단제 · 모자", "계절", "그늘이 적은 동선에서 사용하세요.")
        )
        "봄" -> listOf(PackItem("spring", "가벼운 겉옷", "계절", "해가 진 뒤 기온이 떨어질 때 겹쳐 입으세요."))
        else -> listOf(PackItem("fall", "두꺼운 겉옷 · 보온용품", "계절", "일교차에 대비해 저녁에 입을 옷을 따로 챙기세요."))
    }
    val weatherItems = observation?.forTrip(trip, today)?.takeIf { place != null }?.let { current ->
        buildList {
            if (current.description.contains("비") || current.description.contains("뇌우")) {
                add(PackItem("rain", "우비 · 방수 신발 · 방수 가방", "날씨", "같은 지역의 현재 날씨를 참고했어요. 출발 전 현지 예보를 다시 확인하세요."))
            }
            if (current.temperature <= 5) {
                add(PackItem("cold", "보온 의류 · 핫팩", "날씨", "같은 지역의 현재 기온을 참고했어요. 야간 기온은 따로 확인하세요."))
            }
            if (current.temperature >= 28) {
                add(PackItem("heat", "쿨링용품 · 여분의 식수", "날씨", "같은 지역의 현재 기온을 참고했어요. 이동 중 식수 보급 지점을 확인하세요."))
            }
        }
    }.orEmpty()
    val campsite = place?.let(::campsitePackItems).orEmpty()
    val extras = trip.extraItems.map { PackItem("custom_$it", it, "추가") }
    return basic + seasonal + weatherItems + campsite + extras
}

internal fun campsitePackItems(camp: Place): List<PackItem> {
    val tags = camp.natureTags()
    fun item(id: String, name: String, description: String) = PackItem("site_${id}_${camp.id}", name, "장소", description)
    val terrain = if (camp.live || camp.kind != CampingPlaceKind.CAMPGROUND) buildList {
        if ("계곡" in tags) add(item("valley", "계곡 지형 · 미끄럼 방지 신발 · 방수 파우치", "물가의 젖은 바닥과 짐이 젖는 상황에 대비하세요."))
        if ("바다" in tags || "해변" in tags) add(item("sea", "바닷가 바람 대비 · 바람막이 · 여벌 옷", "바람이 강한 해안에서 겹쳐 입을 옷을 챙기세요."))
        if ("숲" in tags) add(item("forest", "숲 주변 대비 · 방충망 · 벌레 기피용품", "문을 열어 둘 때 사용할 방충망의 크기를 차량에 맞춰 확인하세요."))
        if ("산" in tags && "숲" !in tags) add(item("mountain", "산지 기온 차 대비 · 보온용 겉옷", "아침과 밤에 겹쳐 입을 수 있는 겉옷을 준비하세요."))
        if ("호수" in tags || "강" in tags) add(item("water", "수변 환경 대비 · 방수 파우치 · 여벌 옷", "휴대전화와 갈아입을 옷을 젖지 않도록 따로 보관하세요."))
    }.take(2) else emptyList()

    val facilities = buildList {
        when (camp.toiletCount ?: camp.permit?.toiletAvailable?.let { if (it) 1 else 0 }) {
            null -> add(item("toilet_check", "화장실 이용 여부 확인 필요", "현장에 화장실이 있는지와 야간 개방 시간을 확인하세요."))
            0 -> add(item("toilet_nearby", "인근 이용 가능한 화장실 확인", "등록된 화장실이 0개인 장소는 가까운 대체 시설을 찾아 두세요."))
        }
        when (camp.showerCount) {
            null -> add(item("shower_check", "샤워실 이용 여부 확인 필요", "샤워실 운영 여부와 이용 시간을 확인하세요."))
            0 -> add(item("shower_alternative", "간이 세면용품 · 물티슈 준비", "샤워실이 등록되지 않은 장소에서는 대체 세면 방법을 준비하세요."))
        }
        if (camp.sinkCount == 0) add(item("sink_alternative", "세척용 물과 빈 물통 준비", "개수대가 등록되지 않은 장소의 세척·폐수 처리 규칙을 확인하세요."))
        when (camp.campingPower) {
            null -> add(item("power_check", "캠핑용 전기 사용 조건 확인 필요", "사이트의 콘센트 제공 여부와 허용 용량을 운영자에게 확인하세요."))
            false -> add(item("power_alternative", "캠핑용 보조전원 준비", "현장 콘센트 없이 쓸 기기를 미리 충전하세요."))
            true -> Unit
        }
        when {
            camp.onsiteEvCharger == true -> Unit
            camp.nearbyEvCharger == true ->
                add(item("ev_nearby", "인근 충전소 경유 계획", "이용 시간과 차량에 맞는 충전기 종류를 확인하세요."))
            camp.onsiteEvCharger == false && camp.nearbyEvCharger == false ->
                add(item("ev_route", "이동 경로에서 전기차 충전 지점 확인", "캠핑장에 도착하기 전 이용할 충전소를 정해 두세요."))
            else -> add(item("ev_check", "내부·인근 전기차 충전소 확인 필요", "캠핑 사이트의 전기 시설과 차량 충전소를 구분해 확인하세요."))
        }
        when (camp.overnightAllowed) {
            null -> add(item("overnight_check", "차량 내 숙박 허용 여부 확인 필요", "차량 안에서 자도 되는지 예약 전에 운영자에게 확인하세요."))
            false -> add(item("overnight_alternative", "차량 숙박을 허용하는 차박지 확인", "차량 숙박이 허용되는 다른 장소를 선택해 주세요."))
            true -> Unit
        }
        val permit = camp.permit
        if (permit != null && (permit.cookingAllowed != true || permit.openFlameAllowed != true)) {
            add(item("no_cook_meal", "현장 취사 없이 먹을 식사 준비", "취사와 화기 사용의 허용 범위를 각각 확인하세요."))
        }
    }
    return terrain + facilities
}
