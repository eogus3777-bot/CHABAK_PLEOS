package com.chabak.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ChabakDesign {
    val background = Color(0xFF071E19)
    val card = Color(0xC7163028)
    val accent = Color(0xFFB7E6CF)
    val primary = Color(0xFF2E6C53)
    val border = Color(0x4785B99D)
    val muted = Color(0xFFD0DFD6)
    val radius = 22.dp
    val buttonRadius = 16.dp
    val sectionText = 32.sp
    val bodyText = 24.sp
    val space = 16.dp
}

internal object ScenicBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, bitmap: Bitmap) = bitmap.allocationByteCount
    }
    fun clear() = cache.evictAll()
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
}

@Composable
internal fun ChabakImage(res: Int, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(0.dp))) {
        val width = with(density) { maxWidth.toPx() }.takeIf { it.isFinite() }?.toInt()?.coerceIn(1, 1920) ?: 1920
        val height = with(density) { maxHeight.toPx() }.takeIf { it.isFinite() }?.toInt()?.coerceIn(1, 1080) ?: 1080
        // Power-of-two sampling plus a shared byte-bounded cache; density scaling is disabled.
        val bounds = remember(res) { BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeResource(context.resources, res, this)
        } }
        val sample = bitmapSample(bounds.outWidth, bounds.outHeight, width, height)
        val key = "$res:$sample"
        val bitmap by produceState(ScenicBitmapCache.get(key), key) {
            value = withContext(Dispatchers.IO) {
                ScenicBitmapCache.get(key) ?: run {
                    BitmapFactory.decodeResource(context.resources, res, BitmapFactory.Options().apply {
                        inScaled = false; inSampleSize = sample
                    })?.also { ScenicBitmapCache.put(key, it) }
                }
            }
        }
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = scale) }
    }
}

@Composable
internal fun ChabakNavigationRail(page: String, enabled: Boolean, navigate: (String) -> Unit) {
    val pages = listOf("home", "recommendations", "stay", "energy", "environment", "diary")
    val labels = listOf("홈", "차박지·여행", "차박 중", "스마트 에너지", "환경 조성", "다이어리")
    val symbols = listOf("⌂", "⌖", "☾", "ϟ", "◈", "▤")
    val selected = when (page) {
        "places", "place_detail", "packing", "trip_travel", "nearby" -> "recommendations"
        "playlist", "morning", "wake_scene" -> "stay"
        else -> page
    }
    Column(Modifier.width(120.dp).fillMaxHeight().clip(RoundedCornerShape(26.dp))
        .background(Color(0xDC09261E)).border(1.dp, ChabakDesign.border, RoundedCornerShape(26.dp))
        .padding(horizontal = 8.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("C", color = ChabakDesign.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("CHABAK 2.0", color = ChabakDesign.muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        pages.forEachIndexed { index, destination ->
            val active = selected == destination
            Column(Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(RoundedCornerShape(18.dp))
                .background(if (active) ChabakDesign.primary else Color.Transparent)
                .semantics { contentDescription = "메뉴 ${labels[index]}" }
                .clickable(enabled = enabled || destination == "home" || destination == "energy") { navigate(destination) }
                .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(symbols[index], color = if (active) Color.White else ChabakDesign.accent, fontSize = 32.sp)
                Text(if (index == 3) "스마트\n에너지" else labels[index], color = Color.White.copy(alpha = if (enabled || active) 1f else .65f),
                    fontSize = 20.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun BatteryDial(percent: Int?, simulated: Boolean) {
    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(224.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(14.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(ChabakDesign.border, 140f, 260f, false, style = stroke)
            percent?.let { drawArc(ChabakDesign.accent, 140f, 260f * it.coerceIn(0, 100) / 100f, false, style = stroke) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(percent?.let { "$it%" } ?: "—", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Text(if (simulated) "시뮬레이션" else "Vehicle SDK", color = ChabakDesign.accent, fontSize = 20.sp)
        }
    }
}

@Composable
internal fun LightingPreview() {
    var choice by rememberSaveable { mutableIntStateOf(0) }
    var brightness by rememberSaveable { mutableFloatStateOf(.7f) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("차량 실내 · 미리보기", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(18.dp))) {
            ChabakImage(if (choice in setOf(0, 2)) R.drawable.chabak2_lighting_warm else R.drawable.chabak2_lighting_cool,
                Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - brightness) * .65f)))
        }
        listOf("따뜻한 조명", "숲속 휴식", "노을 감성", "밤의 휴식").chunked(2).forEachIndexed { row, labels ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { column, label -> ActionButton(label, Modifier.weight(1f), choice == row * 2 + column) { choice = row * 2 + column } }
            }
        }
        Text("미리보기 밝기 · ${(brightness * 100).toInt()}%", color = ChabakDesign.accent, fontSize = 23.sp)
        androidx.compose.material3.Slider(value = brightness, onValueChange = { brightness = it })
        Text("화면 이미지에만 적용 · 차량 조명 제어 미연동", color = ChabakDesign.muted, fontSize = 21.sp)
    }
}

@Composable
internal fun GleoInformation() {
    GlassPanel(Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SectionTitle("Gleo")
            Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                val values = listOf(.15f,.3f,.5f,.8f,.4f,1f,.7f,.35f,.65f,.25f,.15f)
                values.forEachIndexed { index, height ->
                    val x = size.width / 2 + (index - values.size / 2) * 24.dp.toPx()
                    drawLine(ChabakDesign.accent, Offset(x, size.height * (.5f - height * .4f)),
                        Offset(x, size.height * (.5f + height * .4f)), 8.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
            Text("Gleo 음성 연결 미검증", color = ChabakDesign.accent, fontSize = 25.sp)
            listOf("차박지 추천해 줘", "근처 양조장 찾아줘", "이번 주 오일장 알려줘", "차박 시작해 줘", "배터리 얼마나 남았어?").forEach {
                Text("“$it”", color = Color.White, fontSize = 28.sp)
            }
        }
    }
}
