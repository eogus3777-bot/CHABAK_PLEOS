package com.chabak.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Warning = Color(0xFFFFAB99)

@Composable
internal fun rememberVehicleEnergy(enabled: Boolean): VehicleEnergy {
    val context = LocalContext.current
    val lifecycle = (context as? LifecycleOwner)?.lifecycle
    val scope = rememberCoroutineScope()
    var readings by remember(enabled, context) { mutableStateOf(VehicleEnergy()) }
    DisposableEffect(enabled, lifecycle, context) {
        var job: Job? = null
        fun start() {
            if (job == null) job = scope.launch { PleosEnergyReader(context).monitor { readings = it } }
        }
        fun stop() {
            job?.cancel()
            job = null
            readings = VehicleEnergy()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        if (enabled) {
            lifecycle?.addObserver(observer)
            if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) start()
        }
        onDispose {
            lifecycle?.removeObserver(observer)
            stop()
        }
    }
    return readings
}

@Composable
internal fun SmartEnergyScreen(trip: Trip, readings: VehicleEnergy, save: (String, TripEnergyPlan) -> Boolean) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val gate = remember(context) { TravelUxGate(context) }
    var interaction by remember(gate) { mutableStateOf(TravelInteraction.UNKNOWN) }
    DisposableEffect(gate) {
        gate.start { interaction = it }
        onDispose { gate.close() }
    }
    val editable = interaction == TravelInteraction.ALLOWED
    LaunchedEffect(editable) { if (!editable) keyboard?.hide() }

    val plan = trip.energyPlan
    var settingsOpen by rememberSaveable(trip.tripId) { mutableStateOf(false) }
    var simulated by rememberSaveable(trip.tripId) { mutableStateOf(false) }
    var simulatedSoc by rememberSaveable(trip.tripId) { mutableIntStateOf(75) }
    var hours by rememberSaveable(trip.tripId, plan) { mutableIntStateOf(plan.overnightHours) }
    var minimum by rememberSaveable(trip.tripId, plan) { mutableStateOf(plan.minimumPercent.toString()) }
    var power by rememberSaveable(trip.tripId, plan) { mutableStateOf(plan.estimatedPowerKw?.toString().orEmpty()) }
    var feedback by remember(trip.tripId) { mutableStateOf("") }

    val soc = if (simulated) simulatedSoc else readings.soc.valueOrNull()
    val capacity = if (simulated) 72.0 else readings.capacityKwh.valueOrNull()
    // Vehicle SDK does not provide measured overnight load for this plan.
    val estimate = if (simulated) plan.estimate(soc, capacity) else null
    val available = plan.availablePercent(soc)
    val source = when {
        simulated -> "앱 시뮬레이션 · 가정값"
        android.os.Build.HARDWARE in setOf("ranchu", "goldfish") -> "Pleos Vehicle SDK · 에뮬레이터"
        else -> "Pleos Vehicle SDK 조회"
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        GlassPanel(Modifier.fillMaxWidth(if (maxWidth >= 760.dp) 0.82f else 1f).heightIn(max = maxHeight)) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionTitle("스마트 에너지", trip.place.name)
                Text(source, color = Gold, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "배터리 잔량 · ${soc?.let { "$it%" } ?: readings.soc.missingLabel()}",
                    color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold
                )
                BatteryDial(soc, simulated)
                EnergyWarning(plan, soc, simulated)
                EnergyLine("배터리 용량 · ${capacity?.let { "${decimal(it)} kWh (${if (simulated) "가정" else "공칭"})" } ?: readings.capacityKwh.missingLabel()}")
                if (!simulated) {
                    EnergyLine("주행 가능 거리 · 미지원")
                    EnergyLine("충전 중 여부 · 미지원")
                    EnergyLine("충전 스위치 · ${readings.chargeSwitch.valueOrNull()?.let { if (it) "켜짐" else "꺼짐" } ?: readings.chargeSwitch.missingLabel()}")
                }
                Text(
                    "숙박 ${plan.overnightHours}시간 · 귀가용 보호 기준 ${plan.minimumPercent}%",
                    color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold
                )
                EnergyLine("차박용 사용 가능 비율 · ${available?.let { "$it%p" } ?: "확인 필요"}")
                if (estimate != null) {
                    EnergyLine("가상 숙박 소비량 · ${decimal(estimate.useKwh)} kWh")
                    EnergyLine("가상 숙박 종료 잔량 · ${decimal(estimate.endPercent)}%")
                    val margin = estimate.minimumMarginKwh
                    val label = if (margin >= 0) "여유" else "부족"
                    EnergyLine("보호 기준 대비 가상 $label · ${decimal(abs(margin))} kWh")
                    EnergyLine("보호 기준까지 가상 사용 시간 · ${estimate.hoursUntilMinimum?.let { "${decimal(it)}시간" } ?: "계산 불가"}")
                } else {
                    EnergyLine(if (simulated) "가상 계산 · 추정 소비전력 입력 필요"
                        else "숙박 소비량·사용 시간 · 계산 불가 (측정 소비전력 미확인)")
                }
                EnergyLine("실제 귀가 필요 에너지 · 확인 필요")
                EnergyLine("잔량 경고는 차박 중·에너지 화면이 보일 때 확인할 수 있어요. 백그라운드에서는 알림을 보장하지 않아요.")
                if (editable) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        ActionButton(if (settingsOpen) "설정 닫기" else "계획 설정", Modifier.weight(1f), filled = false) {
                            if (gate.current() == TravelInteraction.ALLOWED) settingsOpen = !settingsOpen
                        }
                        ActionButton(if (simulated) "시뮬레이션 끄기" else "시뮬레이션 켜기", Modifier.weight(1f), filled = false) {
                            if (gate.current() == TravelInteraction.ALLOWED) simulated = !simulated
                        }
                    }
                    if (simulated) {
                        EnergyLine("가상 잔량 조절 · $simulatedSoc%")
                        Slider(
                            value = simulatedSoc.toFloat(),
                            onValueChange = { if (gate.current() == TravelInteraction.ALLOWED) simulatedSoc = it.roundToInt() },
                            valueRange = 0f..100f
                        )
                    }
                    if (settingsOpen) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TripEnergyPlan.hours.forEach { choice ->
                                ActionButton("${choice}시간", Modifier.weight(1f), filled = hours == choice) {
                                    if (gate.current() == TravelInteraction.ALLOWED) hours = choice
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            EnergyInput("귀가용 보호 기준 (%)", minimum, Modifier.weight(1f), KeyboardType.Number) { minimum = it }
                            EnergyInput("시뮬레이션 전력 (kW)", power, Modifier.weight(1f), KeyboardType.Decimal) { power = it }
                        }
                        ActionButton("에너지 계획 저장", Modifier.fillMaxWidth()) {
                            if (gate.current() == TravelInteraction.ALLOWED) {
                                val percent = minimum.trim().toIntOrNull()
                                val kw = power.trim().replace(',', '.').toDoubleOrNull()
                                if (percent == null || percent !in 1..100 ||
                                    (power.isNotBlank() && (kw == null || kw !in 0.0..TripEnergyPlan.MAX_POWER_KW))
                                ) {
                                    feedback = "최소 잔량은 1~100%, 소비전력은 0~50 kW로 입력해 주세요."
                                } else if (save(trip.tripId, TripEnergyPlan(hours, percent, kw))) {
                                    keyboard?.hide()
                                    settingsOpen = false
                                    feedback = "이 여행의 에너지 계획을 저장했어요."
                                }
                            }
                        }
                    }
                    if (feedback.isNotBlank()) Text(feedback, color = Gold, fontSize = 24.sp)
                } else {
                    EnergyLine(if (interaction == TravelInteraction.RESTRICTED) "주행 중에는 에너지 정보를 읽을 수 있어요."
                        else "입력 허용 상태를 확인 중이에요.")
                }
            }
        }
    }
}

@Composable
internal fun EnergyWarning(plan: TripEnergyPlan, soc: Int?, simulated: Boolean = false) {
    if (plan.warnsAt(soc)) {
        val warning = if (simulated) "시뮬레이션 경고" else "배터리 경고"
        Text(
            "$warning · 최소 잔량 도달 ($soc% / ${plan.minimumPercent}%)",
            color = Warning, fontWeight = FontWeight.Bold, fontSize = 22.sp
        )
    }
}

@Composable
private fun EnergyLine(text: String) {
    Text(text, color = Color.White, fontSize = 24.sp)
}

@Composable
private fun EnergyInput(label: String, value: String, modifier: Modifier, keyboardType: KeyboardType, update: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { update(it.take(10)) }, modifier = modifier, singleLine = true,
        textStyle = TextStyle(fontSize = 22.sp), keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        label = { Text(label, fontSize = 17.sp) }
    )
}

private fun EnergyRead<*>.missingLabel() = (this as? EnergyRead.Missing)?.issue?.label ?: "확인 필요"
private fun decimal(value: Double): String = String.format(Locale.KOREA, "%.1f", value)
