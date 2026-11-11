package com.chabak.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val diaryTime = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"))

@Composable
internal fun TripDiaryScreen(
    trip: Trip,
    legacyDiaries: List<DiaryEntry>,
    canEdit: Boolean,
    update: (String, (TripDiary) -> TripDiary) -> Boolean,
    stillAllowed: () -> Boolean
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val diary = trip.diary
    var note by rememberSaveable { mutableStateOf(diary.note) }
    var review by rememberSaveable { mutableStateOf(diary.review) }
    var status by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var showLegacy by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_DIARY_PHOTOS)
    ) { selected ->
        if (selected.isNotEmpty() && stillAllowed()) {
            val available = (MAX_DIARY_PHOTOS - diary.photos.size).coerceAtLeast(0)
            val newUris = selected.distinct().filterNot { it.toString() in diary.photos }
            val accepted = newUris.take(available).filter { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    true
                } catch (_: SecurityException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                }
            }.map(Uri::toString)
            if (accepted.isNotEmpty() && !update(trip.tripId) { current ->
                    current.copy(photos = (current.photos + accepted).distinct().take(MAX_DIARY_PHOTOS))
                }) {
                status = "사진을 저장할 수 없어요."
            } else {
                status = when {
                    accepted.size < newUris.size -> "접근 권한 또는 최대 장수 제한으로 일부 사진을 추가하지 못했어요."
                    accepted.isNotEmpty() -> "사진 ${accepted.size}장을 추가했어요."
                    else -> "이미 추가된 사진이에요."
                }
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        GlassPanel(Modifier.fillMaxWidth().fillMaxHeight(.85f)) {
            SectionTitle("여행 기록", "${trip.visitDate} 예정 · ${trip.place.name}")
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (trip.place.address.isNotBlank()) DiaryText(trip.place.address)
                if (!canEdit) DiaryText("차량 화면 조작이 허용될 때 사진과 메모를 편집할 수 있어요.")
                if (diary.photos.isEmpty()) DiaryText("추가한 사진이 없어요.")
                if (diary.photos.isNotEmpty() && canEdit) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        diary.photos.forEachIndexed { index, photo ->
                            key(photo) {
                                Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    DiaryImage(photo, "사진 ${index + 1} 확대",
                                        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp))
                                            .clickable { if (stillAllowed()) preview = photo })
                                    ActionButton("사진 ${index + 1} 제거", Modifier.fillMaxWidth(), filled = false) {
                                        if (stillAllowed() && update(trip.tripId) { it.copy(photos = it.photos - photo) }) {
                                            status = "기록에서 사진을 제거했어요."
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (canEdit && diary.photos.size < MAX_DIARY_PHOTOS) {
                    ActionButton("사진 추가 · ${diary.photos.size}/$MAX_DIARY_PHOTOS", Modifier.fillMaxWidth(), filled = false) {
                        if (stillAllowed()) {
                            try {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } catch (_: ActivityNotFoundException) {
                                status = "이 기기에서 사진 선택 화면을 열 수 없어요."
                            }
                        }
                    }
                }
                if (canEdit) {
                    DiaryField("한 줄 메모", note, { note = it.take(160) }, 1)
                    DiaryField("여행 후기", review, { review = it.take(4000) }, 3)
                } else {
                    if (diary.note.isNotBlank()) DiaryText(diary.note)
                    if (diary.review.isNotBlank()) DiaryText(diary.review)
                }
                if (diary.sessions.isEmpty()) DiaryText("기록된 차박 시작·종료 시각이 없어요.")
                diary.sessions.forEach { stay ->
                    DiaryText("${diaryTime.format(Instant.ofEpochMilli(stay.startedAt))} 시작 · ${stay.campsite}")
                    stay.endedAt?.let { DiaryText("${diaryTime.format(Instant.ofEpochMilli(it))} 종료") }
                }
                if (canEdit && diary.sessions.isNotEmpty() && !diary.inProgress) {
                    ActionButton(if (diary.archived) "보관 해제" else "여행 보관", filled = false) {
                        if (stillAllowed() && update(trip.tripId) { it.copy(archived = !it.archived) }) {
                            status = if (diary.archived) "보관을 해제했어요." else "여행을 보관했어요."
                        }
                    }
                }
                if (legacyDiaries.isNotEmpty() && canEdit) {
                    ActionButton(if (showLegacy) "이전 기록 닫기" else "이전 기록 보기", filled = false) {
                        if (stillAllowed()) showLegacy = !showLegacy
                    }
                    if (showLegacy) legacyDiaries.forEach { old ->
                        DiaryText(listOf(old.date, old.place, old.title, old.mood, old.note).filter(String::isNotBlank).joinToString(" · "))
                        if (old.photoUri.isNotBlank()) {
                            DiaryImage(old.photoUri, "이전 기록 사진", Modifier.size(140.dp, 90.dp))
                        }
                    }
                }
            }
            if (canEdit && (note.trim() != diary.note || review.trim() != diary.review)) {
                DiaryText("수정한 내용을 저장해 주세요.")
            } else if (status.isNotBlank()) DiaryText(status)
            if (canEdit) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (diary.note.isNotBlank() || diary.review.isNotBlank()) {
                    ActionButton("저장한 글 공유", Modifier.weight(1f), filled = false) {
                        if (stillAllowed()) {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, listOf(diary.note, diary.review)
                                    .filter(String::isNotBlank).joinToString("\n\n"))
                            }
                            if (!launchDiaryShare(context, share)) status = "공유 화면을 열 수 없어요."
                        }
                    }
                }
                if (diary.photos.isNotEmpty()) {
                    ActionButton("사진 공유", Modifier.weight(1f), filled = false) {
                        if (stillAllowed()) {
                            val photos = ArrayList(diary.photos.map(Uri::parse))
                            val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "image/*"
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, photos)
                                clipData = ClipData.newRawUri("CHABAK", photos.first()).apply {
                                    photos.drop(1).forEach { addItem(ClipData.Item(it)) }
                                }
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            if (!launchDiaryShare(context, share)) status = "공유 화면을 열 수 없어요."
                        }
                    }
                }
                ActionButton("메모 저장", Modifier.weight(1f)) {
                    if (stillAllowed() && update(trip.tripId) { it.copy(note = note.trim(), review = review.trim()) }) {
                        keyboard?.hide()
                        focus.clearFocus()
                        status = "메모를 저장했어요."
                    }
                }
            }
        }
    }

    if (canEdit) preview?.let { photo ->
        Dialog(onDismissRequest = { preview = null }) {
            GlassPanel(Modifier.fillMaxWidth()) {
                DiaryImage(photo, "선택한 여행 사진", Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp),
                    large = true)
                ActionButton("닫기", Modifier.fillMaxWidth()) { preview = null }
            }
        }
    }
    LaunchedEffect(canEdit) {
        if (!canEdit) {
            preview = null
            keyboard?.hide()
            focus.clearFocus()
        }
    }
}

