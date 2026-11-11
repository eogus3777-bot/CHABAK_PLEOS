package com.chabak.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun AmbientLightPanel(
    controller: PleosAmbientLight,
    scene: TimeScene,
    autoApply: Boolean,
    onAutoApply: (Boolean) -> Unit,
    guard: suspend () -> String?
) {
    var state by remember(controller) { mutableStateOf(AmbientLightState()) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, busy) {
        while (isActive && !busy) {
            state = controller.read()
            delay(2_000)
        }
    }
    val zone = state.zones.firstOrNull { it.key == selectedKey } ?: state.zones.firstOrNull()
    var selectedRgb by rememberSaveable(zone?.key) { mutableStateOf(zone?.rgb) }
    LaunchedEffect(zone?.key, zone?.rgb) {
        if (!busy) selectedRgb = zone?.rgb
    }
    var brightness by remember(zone?.key, zone?.brightness) { mutableFloatStateOf((zone?.brightness ?: 0).toFloat()) }
    fun apply(value: Int?, color: AmbientColor?) {
        if (busy || zone == null) return
        if (color != null) {
            selectedRgb = color.rgb
            feedback = "${color.label} 적용 중"
        }
        busy = true
        scope.launch {
            try {
                val result = controller.apply(zone.key, value, color, guard)
                val actual = controller.read()
                state = actual
                selectedRgb = actual.zones.firstOrNull { it.key == zone.key }?.rgb
                feedback = result.message
            } finally { busy = false }
        }
    }

    Text("차량 앰비언트 조명", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
    if (state.zones.size > 1) {
        state.zones.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { item ->
                    OutlineChip(item.label, zone?.key == item.key, Modifier.weight(1f)) {
                        if (!busy) selectedKey = item.key
                    }
                }
            }
        }
    }
    if (zone == null) {
        Text(state.status, color = Gold, fontSize = 24.sp)
    } else {
        Text("${zone.brightness?.let { "$it%" } ?: "밝기 확인 필요"} · ${zone.colorLabel ?: "색상 확인 필요"}",
            color = Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        val limits = zone.limits
        if (limits != null && limits.first < limits.last && zone.brightnessWritable) {
            Slider(value = brightness.coerceIn(limits.first.toFloat(), limits.last.toFloat()),
                onValueChange = { brightness = it }, enabled = !busy,
                valueRange = limits.first.toFloat()..limits.last.toFloat(),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "차량 조명 밝기" })
            ActionButton(if (busy) "조명 적용 중" else "밝기 ${brightness.toInt()}% 적용", Modifier.fillMaxWidth()) {
                apply(brightness.toInt(), null)
            }
        } else {
            Text(if (limits == null) "밝기 범위 확인 필요" else "밝기 조회 전용", color = ChabakDesign.muted, fontSize = 22.sp)
        }
        if (zone.colorWritable && controller.colors.isNotEmpty()) {
            controller.colors.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    pair.forEach { color ->
                        val selected = selectedRgb == color.rgb
                        OutlineChip(color.label, selected,
                            Modifier.weight(1f).semantics { this.selected = selected }, enabled = !busy) {
                            apply(null, color)
                        }
                    }
                }
            }
        } else Text("색상 변경 지원 범위 확인 필요", color = ChabakDesign.muted, fontSize = 22.sp)
        if (state.driveModeAuto == true) Text("차량 주행 모드 자동 색상 사용 중", color = Gold, fontSize = 22.sp)
        val preset = scene.ambientPreset()
        Text("${scene.nameLabel} 추천 · ${preset.color.label} ${preset.brightness}%", color = ChabakDesign.muted, fontSize = 23.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("차박 시작 시 조명 적용", color = Color.White, fontSize = 24.sp, modifier = Modifier.weight(1f))
            Switch(checked = autoApply, onCheckedChange = onAutoApply,
                modifier = Modifier.semantics { contentDescription = "차박 시작 시 조명 자동 적용 동의" })
        }
        Text("P 상태 · 최소 배터리 설정 확인 후 적용", color = ChabakDesign.muted, fontSize = 22.sp)
    }
    if (feedback.isNotBlank()) Text(feedback, color = Gold, fontSize = 23.sp)
}