private fun launchDiaryShare(context: Context, intent: Intent): Boolean = try {
    context.startActivity(Intent.createChooser(intent, "여행 기록 공유"))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
private fun DiaryText(text: String) {
    Text(text, color = Color.White, fontSize = 25.sp)
}

@Composable
private fun DiaryField(label: String, value: String, onChange: (String) -> Unit, lines: Int) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 22.sp) },
        textStyle = TextStyle(color = Color.White, fontSize = 25.sp),
        singleLine = lines == 1,
        minLines = lines,
        maxLines = if (lines == 1) 1 else 6,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLabelColor = Gold, unfocusedLabelColor = Color.White,
            focusedBorderColor = Gold, unfocusedBorderColor = Color.White
        )
    )
}

private data class DiaryImageResult(val bitmap: ImageBitmap?)

@Composable
private fun DiaryImage(raw: String, description: String, modifier: Modifier, large: Boolean = false) {
    val resolver = LocalContext.current.contentResolver
    val result by produceState<DiaryImageResult?>(null, raw, large) {
        value = null
        val bitmap = withContext(Dispatchers.IO) {
            val uri = Uri.parse(raw).takeIf { it.scheme == "content" } ?: return@withContext null
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val maxEdge = if (large) 1400 else 450
                    val edge = maxOf(info.size.width, info.size.height)
                    decoder.setTargetSampleSize(((edge.toLong() + maxEdge - 1) / maxEdge).toInt().coerceAtLeast(1))
                }.asImageBitmap()
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        value = DiaryImageResult(bitmap)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = result?.bitmap
        if (image != null) {
            Image(image, description, Modifier.fillMaxSize(), contentScale = if (large) ContentScale.Fit else ContentScale.Crop)
        } else {
            Text(if (result == null) "사진 불러오는 중…" else "사진 미리보기 확인 필요",
                color = Color.White, fontSize = 18.sp)
        }
    }
}
