package com.chabak.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.util.Calendar
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

// CHABAK 4.7 | STEP047: 대형 3열 스크롤 휠 알람 시간 설정 | STEP045: 풍경과 음악 중심 감성 알람 | STEP044: 아침 일출 vs 낮·밤 즉시 전환 및 사용자 선택 알람 음악 | STEP043: 자연음 동기화 일출 크로스페이드 + 기존 화면 유지 | STEP041: 자연음 알람 예약 + 백그라운드 알람 서비스 | STEP040: 실시간 자연음 재생 / 일시정지 / 정지 / 다음곡 + 기존 STEP039 유지 | Pleos Connect API 34
// 기존 CHABAK 3.8 입지 판정, 추천 서버, 준비물 자동화는 유지합니다.
// 시안 이미지에서 참고한 그래픽들은 사용자 제공 이미지에서 파생된 샘플 에셋입니다.
// 캠핑장: 고캠핑 API 프록시 서버 연결 기능 추가. 예약/차량/음원 서비스는 아직 연동되지 않았습니다.
private val Midnight = Color(0xFF071C2B)
private val Pine = Color(0xFF11382F)
private val Frost = Color(0xFFFFFFFF)
private val Mint = Color(0xFFADD6A7)
internal val Gold = Color(0xFFFFD083)
private val Glass = Color(0x20FFFFFF) // 약 12.5% 불투명 흰색 유리
private val GlassBorder = Color(0x65FFFFFF)
private val Muted = Color(0xFFF1F4F3)

// STEP042 — 한국 캠핑 서비스 시연 기준: 기기의 UTC 설정과 무관하게 한국 표준시(KST) 사용.
// 정식 글로벌 서비스에서는 캠핑장 목적지/차량의 시간대를 적용할 수 있도록 분리합니다.
private val ChabakTimeZone = java.time.ZoneId.of("Asia/Seoul")
private val ChabakLegacyTimeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
private fun chabakNow(): LocalDateTime = LocalDateTime.now(ChabakTimeZone)
// 아침 인사는 05:00~10:59에만 표시합니다.
private data class TimeScene(val index: Int, val name: String, val greeting: String)
private fun timeScene(hour: Int): TimeScene = when (hour) {
    in 5..10 -> TimeScene(0, "아침", "좋은 아침이에요 ☀️")
    in 11..16 -> TimeScene(1, "낮", "오늘도 즐거운 차박 되세요!")
    in 17..19 -> TimeScene(2, "노을", "아름다운 저녁이에요 🌇")
    else -> TimeScene(3, "밤", "편안한 밤 보내세요 🌙")
}
private fun isMorningTime(): Boolean = chabakNow().hour in 5..10

class MainActivity : ComponentActivity() {
    // 알람 알림을 누르고 이미 실행 중인 앱으로 돌아왔을 때도 화면 변경 이벤트를 전달합니다.
    private var newAlarmIntentSerial by mutableIntStateOf(0)
    private val gleoIntents = Channel<Intent>(Channel.UNLIMITED)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == GleoCampingAction.ACTION) gleoIntents.trySend(intent)
        else newAlarmIntentSerial++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.action == GleoCampingAction.ACTION) {
            gleoIntents.trySend(intent)
        }
        window.statusBarColor = android.graphics.Color.rgb(6, 23, 34)
        window.navigationBarColor = android.graphics.Color.rgb(6, 23, 34)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Midnight)) { Chabak3App(newAlarmIntentSerial, gleoIntents) } }
    }
}

internal data class Place(
    val id: String, val name: String, val region: String, val detail: String,
    val picture: Int, val theme: String, val services: String,
    val address: String = "", val phone: String = "",
    val toiletCount: Int? = null, val showerCount: Int? = null,
    val sinkCount: Int? = null, val website: String = "",
    val reservationUrl: String = "", val siteType: String = "",
    val businessStatus: String = "", val live: Boolean = false,
    val campingPower: Boolean? = registeredCampingPower(services),
    val onsiteEvCharger: Boolean? = null,
    val nearbyEvCharger: Boolean? = null,
    val overnightAllowed: Boolean? = null,
    val kind: CampingPlaceKind = CampingPlaceKind.CAMPGROUND,
    val permit: CampingPermit? = null,
    val sourceUpdatedAt: String = ""
)
// 한국관광공사 고캠핑 공개 기록(2026-09-23 수집본)에 등장하는 실제 캠핑장 일부.
// 조회 서버 미연결 시에만 노출하는 참고 목록이며 실시간 영업/빈자리 정보는 아닙니다.
// 이미지는 장소 사진이 아니라 CHABAK 테마 배경입니다.
private val referencePlaces = listOf(
    Place("ref_pocheon", "계곡과소나무 캠핑장", "경기", "포천 · 산", R.drawable.chabak_day_photo, "산", "시설 정보 확인 필요", "경기도 포천시 일동면 무리울길 299", "010-4842-2689", siteType="일반야영장"),
    Place("ref_paju", "캠핑엔조이", "경기", "파주 · 도심", R.drawable.chabak_evening_photo, "도심", "시설 정보 확인 필요", "경기도 파주시 적성면 윗배우니길 68", "031-959-1762", siteType="자동차야영장"),
    Place("ref_hanam", "캠핑플레이", "경기", "하남 · 숲", R.drawable.chabak_day_photo, "숲", "시설 정보 확인 필요", "경기도 하남시 검단산로250번길 152 (창우동)", "010-8512-8939", siteType="일반야영장"),
    Place("ref_yeoju", "여주참숯마을 캠핑장", "경기", "여주", R.drawable.chabak_lakeside_car, "자연", "시설 정보 확인 필요", "경기도 여주시 강천면 걸촌동길 40", "031-886-1119", siteType="일반야영장"),
    Place("ref_gapy", "가평물의나라 오토캠핑장", "경기", "가평 · 계곡", R.drawable.chabak_lakeside_car, "계곡", "시설 정보 확인 필요", "경기도 가평군 조종면 명지산로 475", siteType="일반야영장"),
    Place("ref_icheon", "어름박골캠핑장", "경기", "이천", R.drawable.chabak_day_photo, "자연", "시설 정보 확인 필요", "경기도 이천시 마장면 작촌로35번길 317-31", siteType="일반야영장"),
    Place("ref_namyangju", "뷰66캠핑장", "경기", "남양주", R.drawable.chabak_evening_photo, "강변", "시설 정보 확인 필요", "경기도 남양주시 강변북로632번길 84 (수석동)", siteType="일반야영장"),
    Place("ref_yangyang", "해담구룡령 야영장", "강원", "양양 · 계곡", R.drawable.chabak_morning_photo, "계곡", "시설 정보 확인 필요", "강원특별자치도 양양군 서면 구룡령로 2066", "033-673-8887", siteType="일반야영장"),
    Place("ref_yangyang2", "양양오색 휴랜드 야영장", "강원", "양양 · 숲", R.drawable.chabak_day_photo, "숲", "시설 정보 확인 필요", "강원특별자치도 양양군 서면 관대문길 99", "033-672-9189", siteType="일반야영장"),
    Place("ref_yeongwol", "필리가 캠핑", "강원", "영월 · 산", R.drawable.chabak_night_photo, "산", "시설 정보 확인 필요", "강원특별자치도 영월군 김삿갓면 두릉골길 11", "010-9857-5901", siteType="자동차야영장"),
    Place("ref_cheonan", "천안시 국민여가캠핑장", "충청", "천안", R.drawable.chabak_morning_photo, "자연", "시설 정보 확인 필요", "충청남도 천안시 동남구 목천읍 교촌7길 46-13", "041-529-5113", siteType="자동차야영장"),
    Place("ref_jecheon", "덕동 히든밸리 캠프펜션", "충청", "제천 · 강", R.drawable.chabak_day_photo, "강", "시설 정보 확인 필요", "충청북도 제천시 백운면 덕동로 207-3", "010-7558-6981", siteType="일반야영장"),
    Place("ref_biseul", "비슬산 숲속 오토캠핑장", "경상", "대구 · 산", R.drawable.chabak_morning_photo, "산", "시설 정보 확인 필요", "대구광역시 달성군 유가읍 일연선사길 61", "053-659-4400", siteType="일반야영장"),
    Place("ref_pohang", "썬라이즈 코스트", "경상", "포항 · 바다", R.drawable.chabak_evening_photo, "바다", "시설 정보 확인 필요", "경상북도 포항시 남구 호미곶면 호미로 1170", siteType="자동차야영장"),
    Place("ref_damyang", "가족소풍", "전라", "담양 · 계곡", R.drawable.chabak_day_photo, "계곡", "시설 정보 확인 필요", "전남광주통합특별시 담양군 용면 용소길 6", "061-383-4811", siteType="자동차야영장"),
    Place("ref_jeju", "김녕해수욕장 야영장", "제주", "제주 · 해변", R.drawable.chabak_lakeside_car, "해변", "시설 정보 확인 필요", "제주특별자치도 제주시 구좌읍 김녕리 497-4", siteType="일반야영장")
)
internal fun storedCampsitePicture(id: String, theme: String): Int =
    referencePlaces.firstOrNull { it.id == id }?.picture ?: when {
        theme.contains("해변") || theme.contains("바다") -> R.drawable.chabak_evening_photo
        theme.contains("산") || theme.contains("숲") -> R.drawable.chabak_day_photo
        else -> R.drawable.chabak_lakeside_car
    }

private data class DiaryEntry(val title: String, val date: String, val place: String, val mood: String,
                              val note: String, val photoUri: String = "")
private data class PlacePreview(val place: Place, val date: LocalDate)
private data class ReviewedStay(val visitDate: LocalDate, val nights: Int, val checkedOn: LocalDate)

private class AppState(private val context: Context) {
    private val pref = context.getSharedPreferences("chabak_3", Context.MODE_PRIVATE)

    private val tripStore = TripStore(context)
    private val legacyTrip by lazy { readLegacyTrip(pref.all, referencePlaces, chabakNow().toLocalDate().plusDays(1)) }
    private var tripCatalog by mutableStateOf(
        try {
            tripStore.loadOrMigrate { legacyTrip }
        } catch (_: SQLiteException) {
            Toast.makeText(context, "여행 저장소를 열 수 없어요. 기존 설정을 표시합니다.", Toast.LENGTH_LONG).show()
            TripCatalog(listOf(legacyTrip), legacyTrip.tripId)
        }
    )

    val savedTrips: List<Trip> get() = tripCatalog.trips
    val activeTrip: Trip get() = tripCatalog.activeTrip

    var page by mutableStateOf("home")
    val route = mutableStateListOf<String>()
    // -1이면 자동: 앱 실행 중에도 시간대에 맞춰 배경이 변경됩니다.
    var themeIndex by mutableIntStateOf(-1)
    var wakeScenePreview by mutableStateOf(false)
    var wakeSceneLive by mutableStateOf(false)
    var wakeQuickMode by mutableStateOf(false) // 아침 이외에는 즉시 화면 전환
    var selectedWakeTrackName by mutableStateOf(pref.getString("wakeMusicName", "") ?: "")
    var selectedWakeTrackUri by mutableStateOf(pref.getString("wakeMusicUri", "") ?: "")
    var wakeTransitionSerial by mutableIntStateOf(0)
    // 기기 차량 정차 여부를 아직 알 수 없으므로 자동 화면 전환은 사용자 선택 시에만 활성화.
    var sunriseForegroundOptIn by mutableStateOf(pref.getBoolean("sunriseForegroundOptIn", false))
    var region by mutableStateOf(pref.getString("region", "경기") ?: "경기")
    val pickedPlaceId: String get() = activeTrip.place.id
    var places by mutableStateOf(referencePlaces)
    var livePlacesLoaded by mutableStateOf(false)
    var campsLoading by mutableStateOf(false)
    var campsMessage by mutableStateOf("내장 참고 목록 · 2026-09-23 수집")
    var requiresToilet by mutableStateOf(false)
    var requiresElectric by mutableStateOf(false)
    private var campsSeq = 0
    private val reviewedSites by lazy { ReviewedCampingSites.load(context) }
    private var placePreview by mutableStateOf<PlacePreview?>(null)
    var campingCriteria by mutableStateOf(CampingCriteria(region = region, toilet = true))
        private set
    private var recAskedClean = false
    private var appliedCriteria by mutableStateOf<CampingCriteria?>(null)
    private var appliedStay by mutableStateOf<ReviewedStay?>(null)
    val recommendationStarted: Boolean get() = appliedCriteria != null
    val recommendationIsCurrent: Boolean get() {
        val stay = appliedStay
        return campingCriteria == appliedCriteria && (stay == null ||
            stay == ReviewedStay(activeTrip.visitDate, nights, chabakNow().toLocalDate()))
    }
    var recommendationLoading by mutableStateOf(false)
        private set
    var recommendedPlaces by mutableStateOf<List<SuggestedPlace>>(emptyList())
        private set
    var recommendationMessage by mutableStateOf("조건을 선택해 등록 캠핑장을 비교해 보세요.")
        private set
    var recommendationNotices by mutableStateOf<List<String>>(emptyList())
        private set
    var recommendationSeq by mutableIntStateOf(0)
        private set
    var packingDetailed by mutableStateOf(pref.getBoolean("packingDetailed", true))
        private set
    val tripDate: String get() = activeTrip.visitDate.toString()
    val people: Int get() = activeTrip.people
    val nights: Int get() = activeTrip.nights
    val packed: Set<String> get() = activeTrip.packedItemIds
    var assigned by mutableStateOf(setOf<String>())
    var customItem by mutableStateOf("")

    var music by mutableStateOf(pref.getString("genre", "재즈") ?: "재즈")
    var ambience by mutableStateOf(pref.getString("ambience", "별빛") ?: "별빛")
    var natureSound by mutableStateOf(pref.getString("natureSound", "숲소리") ?: "숲소리")
    // STEP040: 자연음 실제 오디오 상태와 음량. 음악 스트리밍 추천 시연과 구분합니다.
    var naturePlaybackStatus by mutableStateOf("정지")
    var naturePlaybackError by mutableStateOf("")
    var naturePlaybackProgress by mutableFloatStateOf(0f)
    var natureVolume by mutableFloatStateOf(pref.getFloat("natureVolume", 0.55f))
    fun saveNatureVolume() { pref.edit().putFloat("natureVolume", natureVolume).apply() }
    var wakeHour by mutableIntStateOf(pref.getInt("wakeHour", 7))
    var wakeMinute by mutableIntStateOf(pref.getInt("wakeMinute", 0))
    var wakeSound by mutableStateOf(
        (pref.getString("wakeSound", "숲소리") ?: "숲소리")
            .takeIf { it in listOf("숲소리", "빗소리", "파도소리", "백색소음") } ?: "숲소리"
    )
    var morningFade by mutableIntStateOf(pref.getInt("morningFade", 15))
    var morningLevel by mutableFloatStateOf(pref.getFloat("morningLevel", 0.5f))
    var morningEnabled by mutableStateOf(
        pref.getBoolean("morningEnabled", false) &&
                pref.getLong(ChabakMorningScheduler.KEY_TIME, 0L) > System.currentTimeMillis()
    )
    var morningStatus by mutableStateOf(ChabakMorningScheduler.status(context))
    var dayTitle by mutableStateOf("")
    var dayNote by mutableStateOf("")
    var dayMood by mutableStateOf("편안해요")
    var dayPhoto by mutableStateOf("")
    var editingDiary by mutableIntStateOf(-1)
    var diaries by mutableStateOf(readDiaries())
    var weatherTemp by mutableStateOf<String?>(null)
    var weatherLabel by mutableStateOf("연결 중")
    var weatherChecked by mutableStateOf("")
    private var packingObservation by mutableStateOf<PackingObservation?>(null)
    var playlistVersion by mutableIntStateOf(pref.getInt("playlistVersion",0))
    var demoPlayStatus by mutableStateOf("정지")
    var demoTrackIndex by mutableIntStateOf(0)
    fun demoNextTrack() { demoTrackIndex=(demoTrackIndex+1)%4; demoPlayStatus="정지" }
    fun demoPreviousTrack() { demoTrackIndex=if(demoTrackIndex==0) 3 else demoTrackIndex-1; demoPlayStatus="정지" }
    fun demoPlayPause() { demoPlayStatus=if(demoPlayStatus=="재생 중") "일시정지" else "재생 중" }
    fun demoStop() { demoPlayStatus="정지" }
    var phoneDraft by mutableStateOf("")
    fun placePhone(id:String):String = pref.getString("phone_$id","") ?: ""
    fun savePhone(id:String,value:String) { pref.edit().putString("phone_$id",value.trim()).apply(); phoneDraft=value.trim() }
    fun shufflePlaylist() { playlistVersion++;demoTrackIndex=0;demoPlayStatus="정지";pref.edit().putInt("playlistVersion",playlistVersion).apply() }
    fun suggestedTracks(): List<String> {
        val mood = when { weatherLabel.contains("비") -> "빗소리"; weatherLabel.contains("눈") -> "겨울밤"; else -> "숲속" }
        val sets = mapOf(
            "재즈" to listOf("고요한 호숫가 재즈","달빛 아래 스윙","밤공기와 피아노","새벽의 느린 재즈","은은한 재즈 기타"),
            "샹송" to listOf("호숫가 샹송","느린 저녁 산책","별빛의 프랑스어 멜로디","커피 향의 밤","차분한 아코디언"),
            "K-POP" to listOf("기분 좋은 드라이브","노을 감성 플레이","별 보며 듣는 발라드","잔잔한 인디팝","아침을 여는 팝"),
            "클래식" to listOf("잔잔한 피아노","밤의 현악 사중주","호숫가 첼로","아침 클래식","조용한 소나타")
        )
        val base = sets[music] ?: listOf("${music} · 가벼운 시작","${music} · 산책","${music} · 편안한 휴식","${music} · 밤의 마무리","${music} · 아침")
        return (0..3).map { i -> "${i+1}. ${base[(i+playlistVersion)%base.size]}" } + "환경 추천 · ${mood} ASMR"
    }
    fun refreshWeather() {
        val currentRegion = region
        val loc = when(currentRegion) {
            "강원" -> Pair(37.8813,127.7298)
            "충청" -> Pair(36.6424,127.4890)
            "경상" -> Pair(35.8714,128.6014)
            "전라" -> Pair(35.8242,127.1480)
            "제주" -> Pair(33.4996,126.5312)
            "경기" -> Pair(37.4138,127.5183)
            else -> Pair(37.5665,126.9780)
        }
        Thread {
            var temp: Int? = null
            var observedOn: LocalDate? = null
            var status = "날씨 연결 안 됨"
            try {
                val endpoint="https://api.open-meteo.com/v1/forecast?latitude=${loc.first}&longitude=${loc.second}&current=temperature_2m,weather_code&timezone=Asia%2FSeoul"
                val conn=(URL(endpoint).openConnection() as HttpURLConnection).apply { connectTimeout=4500;readTimeout=4500 }
                try {
                    val json=JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                    temp = json.optDouble("temperature_2m").takeIf { it.isFinite() }?.toInt()
                    observedOn = json.stringField("time").takeIf { it.isNotBlank() }?.let {
                        try { LocalDateTime.parse(it).toLocalDate() }
                        catch (_: java.time.format.DateTimeParseException) { null }
                    }
                    status=when(json.optInt("weather_code",-1)) {
                        0->"맑음";1,2->"구름 조금";3->"흐림";45,48->"안개"
                        51,53,55,56,57->"이슬비";61,63,65,66,67,80,81,82->"비"
                        71,73,75,77,85,86->"눈";95,96,99->"뇌우";else->"관측 중"
                    }
                } finally { conn.disconnect() }
            } catch (_:Exception) { }
            Handler(Looper.getMainLooper()).post {
                if (region != currentRegion) return@post
                val temperature = temp
                val day = observedOn
                weatherTemp = temperature?.let { "${it}°" }
                weatherLabel = status
                weatherChecked = if (temperature != null) "Open-Meteo · $currentRegion 대표지점" else "네트워크/날씨 연결 확인"
                packingObservation = if (temperature != null && day != null) {
                    PackingObservation(currentRegion, day, temperature, status)
                } else null
            }
        }.start()
    }

    val selectedPlace: Place get() = activeTrip.place

    fun refreshCamps(query: String = "") {
        campsLoading = true
        val currentRegion = region
        val seq = ++campsSeq
        Thread {
            val result = try {
                GoCampingCatalog.search(currentRegion, query)
            } catch (_: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post {
                if (seq != campsSeq || region != currentRegion) return@post
                places = result?.places ?: referencePlaces
                livePlacesLoaded = result != null
                campsMessage = if (result != null) {
                    val day = goCampingCollectionDay(result.updatedAt)
                    "고캠핑 공식 API · 조회 ${result.places.size}곳" + (day?.let { " · 수집 $it" } ?: "")
                } else {
                    "고캠핑 API 연결 실패 · 내장 참고 목록"
                }
                campsLoading = false
            }
        }.start()
    }

    // 사용자가 이미 선택한 지역은 유지하고 명시적으로 요청한 지역만 덮어씁니다.
    fun requestRecommendation(utterance: String) {
        val q = utterance.trim().ifBlank { "자연 가까운 자동차야영장 추천해줘" }
        val lower = q.lowercase(Locale.KOREA)
        val districts = listOf(
            "가평", "연천", "남양주", "포천", "파주", "양평", "여주", "이천", "용인", "안성", "수원",
            "춘천", "양양", "속초", "강릉", "홍천", "평창", "원주", "영월", "제천", "충주", "청주", "천안", "보령", "태안", "단양",
            "포항", "경주", "안동", "울진", "거제", "통영", "부산", "대구", "담양", "전주", "여수", "순천", "제주", "서귀포"
        )
        val district = districts.firstOrNull { lower.contains(it) } ?: ""
        val matchedRegion = when {
            listOf("경기", "경기도").any { lower.contains(it) } -> "경기"
            listOf("강원", "강원도").any { lower.contains(it) } -> "강원"
            listOf("충청", "충북", "충남", "대전", "세종").any { lower.contains(it) } -> "충청"
            listOf("경상", "경북", "경남", "부산", "대구", "울산").any { lower.contains(it) } -> "경상"
            listOf("전라", "전북", "전남", "광주").any { lower.contains(it) } -> "전라"
            lower.contains("제주") -> "제주"
            district in listOf("가평", "연천", "남양주", "포천", "파주", "양평", "여주", "이천", "용인", "안성", "수원") -> "경기"
            district in listOf("춘천", "양양", "속초", "강릉", "홍천", "평창", "원주", "영월") -> "강원"
            district in listOf("제천", "충주", "청주", "천안", "보령", "태안", "단양") -> "충청"
            district in listOf("포항", "경주", "안동", "울진", "거제", "통영", "부산", "대구") -> "경상"
            district in listOf("담양", "전주", "여수", "순천") -> "전라"
            district in listOf("서귀포", "제주") -> "제주"
            else -> null
        }
        val nature = when {
            "계곡" in lower -> "계곡"
            "바다" in lower || "해변" in lower || "해수욕" in lower -> "바다"
            "호수" in lower -> "호수"
            "강가" in lower || "강변" in lower -> "강"
            "숲" in lower || "산림" in lower || "수목" in lower -> "숲"
            "산속" in lower || "산 근처" in lower || "산 캠핑" in lower -> "산"
            else -> ""
        }
        recAskedClean = listOf("깨끗", "청결", "위생").any { it in lower }
        campingCriteria = CampingCriteria(
            region = matchedRegion ?: region, district = district, nature = nature,
            toilet = "화장실" in lower || recAskedClean,
            campingPower = requestedCampingPower(lower),
            store = listOf("편의점", "매점", "마트").any { it in lower },
            quiet = listOf("조용", "한적", "고요", "한산").any { it in lower },
            evCharging = requestedEvCharging(lower)
        )
        fetchRecommendations()
    }

    fun changeCampingCriteria(value: CampingCriteria) {
        campingCriteria = value
        recAskedClean = false
    }

    fun searchFromGleo(query: GleoCampingAction.Query, onResult: (GleoCampingAction.Outcome, List<String>) -> Unit) {
        changeCampingCriteria(CampingCriteria(
            region = query.region, district = query.district, nature = query.nature,
            toilet = query.toilet, campingPower = query.electric, store = query.store
        ))
        navigate("recommendations")
        fetchRecommendations(onResult = onResult)
    }

    fun fetchRecommendations(
        onResult: ((GleoCampingAction.Outcome, List<String>) -> Unit)? = null
    ) {
        val criteria = campingCriteria
        val trip = activeTrip
        val today = chabakNow().toLocalDate()
        val useGoCamping = criteria.kind == CampingPlaceKind.CAMPGROUND
        val source = if (useGoCamping) emptyList() else reviewedSites
        val askedClean = recAskedClean
        if (region != criteria.region) selectRegion(criteria.region)
        val seq = ++recommendationSeq
        appliedCriteria = criteria
        appliedStay = if (useGoCamping) null else ReviewedStay(trip.visitDate, trip.nights, today)
        recommendationLoading = true
        recommendationNotices = emptyList()
        recommendationMessage = if (useGoCamping) "고캠핑 등록 자료를 비교하는 중이에요…" else "관리기관의 이용 허용 기록을 확인하는 중이에요…"
        recommendedPlaces = emptyList()

        Thread {
            val outcome = try {
                if (useGoCamping) GoCampingCatalog.recommend(criteria) else {
                    val matches = source.asSequence()
                        .filter { it.isVerifiedStay(today, trip.visitDate, trip.nights) && criteria.matches(it) }
                        .take(MAX_CAMPING_RECOMMENDATIONS)
                        .map { SuggestedPlace(it, listOf("관리기관의 여행 기간 이용 허용 기록 확인")) }
                        .toList()
                    CampingRecommendations(matches)
                }
            } catch (_: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post {
                if (seq != recommendationSeq) {
                    onResult?.invoke(GleoCampingAction.Outcome.SUPERSEDED, emptyList())
                    return@post
                }
                recommendationLoading = false
                if (!recommendationIsCurrent) {
                    recommendationMessage = "추천 보기를 눌러 현재 조건의 결과를 확인해 주세요."
                    onResult?.invoke(GleoCampingAction.Outcome.SUPERSEDED, emptyList())
                    return@post
                }
                val results = outcome?.items.orEmpty()
                recommendedPlaces = results
                recommendationNotices = outcome?.notices.orEmpty() +
                    if (askedClean) listOf("화장실 청결도는 공개 자료에서 확인할 수 없어요.") else emptyList()
                recommendationMessage = when {
                    outcome == null -> "추천 서버에 연결하지 못했어요. 개발 PC의 고캠핑 서버를 확인해 주세요."
                    results.isEmpty() && criteria.evCharging && useGoCamping -> "충전소가 확인된 고캠핑 후보를 찾을 수 없어요."
                    results.isEmpty() -> "조건에 맞는 장소가 없어요. 환경이나 필수 시설을 조정해 보세요."
                    useGoCamping -> "고캠핑 공식 자료 · 추천 ${results.size}곳"
                    else -> "관리기관 허용 자료 · 추천 ${results.size}곳"
                }
                if (onResult != null) {
                    val result = when {
                        page != "recommendations" -> GleoCampingAction.Outcome.SUPERSEDED
                        outcome == null -> GleoCampingAction.Outcome.SERVICE_FAILURE
                        results.isEmpty() -> GleoCampingAction.Outcome.NO_RESULTS
                        else -> GleoCampingAction.Outcome.FOUND
                    }
                    onResult(result, if (result == GleoCampingAction.Outcome.FOUND) results.map { it.place.name } else emptyList())
                }
            }
        }.start()
    }

    fun navigate(to: String) { if (page != to) { route.add(page); page = to } }
    fun back() {
        if (page == "place_detail") placePreview = null
        page = if (route.isNotEmpty()) route.removeAt(route.lastIndex) else "home"
    }
    fun home() { placePreview = null; route.clear(); page = "home" }
    fun selectRegion(value: String) {
        if (region != value) {
            region = value
            weatherTemp = null
            weatherLabel = "현재 날씨 확인 중"
            weatherChecked = ""
            packingObservation = null
            pref.edit().putString("region", value).apply()
        }
        refreshCamps()
    }
    private fun saveTripChange(operation: () -> TripCatalog): Boolean {
        val previous = activeTrip
        return try {
            tripCatalog = operation()
            if (previous.tripId != activeTrip.tripId || previous.place.id != activeTrip.place.id) {
                assigned = emptySet()
                customItem = ""
            }
            true
        } catch (_: SQLiteException) {
            Toast.makeText(context, "여행 정보를 저장할 수 없어요. 다시 시도해 주세요.", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun updateTrip(change: (Trip) -> Trip): Boolean =
        saveTripChange { tripStore.updateTrip(activeTrip.tripId, change) }

    val travelPlace: Place? get() = usablePlanningPlace(selectedPlace, activeTrip.visitDate, nights)

    fun saveTripTravel(tripId: String, travel: TripTravel): Boolean =
        activeTrip.tripId == tripId && updateTrip { it.copy(travel = travel) }

    fun saveTripEnergy(tripId: String, plan: TripEnergyPlan): Boolean =
        activeTrip.tripId == tripId && updateTrip { it.copy(energyPlan = plan) }

    fun closeTripStore() = tripStore.close()

    private fun usablePlanningPlace(place: Place, visitDate: LocalDate, nights: Int): Place? =
        if (place.kind == CampingPlaceKind.CAMPGROUND) place else {
            ReviewedCampingSites.usablePlace(place, reviewedSites, chabakNow().toLocalDate(), visitDate, nights)
        }

    val eligibleCampingKinds: List<CampingPlaceKind> get() {
        val today = chabakNow().toLocalDate()
        return listOf(CampingPlaceKind.CAMPGROUND) + reviewedSites.filter {
            it.isVerifiedStay(today, activeTrip.visitDate, nights)
        }.map { it.kind }.distinct()
    }

    val detailPlace: Place get() {
        val place = placePreview?.place ?: selectedPlace
        return usablePlanningPlace(place, detailDate, nights) ?: place
    }

    val detailDate: LocalDate get() = placePreview?.date ?: activeTrip.visitDate
    val detailIsPreview: Boolean get() = placePreview != null
    val detailCanPlan: Boolean get() = usablePlanningPlace(detailPlace, detailDate, nights) != null

    fun previewPlace(place: Place) {
        placePreview = PlacePreview(place, activeTrip.visitDate)
        navigate("place_detail")
    }

    fun createTrip(): Boolean {
        val start = usablePlanningPlace(selectedPlace, activeTrip.visitDate, nights) ?: referencePlaces.first()
        return saveTripChange { tripStore.createTrip(activeTrip.tripId, start) }
    }

    fun openTrip(tripId: String): Boolean = saveTripChange { tripStore.openTrip(tripId) }.also {
        if (it) placePreview = null
    }

    fun setDetailDate(placeId: String, tripId: String, value: LocalDate) {
        if (page != "place_detail" || detailPlace.id != placeId || activeTrip.tripId != tripId) return
        val preview = placePreview
        if (preview != null) {
            placePreview = preview.copy(date = value)
        } else if (canUseStay(selectedPlace, value, nights)) {
            updateTrip { it.copy(visitDate = value) }
        }
    }

    fun confirmDetail(): Boolean {
        val preview = placePreview
        val place = usablePlanningPlace(detailPlace, detailDate, nights) ?: run {
            showStayNeedsReview()
            return false
        }
        if (preview == null) return true
        return updateTrip { it.withPlace(place).copy(visitDate = preview.date) }.also { saved ->
            if (saved) placePreview = null
        }
    }

    private fun canUseStay(place: Place, date: LocalDate, nights: Int): Boolean =
        (usablePlanningPlace(place, date, nights) != null).also { if (!it) showStayNeedsReview() }

    private fun showStayNeedsReview() {
        Toast.makeText(context, "여행 기간의 차량 숙박 허용을 확인해 주세요.", Toast.LENGTH_LONG).show()
    }

    fun savePeople(value: Int) { updateTrip { it.copy(people = value.coerceIn(1, 8)) } }

    fun saveNights(value: Int) {
        val newNights = value.coerceIn(1, 7)
        if (canUseStay(selectedPlace, activeTrip.visitDate, newNights)) updateTrip { it.copy(nights = newNights) }
    }
    fun togglePacked(id: String) {
        updateTrip { trip ->
            val checked = trip.packedItemIds
            trip.copy(packedItemIds = if (id in checked) checked - id else checked + id)
        }
    }
    fun toggleAssigned(id: String) { assigned = if (id in assigned) assigned - id else assigned + id }
    fun addExtra() {
        val item = customItem.trim()
        if (item.isEmpty()) return
        if (updateTrip { trip ->
                if (item in trip.extraItems) trip else trip.copy(extraItems = trip.extraItems + item)
            }) customItem = ""
    }
    fun saveRest() { pref.edit().putString("genre", music).putString("ambience", ambience).putString("natureSound", natureSound).apply() }
    fun saveMorning() { pref.edit().putInt("wakeHour", wakeHour).putInt("wakeMinute", wakeMinute).putString("wakeSound", wakeSound).putInt("morningFade", morningFade).putFloat("morningLevel", morningLevel).putBoolean("morningEnabled", morningEnabled).putBoolean("sunriseForegroundOptIn", sunriseForegroundOptIn).apply() }
    fun openDiary(index: Int) {
        editingDiary = index
        val d = diaries.getOrNull(index)
        dayTitle = d?.title ?: "${LocalDate.now().monthValue}월 ${LocalDate.now().dayOfMonth}일의 차박"
        dayNote = d?.note ?: ""
        dayMood = d?.mood ?: "편안해요"
        dayPhoto = d?.photoUri ?: ""
    }
    fun saveDiary() {
        val v = DiaryEntry(dayTitle.ifBlank { "오늘의 차박" }, LocalDate.now().toString(), selectedPlace.name, dayMood, dayNote, dayPhoto)
        val existing = editingDiary in diaries.indices
        diaries = if (existing) diaries.toMutableList().also { it[editingDiary] = v } else listOf(v) + diaries
        if (!existing) editingDiary = 0
        val array = JSONArray()
        for (d in diaries) array.put(JSONObject().put("title",d.title).put("date",d.date).put("place",d.place).put("mood",d.mood).put("note",d.note).put("photo",d.photoUri))
        pref.edit().putString("diaries", array.toString()).apply()
    }
    private fun readDiaries(): List<DiaryEntry> = try {
        val arr = JSONArray(pref.getString("diaries", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { DiaryEntry(it.optString("title"), it.optString("date"), it.optString("place"), it.optString("mood"), it.optString("note"), it.optString("photo")) } }
    } catch (_: Exception) { emptyList() }
    fun togglePackingDetail() {
        packingDetailed = !packingDetailed
        pref.edit().putBoolean("packingDetailed", packingDetailed).apply()
    }

    fun seasonName(): String = activeTrip.packingSeason()

    private fun tripObservation(): PackingObservation? =
        packingObservation?.forTrip(activeTrip, chabakNow().toLocalDate())?.takeIf {
            usablePlanningPlace(selectedPlace, activeTrip.visitDate, nights) != null
        }

    fun packingWeatherLabel(): String =
        if (tripObservation() != null) "지역 현재 날씨 참고 · 방문일 예보 확인 필요" else "방문일 예보 · 확인 필요"

    fun packs(): List<PackItem> {
        val place = usablePlanningPlace(selectedPlace, activeTrip.visitDate, nights)
        return tripPackItems(activeTrip, place, packingObservation, chabakNow().toLocalDate())
    }

    fun packingExplanation(): String = buildString {
        append("${seasonName()} · ${people}명 · ${nights}박 ${nights + 1}일 기준")
        if (usablePlanningPlace(selectedPlace, activeTrip.visitDate, nights) != null) {
            append(" · ${selectedPlace.name}")
        } else {
            append(" · 장소 이용 허용 재확인 필요")
        }
    }

}

// Generated ambient loops are packaged in res/raw, so they play without Internet.
// This is NOT a licensed music streaming service. Audio starts only with an explicit user action.
private val natureTrackNames = listOf("숲소리", "빗소리", "파도소리", "백색소음")
private val LocalNatureAudio = compositionLocalOf<NatureAudioEngine?> { null }

private class NatureAudioEngine(private val context: Context, private val state: AppState) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            Handler(Looper.getMainLooper()).post { pause() }
        }
    }

    private fun resourceId(name: String): Int = when (name) {
        "빗소리" -> R.raw.chabak_rain
        "파도소리" -> R.raw.chabak_waves
        "백색소음" -> R.raw.chabak_white_noise
        else -> R.raw.chabak_forest
    }
    private fun acquireFocus(): Boolean {
        if (focusRequest != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        return if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
            true
        } else {
            state.naturePlaybackError = "다른 오디오가 사용 중이에요. 잠시 후 다시 눌러 주세요."
            false
        }
    }
    private fun releaseFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
    private fun disposePlayer() {
        player?.let { p ->
            try { p.stop() } catch (_: Exception) { }
            try { p.release() } catch (_: Exception) { }
        }
        player = null
    }
    fun start() {
        if (state.natureSound !in natureTrackNames) {
            state.natureSound = "숲소리"
            state.saveRest()
        }
        if (!acquireFocus()) return
        try {
            if (player == null) {
                player = MediaPlayer.create(context, resourceId(state.natureSound))
                    ?: error("자연음 파일을 읽지 못했어요.")
                player?.isLooping = true
            }
            player?.setVolume(state.natureVolume, state.natureVolume)
            player?.start()
            state.naturePlaybackStatus = "재생 중"
            state.naturePlaybackError = ""
        } catch (_: Exception) {
            disposePlayer()
            releaseFocus()
            state.naturePlaybackStatus = "정지"
            state.naturePlaybackProgress = 0f
            state.naturePlaybackError = "음원을 열 수 없어요. res/raw의 ogg 파일 4개를 확인해 주세요."
        }
    }
    fun pause() {
        try { if (player?.isPlaying == true) player?.pause() } catch (_: Exception) { }
        if (state.naturePlaybackStatus == "재생 중") state.naturePlaybackStatus = "일시정지"
        releaseFocus()
    }
    fun toggle() {
        if (state.naturePlaybackStatus == "재생 중") pause() else start()
    }
    fun stop() {
        disposePlayer()
        releaseFocus()
        state.naturePlaybackStatus = "정지"
        state.naturePlaybackProgress = 0f
    }
    fun select(sound: String) {
        val wasPlaying = state.naturePlaybackStatus == "재생 중"
        stop()
        state.natureSound = sound
        state.saveRest()
        state.naturePlaybackError = ""
        if (wasPlaying && sound in natureTrackNames) start()
    }
    fun next() {
        val current = natureTrackNames.indexOf(state.natureSound)
        select(natureTrackNames[(current + 1).coerceAtLeast(0) % natureTrackNames.size])
    }
    fun previous() {
        val current = natureTrackNames.indexOf(state.natureSound).coerceAtLeast(0)
        select(natureTrackNames[(current + natureTrackNames.size - 1) % natureTrackNames.size])
    }
    fun setVolume(value: Float) {
        state.natureVolume = value.coerceIn(0f, 1f)
        state.saveNatureVolume()
        try { player?.setVolume(state.natureVolume, state.natureVolume) } catch (_: Exception) { }
    }
    fun progress(): Float {
        return try {
            val p = player
            if (p == null || p.duration <= 0) 0f
            else (p.currentPosition.toFloat() / p.duration).coerceIn(0f, 1f)
        } catch (_: Exception) { 0f }
    }
    fun release() { stop() }
}

@Composable
private fun Chabak3App(alarmIntentSerial: Int, gleoIntents: Channel<Intent>) {
    val context = LocalContext.current
    val s = remember { AppState(context) }
    val vehicleEnergy = rememberVehicleEnergy(s.page == "energy" || s.page == "stay")
    var tripPickerOpen by rememberSaveable { mutableStateOf(false) }
    val recommendationScroll = rememberScrollState()
    LaunchedEffect(s.recommendationSeq) { recommendationScroll.scrollTo(0) }
    DisposableEffect(s) { onDispose { s.closeTripStore() } }
    LaunchedEffect(gleoIntents) {
        for (intent in gleoIntents) {
            GleoCampingAction.handle(context.applicationContext, intent, s::searchFromGleo)
        }
    }
    var wallClock by remember { mutableStateOf(chabakNow()) }
    LaunchedEffect(Unit) { while (true) { wallClock = chabakNow(); delay(10_000L) } }
    val nowScene = timeScene(wallClock.hour)
    val appPrefs = remember(context) { context.getSharedPreferences("chabak_3", Context.MODE_PRIVATE) }

    // 알림 탭으로 진입하는 경우에만 화면을 열고, 밤 시험 알람은 모닝 설정으로 이동합니다.
    LaunchedEffect(alarmIntentSerial) {
        val activity = context as? ComponentActivity
        if (activity?.intent?.getBooleanExtra("open_morning", false) == true) {
            val triggered = appPrefs.getLong(ChabakMorningScheduler.KEY_LAST_TRIGGER, 0L)
            val test = appPrefs.getBoolean(ChabakMorningScheduler.KEY_LAST_WAS_TEST, false)
            val fresh = triggered > 0L && System.currentTimeMillis() - triggered in 0L..(5 * 60_000L)
            if (fresh) {
                s.wakeSceneLive = true
                s.wakeScenePreview = false
                s.wakeQuickMode = !isMorningTime()
                s.wakeTransitionSerial++
            } else s.navigate("morning")
            activity.intent?.removeExtra("open_morning")
        }
    }
    // 사용자가 '정차 중 자동 표시'를 선택했고, 앱에서 차박 중/스마트 모닝을 보고 있을 때만 전환.
    // 주행 상태를 실제 차량에서 검증하지 못하므로 기본값은 꺼짐입니다.
    LaunchedEffect(Unit) {
        var seen = appPrefs.getLong(ChabakMorningScheduler.KEY_LAST_TRIGGER, 0L)
        while (true) {
            val latest = appPrefs.getLong(ChabakMorningScheduler.KEY_LAST_TRIGGER, 0L)
            if (latest > seen) {
                seen = latest
                val recent = System.currentTimeMillis() - latest in 0L..90_000L
                val isTest = appPrefs.getBoolean(ChabakMorningScheduler.KEY_LAST_WAS_TEST, false)
                if (recent && s.sunriseForegroundOptIn &&
                    (s.page == "home" || s.page == "stay" || s.page == "morning")) {
                    // 일반/시험 알람 모두 현재 페이지를 유지한 채 배경만 부드럽게 전환.
                    // 인사말은 실제 현재 시간으로 계산하므로 밤에 아침 인사가 뜨지 않음.
                    s.wakeSceneLive = true
                    s.wakeScenePreview = false
                    s.wakeQuickMode = !isMorningTime()
                    s.wakeTransitionSerial++
                } else if (recent && (s.page == "stay" || s.page == "morning")) {
                    s.morningStatus = "알람이 울렸어요. 알림을 눌러 확인할 수 있어요."
                }
            }
            delay(1200L)
        }
    }
    val natureAudio = remember(context, s) { NatureAudioEngine(context.applicationContext, s) }
    val activity = context as? ComponentActivity
    DisposableEffect(natureAudio, activity) {
        val observer = LifecycleEventObserver { _, event ->
            // 주행/다른 앱 전환 중 무단 재생 방지: 화면이 백그라운드면 일시정지
            if (event == Lifecycle.Event.ON_STOP) natureAudio.pause()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            natureAudio.release()
        }
    }
    LaunchedEffect(s.naturePlaybackStatus, natureAudio) {
        while (s.naturePlaybackStatus == "재생 중") {
            s.naturePlaybackProgress = natureAudio.progress()
            delay(350L)
        }
    }
    val bgResources = listOf(R.drawable.morning, R.drawable.day, R.drawable.evening, R.drawable.night)
    val currentTheme = if (s.page == "wake_scene") R.drawable.morning
    else bgResources[(if (s.themeIndex == -1) nowScene.index else s.themeIndex) % bgResources.size]
    LaunchedEffect(s.region) { while (true) { s.refreshWeather(); delay(15*60*1000L) } }
    LaunchedEffect(Unit) { s.refreshCamps() }
    val wakeTransitionActive = s.wakeScenePreview || s.wakeSceneLive
    val wakeBlend = remember { Animatable(0f) }
    LaunchedEffect(s.wakeTransitionSerial, wakeTransitionActive) {
        wakeBlend.snapTo(0f)
        if (wakeTransitionActive) {
            // 낮·저녁·밤: 짧고 매끄러운 장면 교체. 아침은 긴 일출 전환을 유지.
            val duration = if (s.wakeSceneLive && s.wakeQuickMode) 1_350 else 12_000
            wakeBlend.animateTo(1f, tween(durationMillis = duration))
        }
    }
    BackHandler(s.page != "home" || wakeTransitionActive) {
        if (wakeTransitionActive) {
            s.wakeScenePreview = false
            s.wakeSceneLive = false
        } else s.back()
    }
    Box(Modifier.fillMaxSize().background(Midnight)) {
        ScenicResource(currentTheme, Modifier.fillMaxSize(), ContentScale.Crop)
        if (wakeTransitionActive) {
            if (s.wakeSceneLive && s.wakeQuickMode) {
                // 현재 시간의 풍경으로 1.35초 교차 전환. 번쩍임과 암전 없이 자연스럽게.
                ScenicResource(bgResources[nowScene.index],
                    Modifier.fillMaxSize().graphicsLayer { alpha = wakeBlend.value }, ContentScale.Crop)
                // 시간대별 배경의 분위기를 살리되 정보 가독성만 은은하게 보정.
                Box(Modifier.fillMaxSize().background(
                    Color(0xFF10232B).copy(alpha = 0.13f * wakeBlend.value)))
            } else {
                // 아침: 기존 화면에 아침 숲이 12초 동안 자연스럽게 겹쳐짐.
                ScenicResource(R.drawable.morning, Modifier.fillMaxSize().graphicsLayer { alpha = wakeBlend.value }, ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color(0xFF071525).copy(alpha = 0.62f * (1f - wakeBlend.value))))
                Box(Modifier.fillMaxSize().background(Color(0xFFFFD58F).copy(alpha = 0.09f * wakeBlend.value)))
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x31071725), Color(0x4A082631), Color(0x9A081C28)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 19.dp, vertical = 13.dp)) {
            AppTopBar(s, onBack = { s.back() })
            Spacer(Modifier.height(6.dp))
            ClockWeatherBar(s, large=s.page=="stay")
            Spacer(Modifier.height(9.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment=Alignment.BottomStart) {
                // 중복 글씨를 줄이기 위해 인사말은 홈/차박 중 화면 상단에 한 번만 표시합니다.
                if ((s.page == "home" || s.page == "stay") && !s.wakeSceneLive) {
                    Text(nowScene.greeting, color=Frost, fontSize=36.sp,
                        fontWeight=FontWeight.Bold,
                        modifier=Modifier.align(Alignment.TopStart).padding(start=8.dp, top=10.dp))
                }
                // 풍경의 상단은 열어두고, 조작 영역만 하단에 둡니다.
                val isMain = s.page == "home" || s.page == "stay" || s.page == "wake_scene"
                Box(Modifier.fillMaxWidth().fillMaxHeight(if (isMain) .65f else .95f)) {
                    CompositionLocalProvider(LocalNatureAudio provides natureAudio) {
                        when(s.page) {
                            "home" -> HomeScreen(s, onTrips = { tripPickerOpen = true })
                            "places" -> PlacesScreen(s)
                            "recommendations" -> RecommendationScreen(s, recommendationScroll)
                            "place_detail" -> PlaceDetailScreen(s)
                            "packing" -> PackingScreen(s)
                            "trip_travel" -> TripTravelScreen(s.activeTrip, s.travelPlace, s::saveTripTravel)
                            "stay" -> StayScreen(s, vehicleEnergy)
                            "energy" -> SmartEnergyScreen(s.activeTrip, vehicleEnergy, s::saveTripEnergy)
                            "environment" -> EnvironmentScreen(s)
                            "playlist" -> PlaylistScreen(s)
                            "morning" -> MorningScreen(s)
                            "wake_scene" -> MorningWakeScene(s, nowScene,
                                onStop = {
                                    if (s.wakeSceneLive) {
                                        try { context.startService(Intent(context, ChabakMorningPlaybackService::class.java)
                                            .setAction(ChabakMorningPlaybackService.ACTION_STOP)) } catch (_: Exception) { }
                                    }
                                    s.wakeSceneLive = false; s.back()
                                },
                                onSnooze = {
                                    if (s.wakeSceneLive) {
                                        try { context.startService(Intent(context, ChabakMorningPlaybackService::class.java)
                                            .setAction(ChabakMorningPlaybackService.ACTION_SNOOZE)) } catch (_: Exception) { }
                                    }
                                    s.wakeSceneLive = false; s.back()
                                })
                            "diary" -> DiaryScreen(s)
                            else -> HomeScreen(s, onTrips = { tripPickerOpen = true })
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("고캠핑 자료 참고 · 로컬 알람 음악/자연음 재생 · 스트리밍·차량 제어는 시연", color=Muted.copy(alpha=.85f), fontSize=17.sp, modifier=Modifier.align(Alignment.End))
        }
        if (tripPickerOpen) {
            TripPicker(
                trips = s.savedTrips,
                activeTripId = s.activeTrip.tripId,
                onOpen = { tripId ->
                    if (s.openTrip(tripId)) {
                        tripPickerOpen = false
                        s.navigate("place_detail")
                    }
                },
                onCreate = {
                    if (s.createTrip()) {
                        tripPickerOpen = false
                        s.navigate("places")
                    }
                },
                onClose = { tripPickerOpen = false }
            )
        }
        if (wakeTransitionActive && s.wakeSceneLive && s.wakeQuickMode) {
            // 큰 경고창 대신 풍경 위에 현재 재생 중인 음악만 작게 표시.
            Row(
                Modifier.align(Alignment.BottomStart)
                    .padding(start = 30.dp, bottom = 115.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xA5122931))
                    .padding(horizontal = 24.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("♫", color = Gold, fontSize = 30.sp)
                Column {
                    Text("CHABAK · MY SOUND", color = Muted, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                    Text(if (s.selectedWakeTrackName.isNotBlank()) s.selectedWakeTrackName
                    else "${s.wakeSound} · 자연의 소리",
                        color = Frost, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1)
                }
            }
        }
        if (wakeTransitionActive) {
            // 알람 제어바는 아침/그 외 시간 모두 표시.
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 35.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xD9082432))
                    .border(1.dp, GlassBorder, RoundedCornerShape(22.dp))
                    .padding(horizontal = 17.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (s.wakeScenePreview) "일출 미리보기"
                else if (s.wakeQuickMode) "♫ CHABAK"
                else "☀ ${nowScene.greeting}",
                    color = Frost, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (s.wakeSceneLive) {
                    Text("5분 뒤 다시", color = Gold, fontSize = 18.sp, modifier = Modifier.clickable {
                        try { context.startService(Intent(context, ChabakMorningPlaybackService::class.java)
                            .setAction(ChabakMorningPlaybackService.ACTION_SNOOZE)) } catch (_: Exception) { }
                        s.wakeSceneLive = false
                    })
                }
                Text(if (s.wakeSceneLive) "알람 끄기" else "닫기", color = Frost,
                    fontSize = 18.sp, modifier = Modifier.clickable {
                        if (s.wakeSceneLive) {
                            try { context.startService(Intent(context, ChabakMorningPlaybackService::class.java)
                                .setAction(ChabakMorningPlaybackService.ACTION_STOP)) } catch (_: Exception) { }
                        }
                        s.wakeSceneLive = false
                        s.wakeScenePreview = false
                    })
            }
        }
    }
}

@Composable
private fun ScenicResource(res: Int, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val bmp: ImageBitmap? = remember(res) {
        try { BitmapFactory.decodeResource(context.resources,res,BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
        catch (_: Exception) { null }
    }
    if (bmp!=null) Image(bitmap=bmp,contentDescription=null,contentScale=scale,modifier=modifier)
    else Box(modifier.background(Brush.linearGradient(listOf(Midnight,Pine))))
}

@Composable
private fun AppTopBar(s: AppState, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=57.dp), verticalAlignment=Alignment.CenterVertically) {
        if (s.page != "home") Text("‹",fontSize=43.sp,color=Frost,modifier=Modifier.clickable(onClick=onBack).padding(end=17.dp))
        Text(when(s.page) {
            "home"->"CHABAK  ·  차박 준비";"places"->"차박 장소 찾기";"recommendations"->"맞춤 장소 추천";"place_detail"->s.detailPlace.name
            "packing"->"나만의 준비물";"trip_travel"->"이동 정보";"stay"->"CHABAK  ·  차박 중";"energy"->"스마트 에너지"
            "playlist"->"나만의 플레이리스트";"environment"->"차박 환경 조성";"morning"->"스마트 모닝"
            "diary"->"차박 다이어리";"wake_scene"->"일출 모닝";else->"CHABAK"
        }, color=Frost,fontSize=35.sp,fontWeight=FontWeight.Bold,maxLines=1,modifier=Modifier.weight(1f))

        val phase = timeScene(chabakNow().hour)
        val idx = if (s.themeIndex == -1) phase.index else s.themeIndex
        val mode = if (s.themeIndex == -1) "자동" else "수동"
        OutlineChip("$mode · ${listOf("아침","낮","노을","밤")[idx]} ↻",false) {
            s.themeIndex = if (s.themeIndex >= 3) -1 else s.themeIndex + 1
        }
    }
}

@Composable
private fun ClockWeatherBar(s:AppState,large:Boolean=false) {
    var clock by remember { mutableStateOf(chabakNow()) }
    LaunchedEffect(Unit) { while(true) { clock=chabakNow();delay(1000L) } }
    val fmt=DateTimeFormatter.ofPattern("HH:mm",Locale.KOREAN)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
        .background(Color(0x25FFFFFF))
        .border(1.dp,GlassBorder,RoundedCornerShape(15.dp))
        .padding(horizontal=20.dp,vertical=if(large) 12.dp else 7.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${s.region} 기준  ·  ${clock.monthValue}월 ${clock.dayOfMonth}일",color=Muted,fontSize=24.sp)
            Text(if(s.weatherTemp!=null) "${s.weatherTemp}  ${s.weatherLabel}" else s.weatherLabel,
                color=Frost,fontSize=if(large) 29.sp else 25.sp,fontWeight=FontWeight.Bold)
        }
        Column(horizontalAlignment=Alignment.End) {
            Text(clock.format(fmt),color=Gold,fontSize=if(large) 42.sp else 32.sp,fontWeight=FontWeight.Bold)
            Text(if(s.weatherTemp!=null) "선택지역 대표지점 날씨" else "날씨 연동 확인 중",
                color=Muted,fontSize=24.sp)
        }
    }
}

@Composable
internal fun GlassPanel(modifier: Modifier=Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(23.dp)).background(Glass).border(1.dp,GlassBorder,RoundedCornerShape(23.dp)).padding(17.dp), content=content)
}

@Composable
internal fun ActionButton(label: String, modifier: Modifier=Modifier, filled: Boolean = true, onClick:()->Unit) {
    val radius=RoundedCornerShape(16.dp)
    Box(modifier.heightIn(min=60.dp).clip(radius).background(Brush.horizontalGradient(listOf(Color(0x30FFFFFF),Color(0x1EFFFFFF)))).border(1.dp,if(filled) Gold else GlassBorder,radius).clickable(onClick=onClick).padding(horizontal=13.dp),contentAlignment=Alignment.Center) {
        Text(label,color=if(filled) Gold else Frost,fontSize=24.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun OutlineChip(label: String, selected: Boolean, modifier: Modifier=Modifier, onClick:()->Unit) {
    val r=RoundedCornerShape(13.dp)
    Box(modifier.heightIn(min=56.dp).clip(r).background(if(selected) Color(0x557BBA82) else Color(0x20FFFFFF)).border(1.dp,if(selected) Mint else GlassBorder,r).clickable(onClick=onClick).padding(horizontal=15.dp,vertical=9.dp),contentAlignment=Alignment.Center) {
        Text(label,color=Frost,fontSize=24.sp,fontWeight=if(selected) FontWeight.Bold else FontWeight.Medium,maxLines=1)
    }
}

@Composable
internal fun SectionTitle(title:String, detail:String="") {
    Text(title,color=Frost,fontSize=29.sp,fontWeight=FontWeight.Bold)
    if(detail.isNotBlank()) { Spacer(Modifier.height(4.dp));Text(detail,color=Muted,fontSize=24.sp) }
}

@Composable
private fun TripPicker(
    trips: List<Trip>,
    activeTripId: String,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        dismissOnClickOutside = false, usePlatformDefaultWidth = false
    )) {
        GlassPanel(Modifier.widthIn(max = 720.dp).fillMaxWidth(.9f)) {
            SectionTitle("내 여행", "변경 사항은 여행별로 자동 저장돼요.")
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 325.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                trips.forEachIndexed { index, trip ->
                    val active = trip.tripId == activeTripId
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Glass)
                            .border(1.dp, if (active) Gold else GlassBorder, RoundedCornerShape(14.dp))
                            .clickable { onOpen(trip.tripId) }.padding(12.dp)
                    ) {
                        Text("여행 ${trips.size - index} · ${trip.place.name}",
                            color = Frost, fontSize = 23.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${trip.visitDate} · ${trip.people}명 · ${trip.nights}박" +
                            if (active) " · 선택 중" else "",
                            color = if (active) Gold else Muted, fontSize = 19.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("새 여행은 현재 일정으로 시작하고 준비 상태는 새로 저장해요.", color = Muted, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("닫기", Modifier.weight(1f), filled = false, onClick = onClose)
                ActionButton("새 여행", Modifier.weight(1f), onClick = onCreate)
            }
        }
    }
}

@Composable
private fun HomeScreen(s: AppState, onTrips: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
        val columns = if (maxWidth >= 760.dp) 2 else 1
        val actions = listOf(
            Triple("차박지 찾기", "고캠핑 추천과 시설 비교", { s.navigate("recommendations") }),
            Triple("내 여행", "${s.selectedPlace.name} · ${s.tripDate}", onTrips),
            Triple("차박 중", "자연음과 스마트 모닝", { s.navigate("stay") }),
            Triple("스마트 에너지", "배터리와 숙박 에너지 계획", { s.navigate("energy") })
        )
        Column(
            Modifier.fillMaxWidth(if (columns == 2) .85f else 1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            actions.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (title, subtitle, open) ->
                        HomeQuickFeature(title, subtitle, Modifier.weight(1f), open)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeQuickFeature(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 76.dp).clip(RoundedCornerShape(21.dp)).background(Glass)
            .border(1.dp, GlassBorder, RoundedCornerShape(21.dp))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Frost, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = Frost, fontSize = 34.sp)
    }
}

@Composable
private fun RecommendationScreen(s: AppState, scrollState: ScrollState) {
    LaunchedEffect(Unit) {
        if (!s.recommendationStarted) {
            s.changeCampingCriteria(s.campingCriteria.copy(region = s.region))
            s.fetchRecommendations()
        }
    }
    GlassPanel(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle("나에게 맞는 캠핑장", "지역·자연환경·필수 시설로 최대 ${MAX_CAMPING_RECOMMENDATIONS}곳 비교")
            RecommendationFilters(s)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val message = if (s.recommendationIsCurrent) s.recommendationMessage
                    else "선택한 조건으로 추천 보기를 눌러 주세요."
                Text(message, color = Gold, fontSize = 22.sp, modifier = Modifier.weight(1f))
                ActionButton("추천 보기", Modifier.width(145.dp)) { s.fetchRecommendations() }
            }
            if (s.recommendationIsCurrent) {
                if (s.recommendationLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
                if (s.recommendedPlaces.isNotEmpty()) {
                    RecommendationCards(s.recommendedPlaces, s::previewPlace)
                }
                s.recommendationNotices.forEach { note ->
                    Text(note, color = Muted, fontSize = 19.sp, lineHeight = 24.sp)
                }
            }
            RecommendationChoiceRow {
                OutlineChip("조용한 숲 · 화장실", false) { s.requestRecommendation("경기도에서 조용한 숲이고 화장실 있는 캠핑장 추천해줘") }
                OutlineChip("계곡 · 전기", false) { s.requestRecommendation("경기도 계곡 근처 전기 있는 캠핑장 추천해줘") }
                OutlineChip("바다 · 편의점", false) { s.requestRecommendation("강원 바다 근처 편의점 있는 캠핑장 추천해줘") }
                OutlineChip("현재 지역 추천", false) { s.requestRecommendation("${s.region}에서 자동차야영장 추천해줘") }
            }
            ActionButton("전체 캠핑장 직접 검색", Modifier.fillMaxWidth(), filled = false) { s.navigate("places") }
        }
    }
}

@Composable
private fun RecommendationFilters(s: AppState) {
    val criteria = s.campingCriteria
    val kinds = (s.eligibleCampingKinds + criteria.kind).distinct()
    if (kinds.size > 1) {
        RecommendationChoiceRow("장소 유형") {
            kinds.forEach { kind ->
                OutlineChip(kind.label, kind == criteria.kind) { s.changeCampingCriteria(criteria.copy(kind = kind)) }
            }
        }
    }
    RecommendationChoiceRow("지역") {
        campingRegions.forEach { region ->
            OutlineChip(region, region == criteria.region) {
                s.changeCampingCriteria(criteria.copy(region = region, district = ""))
            }
        }
        if (criteria.district.isNotBlank()) {
            OutlineChip("${criteria.district} ×", true) { s.changeCampingCriteria(criteria.copy(district = "")) }
        }
    }
    RecommendationChoiceRow("자연환경") {
        (listOf("") + campingEnvironments).forEach { nature ->
            OutlineChip(nature.ifBlank { "전체" }, nature == criteria.nature) {
                s.changeCampingCriteria(criteria.copy(nature = nature, quiet = false))
            }
        }
    }
    RecommendationChoiceRow("필수 시설") {
        OutlineChip("화장실 필수", criteria.toilet) { s.changeCampingCriteria(criteria.copy(toilet = !criteria.toilet)) }
        OutlineChip("캠핑용 전기 필수", criteria.campingPower) { s.changeCampingCriteria(criteria.copy(campingPower = !criteria.campingPower)) }
        OutlineChip("매점·편의점 필수", criteria.store) { s.changeCampingCriteria(criteria.copy(store = !criteria.store)) }
        if (criteria.evCharging || criteria.kind != CampingPlaceKind.CAMPGROUND) {
            OutlineChip("EV 충전 필수", criteria.evCharging) { s.changeCampingCriteria(criteria.copy(evCharging = !criteria.evCharging)) }
        }
    }
}

@Composable
private fun RecommendationChoiceRow(label: String = "", content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        if (label.isNotBlank()) Text(label, color = Frost, fontSize = 21.sp, modifier = Modifier.width(91.dp))
        content()
    }
}

@Composable
private fun RecommendationCards(items: List<SuggestedPlace>, onSelect: (Place) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 1050.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(columns).forEachIndexed { row, cards ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    cards.forEachIndexed { column, entry ->
                        RecommendationCard(entry, row * columns + column + 1, Modifier.weight(1f)) { onSelect(entry.place) }
                    }
                    if (cards.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(entry: SuggestedPlace, index: Int, modifier: Modifier, onSelect: () -> Unit) {
    val p = entry.place
    val ctx = LocalContext.current
    val booking = p.bookingLink()
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Glass)
            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onSelect).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(index.toString(), color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text(p.name, color = Frost, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text(p.address, color = Muted, fontSize = 21.sp, lineHeight = 26.sp)
            }
            Text("상세 ›", color = Gold, fontSize = 21.sp)
        }
        RecommendationField("자연환경 · ${p.theme.ifBlank { "확인 필요" }}")
        RecommendationField("화장실 ${p.toiletLabel()} · 샤워실 ${facilityCountLabel(p.showerCount)} · 개수대 ${facilityCountLabel(p.sinkCount)}")
        RecommendationField("캠핑용 전기 · ${p.campingPowerLabel()}")
        RecommendationField("내부 EV 충전소 ${facilityAvailability(p.onsiteEvCharger)} · 인근 EV 충전소 ${facilityAvailability(p.nearbyEvCharger)}")
        RecommendationField("차량 내 숙박 허용 · ${facilityAvailability(p.overnightAllowed, "허용", "미허용")}")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RecommendationField("예약 방법 · ${p.bookingMethodLabel()}")
            if (booking != null) OutlineChip("예약 링크 ↗", false) { openBrowser(ctx, booking) }
        }
        if (entry.reasons.isNotEmpty()) {
            Text(entry.reasons.take(2).joinToString(" · "), color = Gold, fontSize = 19.sp, lineHeight = 24.sp)
        }
    }
}

@Composable
internal fun RecommendationField(text: String) {
    Text(text, color = Frost, fontSize = 21.sp, lineHeight = 27.sp)
}

@Composable
private fun PlacesScreen(s: AppState) {
    var query by remember { mutableStateOf("") }
    val areas = listOf("전국", "경기", "강원", "충청", "경상", "전라", "제주")
    LaunchedEffect(s.region, query) {
        delay(400)
        // 검색어를 지울 때에도 전체 결과를 다시 받아옵니다.
        if (s.livePlacesLoaded) s.refreshCamps(query)
    }
    val filtered = s.places.filter { p ->
        (s.region == "전국" || p.region == s.region) &&
                (query.isBlank() || listOf(p.name, p.detail, p.address, p.theme, p.services).any { it.contains(query, ignoreCase=true) }) &&
                (!s.requiresToilet || p.hasToilet()) &&
                (!s.requiresElectric || p.campingPower == true)
    }.sortedWith(compareByDescending<Place> { it.siteType.contains("자동차") }
        .thenByDescending { it.hasToilet() }
        .thenByDescending { it.campingPower == true })
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            areas.forEach { area -> OutlineChip(area, s.region == area, onClick={s.selectRegion(area)}) }
        }
        GlassPanel(Modifier.fillMaxWidth().weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Text("실제 캠핑장 찾기", color=Frost, fontSize=29.sp, fontWeight=FontWeight.Bold, modifier=Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ActionButton("↻ 새로고침", Modifier.width(155.dp), filled=false) { s.refreshCamps(query) }
            }
            Text(s.campsMessage, color=Gold, fontSize=19.sp)
            ActionButton("✦ 맞춤 추천", Modifier.fillMaxWidth(), filled=false) { s.navigate("recommendations") }
            Spacer(Modifier.height(8.dp))
            InputDemo(query, { query=it }, "이름·지역·시설 검색", Modifier.fillMaxWidth())
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("🚙 차량 야영 우선", color=Gold, fontSize=22.sp, modifier=Modifier.padding(vertical=9.dp))
                OutlineChip("🚻 화장실 정보", s.requiresToilet) { s.requiresToilet = !s.requiresToilet }
                OutlineChip("⚡ 전기", s.requiresElectric) { s.requiresElectric = !s.requiresElectric }
            }
            Spacer(Modifier.height(8.dp))
            if (s.campsLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color=Gold)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if (filtered.isEmpty()) Text("검색 결과가 없어요. 지역이나 조건을 바꿔보세요.", color=Frost, fontSize=23.sp)
                filtered.forEach { p -> PlaceTile(p, p.id == s.pickedPlaceId) { s.previewPlace(p) } }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ActionButton("🗺 실제 지도", Modifier.weight(1f), filled=false) {
                    openMapSearch(ctx, if (query.isBlank()) s.region+" 캠핑장" else query+" 캠핑장")
                }
                ActionButton("고캠핑 공식 검색 ↗", Modifier.weight(1f), filled=false) {
                    openBrowser(ctx, "https://gocamping.or.kr/bsite/search/list.do")
                }
            }
            Text("※ 화장실·전기 정보가 없는 곳은 미확인입니다. 차량 내 숙박 허용과 예약 가능 여부는 운영자에게 확인해 주세요.", color=Muted, fontSize=18.sp)
        }
    }
}

@Composable
private fun PlaceTile(p: Place, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=105.dp).clip(RoundedCornerShape(16.dp))
        .background(if(selected) Color(0x4AFFFFFF) else Glass)
        .border(1.dp, if(selected) Gold else GlassBorder, RoundedCornerShape(16.dp))
        .clickable(onClick=onClick).padding(10.dp),
        verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        ScenicResource(p.picture, Modifier.width(105.dp).height(82.dp).clip(RoundedCornerShape(11.dp)))
        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(p.name, color=Frost, fontSize=26.sp, fontWeight=FontWeight.Bold, maxLines=1, overflow=TextOverflow.Ellipsis)
            Text(p.address.ifBlank { p.detail }, color=Muted, fontSize=20.sp, maxLines=1, overflow=TextOverflow.Ellipsis)
            Text((if(p.siteType.isBlank()) "캠핑장" else p.siteType) + "  ·  " +
                    (if(p.toiletCount != null) "화장실 ${p.toiletCount}곳" else "화장실 확인 필요"),
                color=Gold, fontSize=20.sp, maxLines=1)
        }
        Text("›", fontSize=36.sp, color=Gold)
    }
}

@Composable
private fun PlaceDetailScreen(s: AppState) {
    val ctx = LocalContext.current
    val p = s.detailPlace
    val canPlan = s.detailCanPlan
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            GlassPanel(Modifier.weight(.94f).fillMaxHeight()) {
                PlaceDetailLeft(s, p, Modifier.weight(1f), ctx)
                Spacer(Modifier.height(8.dp))
                PlacePlanAction(s, p, canPlan)
            }
            GlassPanel(Modifier.weight(1.06f).fillMaxHeight()) { PlaceDetailRight(p, canPlan, Modifier.weight(1f)) }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassPanel(Modifier.fillMaxWidth()) {
                PlaceDetailLeft(s, p, Modifier.height(550.dp), ctx)
                PlacePlanAction(s, p, canPlan)
            }
            GlassPanel(Modifier.fillMaxWidth()) { PlaceDetailRight(p, canPlan, Modifier.height(450.dp)) }
        }
    }
}

@Composable
private fun PlacePlanAction(s: AppState, p: Place, canPlan: Boolean) {
    if (canPlan) {
        val label = when {
            !s.detailIsPreview -> "준비물 보기"
            p.kind == CampingPlaceKind.CAMPGROUND -> "이 캠핑장 선택 → 준비물"
            else -> "이 장소 선택 → 준비물"
        }
        if (s.detailIsPreview) {
            ActionButton(label, Modifier.fillMaxWidth()) {
                if (s.confirmDetail()) s.navigate("packing")
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(label, Modifier.weight(1f)) {
                    if (s.confirmDetail()) s.navigate("packing")
                }
                ActionButton("이동 정보", Modifier.weight(1f), filled = false) { s.navigate("trip_travel") }
                ActionButton("에너지", Modifier.weight(1f), filled = false) { s.navigate("energy") }
            }
        }
    } else {
        Text("이 여행 기간의 차량 숙박 허용을 확인해 주세요.", color = Gold, fontSize = 22.sp)
    }
}

@Composable
private fun PlaceDetailLeft(s: AppState, p: Place, modifier: Modifier, ctx: Context) {
    LaunchedEffect(p.id) { s.phoneDraft = s.placePhone(p.id).ifBlank { p.phone } }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(9.dp)) {
        ScenicResource(p.picture, Modifier.fillMaxWidth().height(135.dp).clip(RoundedCornerShape(12.dp)))
        Text("테마 배경 이미지 · 실제 캠핑장 사진 아님", color=Muted, fontSize=17.sp)
        Text(p.address.ifBlank { p.detail }, color=Frost, fontSize=23.sp, fontWeight=FontWeight.Bold)
        val source = when {
            p.kind != CampingPlaceKind.CAMPGROUND -> p.permit?.manager ?: "관리 주체 확인 필요"
            p.goCampingDetailUrl() != null -> {
                val date = goCampingCollectionDay(p.sourceUpdatedAt)
                "$GOCAMPING_SOURCE · API 조회" + (date?.let { " · 수집 $it" } ?: "")
            }
            p.id.startsWith("ref_") -> "고캠핑 공개 기록 · 내장 참고 목록"
            else -> "저장된 장소 · 출처 확인 필요"
        }
        Text(source, color = Gold, fontSize = 20.sp)
        OutlineChip("방문일  ${s.detailDate}  ✎", false, Modifier.fillMaxWidth()) {
            val date = s.detailDate
            val tripId = s.activeTrip.tripId
            DatePickerDialog(
                ctx, { _, y, m, d -> s.setDetailDate(p.id, tripId, LocalDate.of(y, m + 1, d)) },
                date.year, date.monthValue - 1, date.dayOfMonth
            ).show()
        }
        Text("문의 / 예약 전화", color=Frost, fontSize=24.sp, fontWeight=FontWeight.Bold)
        InputDemo(s.phoneDraft, { s.phoneDraft=it }, if(p.phone.isNotBlank()) "전화번호" else "연락처 확인 필요")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ActionButton("번호 저장", Modifier.weight(1f), filled=false) { s.savePhone(p.id,s.phoneDraft) }
            ActionButton("전화하기", Modifier.weight(1f)) {
                val tel = s.phoneDraft.filter { it.isDigit() || it=='+' }
                if(tel.isNotEmpty()) try { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel"))) }
                catch (_:Exception) { Toast.makeText(ctx,"전화 앱을 열 수 없어요",Toast.LENGTH_SHORT).show() }
                else Toast.makeText(ctx,"공식 사이트에서 연락처를 확인해 주세요",Toast.LENGTH_LONG).show()
            }
        }
        Text("예약 방법 · ${p.bookingMethodLabel()}", color = Frost, fontSize = 22.sp)
        val booking = p.bookingLink()
        if (booking != null) {
            ActionButton("예약 페이지 열기 ↗", Modifier.fillMaxWidth(), filled = false) { openBrowser(ctx, booking) }
        } else {
            usableWebUrl(p.website)?.let { website ->
                ActionButton("운영 홈페이지 ↗", Modifier.fillMaxWidth(), filled = false) { openBrowser(ctx, website) }
            }
        }
        if (p.kind == CampingPlaceKind.CAMPGROUND) {
            val sourceUrl = p.goCampingDetailUrl() ?: "https://gocamping.or.kr/bsite/search/list.do"
            ActionButton("고캠핑에서 정보 확인 ↗", Modifier.fillMaxWidth(), filled = false) { openBrowser(ctx, sourceUrl) }
        } else p.permit?.let { permit ->
            ActionButton("이용 허용 출처 ↗", Modifier.fillMaxWidth(), filled = false) { openBrowser(ctx, permit.sourceUrl) }
        }
    }
}

@Composable
private fun PlaceDetailRight(p: Place, canPlan: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        SectionTitle("시설·위치", "정확한 화장실 건물 위치는 현장 지도 확인")
        val overnight = p.overnightAllowed.takeIf { p.kind == CampingPlaceKind.CAMPGROUND || canPlan }
        val facilitySummary = listOf(
            "장소 유형 · ${p.kind.label}",
            "자연환경 · ${p.theme.ifBlank { "확인 필요" }}",
            "화장실 · ${p.toiletLabel()}",
            "샤워실 · ${facilityCountLabel(p.showerCount)}",
            "개수대 · ${facilityCountLabel(p.sinkCount)}",
            "캠핑용 전기 · ${p.campingPowerLabel()}",
            "전기차 충전소 (장소 내부) · ${facilityAvailability(p.onsiteEvCharger)}",
            "전기차 충전소 (장소 인근) · ${facilityAvailability(p.nearbyEvCharger)}",
            "차량 내 숙박 허용 · ${facilityAvailability(overnight, "허용", "미허용")}"
        )
        facilitySummary.forEach { label ->
            Row(Modifier.fillMaxWidth().background(Glass, RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text(label, color=Frost, fontSize=24.sp, fontWeight=FontWeight.Medium)
            }
        }
        if (p.services.isNotBlank() && p.services != "시설 정보 확인 필요") {
            Text("등록 부대시설 · ${p.services}", color = Frost, fontSize = 22.sp)
        }
        if (p.kind == CampingPlaceKind.CAMPGROUND) {
            RecommendationField("등록 업종 · ${p.siteType.ifBlank { "확인 필요" }}")
            RecommendationField("등록 운영상태 · ${p.businessStatus.ifBlank { "확인 필요" }}")
        } else p.permit?.let { permit ->
            RecommendationField("관리 주체 · ${permit.manager}")
            RecommendationField("좌표 · ${permit.latitude}, ${permit.longitude}")
            RecommendationField("확인일 · ${permit.verifiedOn}")
            RecommendationField("허용 기간 · ${permit.allowedFrom} ~ ${permit.allowedThrough}")
            RecommendationField("접근 도로 · ${permit.accessRoad.ifBlank { "확인 필요" }}")
            RecommendationField("차량 진입 · ${facilityAvailability(permit.vehicleAccessAllowed, "허용", "미허용")}")
            RecommendationField("야간 주차 · ${facilityAvailability(permit.nightParkingAllowed, "허용", "미허용")}")
            RecommendationField("취사 · ${facilityAvailability(permit.cookingAllowed, "허용", "미허용")}")
            RecommendationField("화기 사용 · ${facilityAvailability(permit.openFlameAllowed, "허용", "미허용")}")
        }
        ActionButton("🗺 주소 지도에서 보기 ↗", Modifier.fillMaxWidth()) { openMapSearch(ctx,p.address.ifBlank { p.name }) }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ActionButton("🚻 주변 화장실", Modifier.weight(1f), filled=false) { openMapSearch(ctx,p.name + " 주변 화장실") }
            ActionButton("🏪 주변 편의점", Modifier.weight(1f), filled=false) { openMapSearch(ctx,p.name + " 주변 편의점") }
        }
        Text("※ 시설 개수와 지도상의 정확한 개별 위치는 다른 정보입니다. 연락처·계절 운영·반려동물·차박 허용 여부는 예약 전에 확인해 주세요.",color=Gold,fontSize=19.sp)
    }
}

internal fun openBrowser(ctx: Context, url: String) {
    val link = usableWebUrl(url) ?: return
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
    catch (_:Exception) { Toast.makeText(ctx,"브라우저를 확인해 주세요",Toast.LENGTH_SHORT).show() }
}
private fun openMapSearch(ctx: Context, query: String) {
    try {
        val url = "https://map.kakao.com/?q=" + URLEncoder.encode(query,"UTF-8")
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_:Exception) { Toast.makeText(ctx,"지도 앱 또는 브라우저를 확인해 주세요",Toast.LENGTH_SHORT).show() }
}

@Composable
private fun PackingScreen(s:AppState) {
    val ctx=LocalContext.current
    val items=s.packs()
    val done=items.count { it.id in s.packed }
    val groups=listOf("전체","잠자리","먹거리","계절","날씨","장소","기타","추가")
    var tab by remember { mutableStateOf("전체") }
    val subset=items.filter { tab=="전체" || it.group==tab }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=760.dp
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlineChip("▣  ${s.nights}박 ${s.nights+1}일",false,onClick={s.saveNights(if(s.nights>=3) 1 else s.nights+1)})
                OutlineChip("♙  ${s.people}명",false,onClick={s.savePeople(if(s.people>=4) 1 else s.people+1)})
                OutlineChip("✦  ${s.seasonName()} 자동 추천",true,onClick={})
                OutlineChip(
                    if (s.packingDetailed) "초보자 · 설명 포함" else "경험자 · 간단 보기",
                    s.packingDetailed, onClick = s::togglePackingDetail
                )
                Text(s.packingWeatherLabel(), color = Muted, fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
            if(wide) Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(13.dp)) {
                GlassPanel(Modifier.weight(1.22f).fillMaxHeight()) { PackingMain(s,subset,groups,tab,{tab=it},Modifier.weight(1f)) }
                GlassPanel(Modifier.weight(.82f).fillMaxHeight()) { PackingShare(s,items,Modifier.weight(1f),ctx) }
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(11.dp)) {
                GlassPanel(Modifier.fillMaxWidth().heightIn(min=430.dp)) { PackingMain(s,subset,groups,tab,{tab=it},Modifier.height(380.dp)) }
                GlassPanel(Modifier.fillMaxWidth().heightIn(min=260.dp)) { PackingShare(s,items,Modifier.height(240.dp),ctx) }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ActionButton("완료",Modifier.width(135.dp),filled=false,onClick={s.home()})
                Text("$done / ${items.size}개 준비 완료",fontSize=24.sp,color=Frost,fontWeight=FontWeight.Bold)
                LinearProgressIndicator(progress={ if(items.isEmpty()) 0f else done.toFloat()/items.size },modifier=Modifier.weight(1f).height(11.dp).clip(RoundedCornerShape(5.dp)),color=Mint,trackColor=Color(0xFF3E5660))
            }
            if (s.travelPlace != null) {
                ActionButton("이동 정보 · 가는 길과 귀가", Modifier.fillMaxWidth(), filled = false) {
                    s.navigate("trip_travel")
                }
            }
        }
    }
}

@Composable
private fun PackingMain(s:AppState,items:List<PackItem>,groups:List<String>,tab:String,onTab:(String)->Unit,modifier:Modifier) {
    SectionTitle("자동 추천 준비물",s.packingExplanation())
    Spacer(Modifier.height(11.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) { groups.forEach { t->OutlineChip(t,tab==t,onClick={onTab(t)}) } }
    Spacer(Modifier.height(8.dp))
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        items.forEach { item->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Color(0x64375357)).clickable { s.togglePacked(item.id) }.padding(horizontal=14.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                Checkbox(checked=item.id in s.packed,onCheckedChange={s.togglePacked(item.id)},colors=CheckboxDefaults.colors(checkedColor=Mint,uncheckedColor=Frost))
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = if (item.id in s.packed) Muted else Frost, fontSize = 24.sp)
                    if (s.packingDetailed && item.description.isNotBlank()) {
                        Text(item.description, color = Muted, fontSize = 19.sp, lineHeight = 24.sp)
                    }
                }
                Text(item.group,color=Muted,fontSize=24.sp)
            }
        }
    }
    Spacer(Modifier.height(7.dp))
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        InputDemo(s.customItem,{s.customItem=it},"+ 준비물 직접 추가",Modifier.weight(1f))
        ActionButton("추가",Modifier.width(98.dp),filled=false,onClick={s.addExtra()})
    }
}

@Composable
private fun PackingShare(s:AppState,all:List<PackItem>,modifier:Modifier,ctx:Context) {
    SectionTitle("동행인에게 부탁하기","부탁할 물품만 선택해 공유해 보세요")
    Spacer(Modifier.height(10.dp))
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        all.filter { it.id !in s.packed }.forEach {item ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(Color(0x61365456)).clickable{s.toggleAssigned(item.id)}.padding(horizontal=9.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=item.id in s.assigned,onCheckedChange={s.toggleAssigned(item.id)},colors=CheckboxDefaults.colors(checkedColor=Gold,uncheckedColor=Frost))
                Text(item.name,color=Frost,fontSize=24.sp)
            }
        }
    }
    Spacer(Modifier.height(11.dp))
    ActionButton("${s.assigned.size}개 선택  ·  목록 공유 ↗",Modifier.fillMaxWidth(),onClick={
        val names=all.filter { it.id in s.assigned }.map { it.name }
        if(names.isNotEmpty()) {
            val intent=Intent(Intent.ACTION_SEND).apply { type="text/plain";putExtra(Intent.EXTRA_TEXT,"CHABAK 동행인 준비물 부탁해요!\n"+names.joinToString("\n") {"□ $it"}) }
            try {ctx.startActivity(Intent.createChooser(intent,"준비물 공유"))}catch(_:Exception){ Toast.makeText(ctx,"공유 앱을 열 수 없어요",Toast.LENGTH_SHORT).show() }
        } else Toast.makeText(ctx,"부탁할 물품을 먼저 선택해 주세요",Toast.LENGTH_SHORT).show()
    })
    Text("선택한 물품만 외부 앱의 공유 시트로 전달합니다.",color=Muted,fontSize=24.sp)
}



@Composable
private fun StayScreen(s: AppState, energy: VehicleEnergy) {
    val soc = energy.soc.valueOrNull()
    val energyLabel = soc?.let { "배터리 잔량 $it%" } ?: "배터리 잔량 확인 필요"
    val audio = LocalNatureAudio.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth >= 760.dp
        Column(Modifier.fillMaxHeight().fillMaxWidth(if(wide) .86f else 1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${s.selectedPlace.name}  ·  ${s.music}",color=Frost,fontSize=27.sp,fontWeight=FontWeight.Bold,maxLines=1)
                }
                ActionButton("차박 종료",Modifier.width(145.dp),filled=false,onClick={audio?.stop();s.home()})
            }
            if(wide) {
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    GlassPanel(Modifier.weight(1.42f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text("플레이리스트",color=Frost,fontSize=31.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                            Text("›",fontSize=35.sp,color=Gold,modifier=Modifier.clickable{s.navigate("playlist")})
                        }
                        Spacer(Modifier.height(7.dp))
                        CompactMusicPlayer(s,Modifier.weight(1f).fillMaxWidth(),onOpen={s.navigate("playlist")})
                    }
                    Column(Modifier.weight(.78f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        GlassPanel(Modifier.fillMaxWidth().weight(1f).clickable{s.navigate("energy")}) {
                            Text("ϟ 스마트 에너지", color = Frost, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                            Text(energyLabel, color = Gold, fontSize = 25.sp)
                            EnergyWarning(s.activeTrip.energyPlan, soc)
                        }
                    }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    GlassPanel(Modifier.fillMaxWidth().height(390.dp)) {
                        Text("플레이리스트",color=Frost,fontSize=31.sp,fontWeight=FontWeight.Bold)
                        CompactMusicPlayer(s,Modifier.weight(1f).fillMaxWidth(),onOpen={s.navigate("playlist")})
                    }
                    GlassPanel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(energyLabel, color = Frost, fontSize = 25.sp, modifier = Modifier.weight(1f))
                            OutlineChip("보기",false,onClick={s.navigate("energy")})
                        }
                        EnergyWarning(s.activeTrip.energyPlan, soc)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(13.dp)) {
                StayFeature("♧","환경 조성",Modifier.weight(1f)){s.navigate("environment")}
                StayFeature("☀","스마트 모닝",Modifier.weight(1f)){s.navigate("morning")}
                StayFeature("▤","차박 기록",Modifier.weight(1f)){s.openDiary(-1);s.navigate("diary")}
            }
        }
    }
}

@Composable
private fun CompactMusicPlayer(s:AppState,modifier:Modifier,onOpen:()->Unit) {
    val audio = LocalNatureAudio.current
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
            AlbumArt(Modifier.fillMaxHeight().aspectRatio(1f))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("CHABAK NATURE",color=Gold,fontSize=20.sp,fontWeight=FontWeight.Bold)
                Text(s.natureSound,color=Frost,fontSize=30.sp,fontWeight=FontWeight.Bold,maxLines=1)
                Text("자연음 · 실제 반복 재생",color=Muted,fontSize=20.sp)
                Text(s.naturePlaybackStatus,color=Gold,fontSize=22.sp,fontWeight=FontWeight.Bold)
            }
        }
        LinearProgressIndicator(progress={s.naturePlaybackProgress},modifier=Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),color=Gold,trackColor=Color(0xFF62777D))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
            PlayerControl("|◀",onClick={audio?.previous()})
            PlayerControl(if(s.naturePlaybackStatus=="재생 중") "Ⅱ" else "▶",primary=true,onClick={audio?.toggle()})
            PlayerControl("■",onClick={audio?.stop()})
            PlayerControl("▶|",onClick={audio?.next()})
        }
        if (s.naturePlaybackError.isNotEmpty()) Text(s.naturePlaybackError,color=Gold,fontSize=17.sp,maxLines=2)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("앱에 포함된 자체 제작 자연음",color=Muted,fontSize=18.sp,modifier=Modifier.weight(1f),maxLines=1)
            Text("전체 보기  ›",color=Gold,fontSize=21.sp,modifier=Modifier.clickable(onClick=onOpen))
        }
    }
}

@Composable
private fun AlbumArt(modifier:Modifier=Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp))) {
        ScenicResource(R.drawable.chabak_night_photo,Modifier.fillMaxSize(),ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x12071322),Color(0xC0081A26)))))
        Column(Modifier.align(Alignment.BottomStart).padding(11.dp)) {
            Text("CHABAK",color=Frost,fontSize=24.sp,fontWeight=FontWeight.Bold)
            Text("NATURE SESSIONS",color=Gold,fontSize=15.sp)
        }
    }
}

@Composable
private fun PlayerControl(symbol:String,primary:Boolean=false,onClick:()->Unit) {
    Box(Modifier.size(if(primary) 60.dp else 48.dp)
        .clip(RoundedCornerShape(30.dp))
        .background(if(primary) Gold else Color(0x28FFFFFF))
        .border(1.dp,if(primary) Gold else GlassBorder,RoundedCornerShape(30.dp))
        .clickable(onClick=onClick),contentAlignment=Alignment.Center) {
        Text(symbol,color=if(primary) Midnight else Frost,fontSize=if(primary) 30.sp else 24.sp,fontWeight=FontWeight.Bold)
    }
}

@Composable
private fun StayFeature(icon:String,title:String,modifier:Modifier,onClick:()->Unit) {
    Row(modifier.heightIn(min=103.dp).clip(RoundedCornerShape(17.dp)).background(Glass)
        .border(1.dp,GlassBorder,RoundedCornerShape(17.dp)).clickable(onClick=onClick)
        .padding(horizontal=11.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(icon,color=Gold,fontSize=47.sp,fontWeight=FontWeight.Bold)
        Text(title,color=Frost,fontSize=29.sp,fontWeight=FontWeight.Bold,maxLines=2,lineHeight=32.sp)
    }
}

@Composable
private fun SettingSlider(title:String,value:Float,label:String,range:ClosedFloatingPointRange<Float>,onChange:(Float)->Unit) {
    Column {
        Row(verticalAlignment=Alignment.CenterVertically) { Text(title,color=Frost,fontSize=24.sp,modifier=Modifier.weight(1f));Text(label,color=Gold,fontSize=24.sp,fontWeight=FontWeight.Bold) }
        Slider(value=value,onValueChange=onChange,valueRange=range,colors=SliderDefaults.colors(thumbColor=Frost,activeTrackColor=Mint,inactiveTrackColor=Color(0xFF45626A)))
    }
}

@Composable
private fun PlaylistScreen(s:AppState) {
    val genres=listOf("재즈","샹송","K-POP","클래식","로파이","어쿠스틱")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=760.dp
        if(wide) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            GlassPanel(Modifier.weight(1.22f).fillMaxHeight()) {
                SectionTitle("자연음 플레이어","숲·비·파도·백색소음을 실제로 들어보세요")
                Spacer(Modifier.height(9.dp))
                CompactMusicPlayer(s,Modifier.weight(1f).fillMaxWidth(),onOpen={})
            }
            GlassPanel(Modifier.weight(.78f).fillMaxHeight()) {
                PlaylistOptions(s,genres,Modifier.weight(1f))
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(11.dp)) {
            GlassPanel(Modifier.fillMaxWidth().height(420.dp)) {
                SectionTitle("지금의 플레이리스트")
                CompactMusicPlayer(s,Modifier.weight(1f).fillMaxWidth(),onOpen={})
            }
            GlassPanel(Modifier.fillMaxWidth()) { PlaylistOptions(s,genres,Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun PlaylistOptions(s:AppState,genres:List<String>,modifier:Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        val audio = LocalNatureAudio.current
        Text("실제로 듣는 자연음",fontSize=29.sp,color=Frost,fontWeight=FontWeight.Bold)
        natureTrackNames.chunked(2).forEach { pair ->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                pair.forEach { sound ->
                    OutlineChip(sound,s.natureSound==sound,Modifier.weight(1f),onClick={audio?.select(sound)})
                }
            }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("음량",color=Frost,fontSize=23.sp)
            Slider(value=s.natureVolume,onValueChange={audio?.setVolume(it)},modifier=Modifier.weight(1f),valueRange=0f..1f)
            Text("${(s.natureVolume*100).toInt()}%",color=Gold,fontSize=21.sp)
        }
        Text("음악 취향 선택 · 추천 전용",fontSize=28.sp,color=Frost,fontWeight=FontWeight.Bold)
        genres.chunked(2).forEach { pair ->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                pair.forEach { g -> OutlineChip(g,s.music==g,Modifier.weight(1f),onClick={s.music=g;s.saveRest();s.shufflePlaylist()}) }
            }
        }
        Text("음악 추천 콘셉트 (재생 미연동)",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Frost)
        s.suggestedTracks().take(4).forEachIndexed { idx,label ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if(idx==s.demoTrackIndex) Color(0x9B416B5D) else Color(0x64445A59))
                .clickable { s.demoTrackIndex=idx;s.demoStop() }
                .padding(horizontal=12.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(idx==s.demoTrackIndex) "♪" else "♫",fontSize=27.sp,color=Gold)
                Spacer(Modifier.width(9.dp))
                Text(label.substringAfter(". "),fontSize=23.sp,color=Frost,maxLines=1,modifier=Modifier.weight(1f))
            }
        }
        ActionButton("✦ 다른 분위기로 다시 추천",Modifier.fillMaxWidth(),onClick={s.shufflePlaylist()})
        Text("자연음 4종은 기기에서 실제 재생돼요. 재즈·샹송 등 음악은 추천 화면만 제공하며 스트리밍 연동 전이에요.",color=Gold,fontSize=20.sp,lineHeight=27.sp)
    }
}

@Composable
private fun EnvironmentScreen(s:AppState) {
    val scenes=listOf("별빛","숲속","비 오는 밤","노을")
    val sounds=listOf("숲소리","빗소리","파도소리","백색소음","조용하게")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=760.dp
        if(wide) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(13.dp)) {
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) { EnvironmentLeft(s,scenes,Modifier.weight(1f)) }
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) { EnvironmentRight(s,sounds,Modifier.weight(1f)) }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            GlassPanel(Modifier.fillMaxWidth()) { EnvironmentLeft(s,scenes,Modifier.height(320.dp)) }
            GlassPanel(Modifier.fillMaxWidth()) { EnvironmentRight(s,sounds,Modifier.height(330.dp)) }
        }
    }
}

@Composable
private fun EnvironmentLeft(s:AppState,scenes:List<String>,modifier:Modifier) {
    SectionTitle("나만의 차박 분위기","고른 분위기를 다음 차박에도 이어가요")
    Column(modifier,verticalArrangement=Arrangement.spacedBy(11.dp)) {
        ScenicResource(when(s.ambience){"숲속"->R.drawable.chabak_day_photo;"노을"->R.drawable.chabak_evening_photo;"비 오는 밤"->R.drawable.chabak_morning_photo;else->R.drawable.chabak_night_photo},Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)),ContentScale.Crop)
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){scenes.take(2).forEach{t->OutlineChip(t,s.ambience==t,Modifier.weight(1f),onClick={s.ambience=t;s.saveRest()})}}
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){scenes.drop(2).forEach{t->OutlineChip(t,s.ambience==t,Modifier.weight(1f),onClick={s.ambience=t;s.saveRest()})}}
    }
}

@Composable
private fun EnvironmentRight(s:AppState,sounds:List<String>,modifier:Modifier) {
    val audio = LocalNatureAudio.current
    SectionTitle("자연음 · 분위기","자연음은 실제 재생 · 차량 조명은 아직 시연")
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("듣고 싶은 소리",color=Frost,fontSize=26.sp,fontWeight=FontWeight.Bold)
        sounds.chunked(2).forEach { line ->
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                line.forEach { sound ->
                    OutlineChip(sound,s.natureSound==sound,Modifier.weight(1f),onClick={audio?.select(sound)})
                }
                if (line.size==1) Spacer(Modifier.weight(1f))
            }
        }
        Text("${s.natureSound}  ·  ${s.naturePlaybackStatus}",color=Gold,fontSize=26.sp,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
            PlayerControl("|◀",onClick={audio?.previous()})
            PlayerControl(if(s.naturePlaybackStatus=="재생 중") "Ⅱ" else "▶",primary=true,onClick={audio?.toggle()})
            PlayerControl("■",onClick={audio?.stop()})
            PlayerControl("▶|",onClick={audio?.next()})
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("음량",fontSize=22.sp,color=Frost)
            Slider(value=s.natureVolume,onValueChange={audio?.setVolume(it)},modifier=Modifier.weight(1f))
            Text("${(s.natureVolume*100).toInt()}%",color=Gold,fontSize=21.sp)
        }
        if(s.naturePlaybackError.isNotEmpty()) Text(s.naturePlaybackError,color=Gold,fontSize=19.sp)
        Text("실제 자연음은 인터넷 없이 재생돼요. 차량 조명 자동 제어는 연결 전입니다.",color=Muted,fontSize=19.sp)
        ActionButton("내 분위기 저장하기",Modifier.fillMaxWidth(),onClick={s.saveRest()})
    }
}

@Composable
private fun MorningScreen(s: AppState) {
    val ctx = LocalContext.current
    val sounds = listOf("숲소리", "빗소리", "파도소리", "백색소음")
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        s.morningStatus = if (granted) "알림이 허용됐어요. '알람 예약하기'를 다시 눌러 주세요."
        else "알림 권한이 없어 알람을 예약하지 않았어요."
    }
    val applyAlarm: () -> Unit = {
        s.saveMorning()
        when {
            !s.morningEnabled -> {
                ChabakMorningScheduler.cancel(ctx)
                s.morningStatus = "알람을 해제했어요."
            }
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED -> {
                s.morningStatus = "알림 권한 허용 후 '알람 예약하기'를 다시 눌러 주세요."
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> s.morningStatus = ChabakMorningScheduler.scheduleNext(ctx, s.wakeHour, s.wakeMinute)
        }
    }
    val testAlarm: () -> Unit = {
        s.saveMorning()
        when {
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                s.morningStatus = "알림 권한을 먼저 허용해 주세요."
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> s.morningStatus = ChabakMorningScheduler.scheduleTest(ctx)
        }
    }
    LaunchedEffect(Unit) {
        s.morningStatus = ChabakMorningScheduler.status(ctx)
        s.morningEnabled = ChabakMorningScheduler.isScheduled(ctx)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) {
                MorningLeft(s, ctx, Modifier.weight(1f))
            }
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) {
                MorningRight(s, sounds, Modifier.weight(1f), applyAlarm, testAlarm)
            }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassPanel(Modifier.fillMaxWidth()) { MorningLeft(s, ctx, Modifier.height(660.dp)) }
            GlassPanel(Modifier.fillMaxWidth()) { MorningRight(s, sounds, Modifier.height(570.dp), applyAlarm, testAlarm) }
        }
    }
}

// STEP047: 큰 터치 영역을 가진 삼성 스타일 3열 휠.
// 드래그는 숫자를 한 칸씩 넘기며, 위/아래 숫자를 눌러도 조절할 수 있습니다.
@Composable
private fun ChabakWheelColumn(
    title: String,
    value: Int,
    size: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val stepPx = with(density) { 47.dp.toPx() }
    var dragAccumulator by remember { mutableStateOf(0f) }
    val latestValue by rememberUpdatedState(value)
    val latestOnChange by rememberUpdatedState(onChange)
    val wrapped: (Int) -> Int = { n -> ((n % size) + size) % size }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize=20.sp, color=Muted, fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier.fillMaxWidth().height(238.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0x27071C2B))
                .pointerInput(size) {
                    detectVerticalDragGestures(
                        onDragStart = { dragAccumulator = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulator += dragAmount
                            if (kotlin.math.abs(dragAccumulator) >= stepPx) {
                                // 손가락을 위로 밀면 숫자는 증가.
                                val steps = (kotlin.math.abs(dragAccumulator) / stepPx).toInt()
                                val direction = if (dragAccumulator < 0f) 1 else -1
                                latestOnChange(wrapped(latestValue + direction * steps))
                                dragAccumulator -= (if (direction == 1) -stepPx else stepPx) * steps
                            }
                        },
                        onDragEnd = { dragAccumulator = 0f },
                        onDragCancel = { dragAccumulator = 0f }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.fillMaxWidth().height(65.dp)
                    .padding(horizontal=4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x46FFFFFF))
                    .border(1.dp, Color(0x9AFFFFFF), RoundedCornerShape(14.dp))
                    .align(Alignment.Center)
            )
            Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                for (offset in -2..2) {
                    val selected = offset == 0
                    val number = wrapped(value + offset)
                    Box(
                        Modifier.fillMaxWidth().height(if (selected) 65.dp else 42.dp)
                            .clickable { if (offset != 0) onChange(number) },
                        contentAlignment=Alignment.Center
                    ) {
                        Text(format(number), fontSize=if(selected) 40.sp else if(kotlin.math.abs(offset)==1) 26.sp else 20.sp,
                            fontWeight=if(selected) FontWeight.Bold else FontWeight.Medium,
                            color=if(selected) Frost else if(kotlin.math.abs(offset)==1) Color(0xBFE9F2EE) else Color(0x75E9F2EE))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChabakAlarmWheelPicker(hour24: Int, minute: Int, onTimeChanged: (Int, Int) -> Unit) {
    val pm = hour24 >= 12
    val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    fun convert(period: Int, twelveHourIndex: Int): Int {
        val hour = (twelveHourIndex % 12)
        return if (period == 1) hour + 12 else hour
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .background(Color(0x2BFFFFFF)).border(1.dp, GlassBorder, RoundedCornerShape(22.dp))
        .padding(15.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            ChabakWheelColumn("오전 / 오후", if (pm) 1 else 0, 2,
                { if (it == 0) "오전" else "오후" },
                { period -> onTimeChanged(convert(period, hour12), minute) }, Modifier.weight(1.15f))
            ChabakWheelColumn("시", hour12 - 1, 12,
                { "%02d".format(Locale.KOREA, it + 1) },
                { index -> onTimeChanged(convert(if (pm) 1 else 0, index + 1), minute) }, Modifier.weight(1f))
            ChabakWheelColumn("분", minute, 60,
                { "%02d".format(Locale.KOREA, it) },
                { m -> onTimeChanged(hour24, m) }, Modifier.weight(1f))
        }
        Text("설정 시간  ·  %s %02d:%02d".format(Locale.KOREA,
            if (pm) "오후" else "오전", hour12, minute),
            modifier=Modifier.fillMaxWidth(), textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            fontSize=23.sp, color=Gold, fontWeight=FontWeight.SemiBold)
    }
}

@Composable
private fun MorningLeft(s: AppState, ctx: Context, modifier: Modifier) {
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val label = android.provider.OpenableColumns.DISPLAY_NAME.let { field ->
                    ctx.contentResolver.query(uri, arrayOf(field), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                } ?: (uri.lastPathSegment ?: "선택한 음악")
                s.selectedWakeTrackUri = uri.toString()
                s.selectedWakeTrackName = label
                ctx.getSharedPreferences("chabak_3", Context.MODE_PRIVATE).edit()
                    .putString("wakeMusicUri", uri.toString())
                    .putString("wakeMusicName", label).apply()
                s.morningStatus = "알람 음악 저장 완료: $label"
            } catch (_: Exception) {
                s.morningStatus = "음악 파일을 읽을 수 없어요. 다른 파일을 골라 주세요."
            }
        }
    }
    SectionTitle("스마트 모닝", "자연음으로 편안하게 일어나요")
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("알람 시간", color=Muted, fontSize=21.sp)
                Text("숫자를 위아래로 밀어 설정하세요", color=Gold, fontSize=17.sp)
            }
            Switch(checked = s.morningEnabled, onCheckedChange = { enabled ->
                s.morningEnabled = enabled
                s.saveMorning()
                if (!enabled) { ChabakMorningScheduler.cancel(ctx); s.morningStatus = "알람을 해제했어요." }
                else s.morningStatus = "알람 예약하기 버튼으로 활성화해 주세요."
            }, colors = SwitchDefaults.colors(checkedThumbColor = Frost, checkedTrackColor = Color(0xFF87BC7D)))
        }
        ChabakAlarmWheelPicker(
            hour24 = s.wakeHour,
            minute = s.wakeMinute,
            onTimeChanged = { h, m ->
                s.wakeHour = h
                s.wakeMinute = m
                s.saveMorning()
                s.morningStatus = "시간을 변경했어요. '알람 예약하기'를 누르면 적용됩니다."
            }
        )
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("앱을 보고 있을 때 풍경 연출", color=Frost, fontSize=22.sp,
                modifier=Modifier.weight(1f))
            Switch(checked=s.sunriseForegroundOptIn, onCheckedChange={ enabled ->
                s.sunriseForegroundOptIn = enabled; s.saveMorning()
            })
        }
        Text("정차 중에만 켜주세요 · 차량 주행 상태 미연동", color=Muted, fontSize=18.sp)
        Text("아침 05:00~10:59 · 햇살과 자연음 / 다른 시간 · 풍경과 나만의 음악", color=Gold, fontSize=19.sp)
        ActionButton("♫ 나만의 알람 음악 선택", Modifier.fillMaxWidth(), filled=false,
            onClick={ musicPicker.launch(arrayOf("audio/*")) })
        Text(if (s.selectedWakeTrackName.isNotBlank()) "설정한 음악: ${s.selectedWakeTrackName}"
        else "선택한 음악이 없으면 기존 자연음으로 울려요.", color=Muted, fontSize=19.sp)
        if (s.selectedWakeTrackUri.isNotBlank()) {
            Text("선택 음악 해제", color=Gold, fontSize=19.sp, modifier=Modifier.clickable {
                s.selectedWakeTrackUri=""; s.selectedWakeTrackName=""
                ctx.getSharedPreferences("chabak_3",Context.MODE_PRIVATE).edit()
                    .remove("wakeMusicUri").remove("wakeMusicName").apply()
            })
        }
        Text("일출처럼 깨우기 (차량 조명 시연)", color = Frost, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().height(75.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFB86C80), Color(0xFFFFD387))))) {
            Text("☀", fontSize = 48.sp, color = Frost, modifier = Modifier.align(Alignment.Center))
        }
        Text("조명 연출 시간 · 차량 연동 전", color = Muted, fontSize = 22.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(5,15,30).forEach { m ->
                OutlineChip("${m}분", s.morningFade == m, Modifier.weight(1f), onClick = {
                    s.morningFade = m; s.saveMorning()
                })
            }
        }
        SettingSlider("조명 밝기 시연", s.morningLevel * 100f, "${(s.morningLevel * 100).toInt()}%",
            0f..100f, { s.morningLevel = it / 100f; s.saveMorning() })
        ActionButton("일출 화면 미리보기", Modifier.fillMaxWidth(), filled = false, onClick = {
            s.wakeScenePreview = true
            s.wakeSceneLive = false
            s.wakeTransitionSerial++
        })
    }
}

@Composable
private fun MorningRight(
    s: AppState,
    sounds: List<String>,
    modifier: Modifier,
    onSchedule: () -> Unit,
    onTest: () -> Unit
) {
    val natureAudio = LocalNatureAudio.current
    SectionTitle("알람 자연음", "선택한 소리로 실제 알람이 울려요")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sounds.chunked(2).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { name ->
                    OutlineChip(name, name == s.wakeSound, Modifier.weight(1f), onClick = {
                        s.wakeSound = name; s.saveMorning()
                    })
                }
            }
        }
        Text("소리는 1분 동안 서서히 커져요. (알람은 최대 5분)", color = Muted, fontSize = 20.sp)
        Text(s.morningStatus, color = Gold, fontSize = 22.sp, lineHeight = 27.sp)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            ActionButton("▶ 미리듣기", Modifier.weight(1f), filled = false, onClick = {
                natureAudio?.select(s.wakeSound)
                natureAudio?.start()
            })
            ActionButton("■ 끄기", Modifier.weight(1f), filled = false, onClick = { natureAudio?.stop() })
        }
        ActionButton(if(s.morningEnabled) "알람 예약하기" else "알람 해제", Modifier.fillMaxWidth(), onClick = onSchedule)
        ActionButton("1분 뒤 시험 알람", Modifier.fillMaxWidth(), filled = false, onClick = onTest)
        Text("알람을 끄거나 5분 뒤 다시 울리려면 기기 알림창의 버튼을 사용하세요.", color = Muted, fontSize = 20.sp)
        Text("※ 정확한 알람 권한이 없는 Pleos에서는 예약 시각보다 늦게 울릴 수 있어요. 기기/앱 제한에 따라 울리지 않을 수도 있습니다.", color = Gold, fontSize = 18.sp)
    }
}

// 앱 화면 안에서만 밝아지는 일출 효과. 차량 조명은 제어하지 않습니다.
@Composable
private fun DawnLightingEffect() {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { glow.animateTo(.28f, animationSpec=tween(durationMillis=9000)) }
    Box(Modifier.fillMaxSize().background(Color(0xFFFFE0A0).copy(alpha=glow.value)))
}

@Composable
private fun MorningWakeScene(s: AppState, phase: TimeScene, onStop: () -> Unit, onSnooze: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment=Alignment.BottomStart) {
        GlassPanel(Modifier.fillMaxWidth(.85f)) {
            // 아침 인사는 반드시 현지 기기 시각 05:00~10:59 사이에만 표시합니다.
            Text(if (phase.index == 0) "좋은 아침이에요 ☀️" else phase.greeting,
                color=Frost, fontSize=44.sp, fontWeight=FontWeight.Bold, lineHeight=50.sp)
            Spacer(Modifier.height(13.dp))
            Text(if (s.wakeScenePreview) "일출 화면 미리보기 · 알람은 재생하지 않습니다"
            else "스마트 모닝 · ${s.wakeSound} 알람",
                color=Gold, fontSize=24.sp)
            Spacer(Modifier.height(13.dp))
            Text("눈을 뜨면 호숫가의 아침처럼 화면이 천천히 밝아져요.",
                color=Muted, fontSize=23.sp)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ActionButton(if (s.wakeScenePreview) "돌아가기" else "■ 알람 끄기",
                    Modifier.weight(1f), onClick=onStop)
                if (!s.wakeScenePreview) ActionButton("5분 뒤 다시", Modifier.weight(1f),
                    filled=false, onClick=onSnooze)
                else ActionButton("차박 중으로", Modifier.weight(1f), filled=false, onClick={
                    s.wakeScenePreview=false; s.back(); s.navigate("stay")
                })
            }
        }
    }
}

@Composable
private fun DiaryScreen(s:AppState) {
    val ctx=LocalContext.current
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->
        if(uri!=null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }catch(_:Exception){}
            s.dayPhoto=uri.toString()
        }
    }
    LaunchedEffect(Unit) { if(s.dayTitle.isBlank()) s.openDiary(-1) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide=maxWidth>=760.dp
        if(wide) Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(13.dp)) {
            GlassPanel(Modifier.weight(.75f).fillMaxHeight()) { DiaryList(s,Modifier.weight(1f)) }
            GlassPanel(Modifier.weight(1.25f).fillMaxHeight()) { DiaryEditor(s,Modifier.weight(1f),onPhoto={photoPicker.launch(arrayOf("image/*"))}) }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            GlassPanel(Modifier.fillMaxWidth()) { DiaryList(s,Modifier.height(280.dp)) }
            GlassPanel(Modifier.fillMaxWidth()) { DiaryEditor(s,Modifier.height(510.dp),onPhoto={photoPicker.launch(arrayOf("image/*"))}) }
        }
    }
}

@Composable
private fun DiaryList(s:AppState,modifier:Modifier) {
    Row(verticalAlignment=Alignment.CenterVertically) { Text("나의 차박 기록",color=Frost,fontSize=29.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));OutlineChip("+ 새 기록",false,onClick={s.openDiary(-1)}) }
    Spacer(Modifier.height(12.dp))
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        if(s.diaries.isEmpty()) {
            Text("아직 저장한 기록이 없어요.\n오늘의 차박부터 남겨 보세요.",color=Muted,fontSize=24.sp,lineHeight=27.sp)
        }
        s.diaries.forEachIndexed {idx,d->
            Row(Modifier.fillMaxWidth().heightIn(min=100.dp).clip(RoundedCornerShape(15.dp)).background(if(s.editingDiary==idx) Color(0x8B695F38) else Color(0x75325052)).border(1.dp,if(s.editingDiary==idx) Gold else GlassBorder,RoundedCornerShape(15.dp)).clickable {s.openDiary(idx)}.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                DiaryPhoto(d.photoUri,R.drawable.chabak_night_photo,Modifier.size(100.dp,80.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f)) { Text("${d.date} · ${d.place}",color=Muted,fontSize=24.sp,maxLines=1);Text(d.title,color=Frost,fontSize=24.sp,fontWeight=FontWeight.Bold,maxLines=2) }
                Text("›",color=Gold,fontSize=33.sp)
            }
        }
    }
    Text("일기와 사진 선택 결과는 이 기기에만 저장됩니다.",color=Muted,fontSize=24.sp)
}

@Composable
private fun DiaryEditor(s:AppState,modifier:Modifier,onPhoto:()->Unit) {
    val ctx = LocalContext.current
    SectionTitle("오늘의 기록","사진과 추억을 간단하게 남겨요")
    Column(modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("${LocalDate.now()}  |  ${s.selectedPlace.name}",color=Muted,fontSize=24.sp)
        InputDemo(s.dayTitle,{s.dayTitle=it},"오늘의 기록 제목")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            DiaryPhoto(s.dayPhoto,R.drawable.chabak_lakeside_car,Modifier.weight(1f).height(132.dp).clip(RoundedCornerShape(12.dp)))
            Box(Modifier.weight(.6f).height(132.dp).clip(RoundedCornerShape(12.dp)).border(1.dp,GlassBorder,RoundedCornerShape(12.dp)).clickable(onClick=onPhoto),contentAlignment=Alignment.Center) {Text("＋\n사진 추가",fontSize=24.sp,color=Frost,lineHeight=27.sp)}
        }
        Text("오늘의 기분",color=Frost,fontSize=24.sp,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {listOf("편안해요","신나요","행복해요","아쉬워요").forEach {m->OutlineChip(m,m==s.dayMood,onClick={s.dayMood=m})}}
        Text("한 줄 남기기",color=Frost,fontSize=24.sp,fontWeight=FontWeight.Bold)
        TextField(value=s.dayNote,onValueChange={s.dayNote=it},placeholder={Text("오늘 가장 좋았던 순간은 무엇인가요?",color=Muted)},modifier=Modifier.fillMaxWidth().heightIn(min=110.dp),minLines=2,maxLines=5,colors=TextFieldDefaults.colors(focusedTextColor=Frost,unfocusedTextColor=Frost,focusedContainerColor=Color(0x95385256),unfocusedContainerColor=Color(0x95385256),focusedIndicatorColor=Mint,unfocusedIndicatorColor=GlassBorder))
    }
    Spacer(Modifier.height(9.dp))
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ActionButton("동행인과 공유",Modifier.weight(1f),filled=false,onClick={
            val i=Intent(Intent.ACTION_SEND).apply {type="text/plain";putExtra(Intent.EXTRA_TEXT,"CHABAK 차박 기록\n${s.dayTitle}\n${s.dayNote}")}
            try{ctx.startActivity(Intent.createChooser(i,"차박 기록 공유"))}catch(_:Exception){}
        })
        ActionButton("기록 저장",Modifier.weight(1f),onClick={s.saveDiary();Toast.makeText(ctx,"차박 기록을 저장했어요",Toast.LENGTH_SHORT).show()})
    }
}

@Composable
private fun DiaryPhoto(uri:String,fallback:Int,modifier:Modifier) {
    val ctx=LocalContext.current
    val bitmap:ImageBitmap?=remember(uri) {
        if(uri.isBlank()) null else try {
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { stream->BitmapFactory.decodeStream(stream,null,BitmapFactory.Options().apply{inSampleSize=4})?.asImageBitmap() }
        }catch(_:Exception){null}
    }
    if(bitmap!=null) Image(bitmap=bitmap,contentDescription="차박 기록 사진",contentScale=ContentScale.Crop,modifier=modifier)
    else ScenicResource(fallback,modifier,ContentScale.Crop)
}

@Composable
internal fun InputDemo(value:String,onValue:(String)->Unit,hint:String,modifier:Modifier=Modifier) {
    OutlinedTextField(value=value,onValueChange=onValue,modifier=modifier.fillMaxWidth(),singleLine=true,textStyle=androidx.compose.ui.text.TextStyle(color=Frost,fontSize=24.sp),placeholder={Text(hint,color=Muted,fontSize=24.sp)},shape=RoundedCornerShape(12.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Mint,unfocusedBorderColor=GlassBorder,focusedContainerColor=Color(0x20FFFFFF),unfocusedContainerColor=Color(0x20FFFFFF),cursorColor=Mint))
}


// STEP041: Android AlarmManager + BroadcastReceiver + foreground playback service.
// This is a user-scheduled, one-shot alarm; it does not change any vehicle hardware.
private object ChabakMorningScheduler {
    const val KEY_TIME = "chabak_alarm_at"
    private const val PREFS = "chabak_3"
    private const val REQUEST_CODE = 4101
    private const val TEST_REQUEST_CODE = 4111
    const val KEY_TEST_TIME = "chabak_alarm_test_at"
    const val KEY_LAST_TRIGGER = "chabak_alarm_last_trigger"
    const val KEY_LAST_WAS_TEST = "chabak_alarm_last_was_test"
    const val ACTION_RING = "com.chabak.app.MORNING_RING"
    const val ACTION_TEST = "com.chabak.app.MORNING_TEST"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun manager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun ringIntent(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ChabakMorningReceiver::class.java).setAction(ACTION_RING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private fun testIntent(context: Context) = PendingIntent.getBroadcast(
        context, TEST_REQUEST_CODE,
        Intent(context, ChabakMorningReceiver::class.java).setAction(ACTION_TEST),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    fun canSchedule(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager(context).canScheduleExactAlarms()

    // Pleos 등 정확한 알람 권한 화면이 없는 기기에서는 별도 권한 없이 비정확 알람으로 예약합니다.
    // 비정확 알람은 시스템 절전/배터리 정책에 따라 예약 시각보다 수 분 이상 늦게 울릴 수 있습니다.
    private fun scheduleSystemAlarm(context: Context, whenToRing: Long,
                                    showRequestCode: Int, event: PendingIntent): Boolean {
        val exact = canSchedule(context)
        val alarmManager = manager(context)
        if (exact) {
            val showAlarm = PendingIntent.getActivity(context, showRequestCode,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("open_morning", true)
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(whenToRing, showAlarm), event)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenToRing, event)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, whenToRing, event)
        }
        return exact
    }

    fun isScheduled(context: Context) =
        prefs(context).getLong(KEY_TIME, 0L) > System.currentTimeMillis()

    fun scheduleTest(context: Context): String {
        val whenToRing = System.currentTimeMillis() + 60_000L
        return try {
            val exact = scheduleSystemAlarm(context, whenToRing, 4102, testIntent(context))
            prefs(context).edit().putLong(KEY_TEST_TIME, whenToRing).apply()
            if (exact) "1분 뒤 시험 알람 예약 완료 · 기존 아침 알람은 유지돼요."
            else "대체 시험 알람 예약 완료 · 약 1분 뒤 예정이지만 Pleos 절전 상태에 따라 늦어질 수 있어요."
        } catch (e: Exception) { "시험 알람 예약 실패: ${e.javaClass.simpleName}" }
    }

    fun scheduleNext(context: Context, hour: Int, minute: Int): String {
        val whenToRing = Calendar.getInstance(ChabakLegacyTimeZone).apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DATE, 1)
        }.timeInMillis
        return scheduleAt(context, whenToRing)
    }

    fun scheduleAt(context: Context, whenToRing: Long): String {
        if (whenToRing <= System.currentTimeMillis()) return "미래 시각을 선택해 주세요."
        return try {
            val exact = scheduleSystemAlarm(context, whenToRing, 4102, ringIntent(context))
            prefs(context).edit().putLong(KEY_TIME, whenToRing).putBoolean("morningEnabled", true).apply()
            val time = java.text.SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA).apply { timeZone=ChabakLegacyTimeZone }.format(java.util.Date(whenToRing))
            if (exact) "$time 정확한 알람 예약 완료"
            else "$time 대체 알람 예약 완료 · Pleos 기기 사정으로 늦게 울릴 수 있어요."
        } catch (e: Exception) {
            "알람 예약에 실패했어요: ${e.javaClass.simpleName}"
        }
    }

    fun cancel(context: Context) {
        manager(context).cancel(ringIntent(context))
        manager(context).cancel(testIntent(context))
        prefs(context).edit().remove(KEY_TIME).remove(KEY_TEST_TIME).putBoolean("morningEnabled", false).apply()
        try { context.startService(Intent(context, ChabakMorningPlaybackService::class.java).setAction(ChabakMorningPlaybackService.ACTION_STOP)) }
        catch (_: Exception) { /* stopped service may not exist */ }
    }

    fun onDelivered(context: Context) {
        prefs(context).edit().remove(KEY_TIME).putBoolean("morningEnabled", false).apply()
    }

    fun status(context: Context): String {
        val next = prefs(context).getLong(KEY_TIME, 0L)
        if (next == 0L || next <= System.currentTimeMillis()) return "예약된 알람이 없어요. 시간을 고르고 예약해 주세요."
        val mode = if (canSchedule(context)) "정확한 알람 예약됨" else "대체 알람 예약됨 (시간 지연 가능)"
        return "$mode · " + java.text.SimpleDateFormat("M월 d일 a h:mm", Locale.KOREA)
            .apply { timeZone=ChabakLegacyTimeZone }.format(java.util.Date(next))
    }

    fun restoreAfterBoot(context: Context) {
        val next = prefs(context).getLong(KEY_TIME, 0L)
        if (next > System.currentTimeMillis()) scheduleAt(context, next)
        else if (next <= System.currentTimeMillis()) onDelivered(context)
    }
}

class ChabakMorningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val isTest = intent?.action == ChabakMorningScheduler.ACTION_TEST
        if (!isTest && intent?.action != ChabakMorningScheduler.ACTION_RING) return
        val key = if (isTest) ChabakMorningScheduler.KEY_TEST_TIME else ChabakMorningScheduler.KEY_TIME
        val prefs = context.getSharedPreferences("chabak_3", Context.MODE_PRIVATE)
        if (prefs.getLong(key, 0L) == 0L) return // canceled / stale broadcast
        if (isTest) prefs.edit().remove(key).apply()
        else ChabakMorningScheduler.onDelivered(context)
        prefs.edit()
            .putLong(ChabakMorningScheduler.KEY_LAST_TRIGGER, System.currentTimeMillis())
            .putBoolean(ChabakMorningScheduler.KEY_LAST_WAS_TEST, isTest)
            .apply()
        val start = Intent(context, ChabakMorningPlaybackService::class.java)
            .setAction(ChabakMorningPlaybackService.ACTION_PLAY)
            .putExtra("chabak_test_alarm", isTest)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(start)
            else context.startService(start)
        } catch (_: Exception) {
            // This can happen on vendor infotainment builds that disallow background playback.
        }
    }
}

class ChabakMorningBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent?.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            ChabakMorningScheduler.restoreAfterBoot(context)
        }
    }
}

class ChabakMorningPlaybackService : Service() {
    companion object {
        const val ACTION_PLAY = "com.chabak.app.MORNING_PLAY"
        const val ACTION_STOP = "com.chabak.app.MORNING_STOP"
        const val ACTION_SNOOZE = "com.chabak.app.MORNING_SNOOZE"
        private const val CHANNEL_ID = "chabak_morning_alarm_v1"
        private const val NOTIFICATION_ID = 4101
    }
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var request: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null
    private var fadeFrom = 0L
    private var testAlarm = false
    private val fadeRunnable = object: Runnable {
        override fun run() {
            val elapsed = (SystemClock.elapsedRealtime() - fadeFrom).coerceAtLeast(0L)
            val fraction = (elapsed / 60_000f).coerceIn(0f, 1f)
            val target = getSharedPreferences("chabak_3", Context.MODE_PRIVATE)
                .getFloat("natureVolume", .55f).coerceIn(.15f, 1f)
            val actual = target * (.25f + .75f * fraction)
            try { player?.setVolume(actual, actual) } catch (_: Exception) { }
            if (player != null && fraction < 1f) handler.postDelayed(this, 1200L)
        }
    }
    private val stopRunnable = Runnable { stopAlarm() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAlarm(); return START_NOT_STICKY }
            ACTION_SNOOZE -> {
                stopAlarm()
                ChabakMorningScheduler.scheduleAt(this, System.currentTimeMillis() + 5 * 60_000L)
                return START_NOT_STICKY
            }
            ACTION_PLAY -> { testAlarm = intent?.getBooleanExtra("chabak_test_alarm", false) == true; startAlarm() }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun resId(): Int = when (getSharedPreferences("chabak_3", Context.MODE_PRIVATE)
        .getString("wakeSound", "숲소리")) {
        "빗소리" -> R.raw.chabak_rain
        "파도소리" -> R.raw.chabak_waves
        "백색소음" -> R.raw.chabak_white_noise
        else -> R.raw.chabak_forest
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "CHABAK 스마트 모닝", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "설정한 시각에 울리는 자연음 알람"
            setSound(null, null) // playback comes from MediaPlayer, not notification
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 4103,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_morning", true)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getService(this, 4104,
            Intent(this, ChabakMorningPlaybackService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 4105,
            Intent(this, ChabakMorningPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (testAlarm) "CHABAK 시험 알람" else "CHABAK 스마트 모닝")
            .setContentText(if (isMorningTime())
                "좋은 아침이에요 · 자연음 알람이 울리고 있어요"
            else "${getSharedPreferences("chabak_3", Context.MODE_PRIVATE).getString("wakeMusicName", "자연의 소리")} 재생 중")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "5분 뒤 다시", snooze).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "알람 끄기", stop).build())
            .build()
    }

    private fun startAlarm() {
        if (player != null) return
        try {
            createChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else startForeground(NOTIFICATION_ID, notification())
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { event ->
                    if (event == AudioManager.AUDIOFOCUS_LOSS) handler.post { stopAlarm() }
                }.build()
            if (audioManager?.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                stopAlarm(); return
            }
            request = focus
            // 오전 5~10시에는 자연음 고정. 그 외 시간은 사용자가 고른 로컬 음원 우선.
            val selectedUri = getSharedPreferences("chabak_3", Context.MODE_PRIVATE)
                .getString("wakeMusicUri", "").orEmpty()
            player = if (!isMorningTime() && selectedUri.isNotBlank()) {
                try {
                    MediaPlayer().apply {
                        setAudioAttributes(attributes)
                        setDataSource(this@ChabakMorningPlaybackService, Uri.parse(selectedUri))
                        prepare()
                    }
                } catch (_: Exception) {
                    // 저장한 파일이 사라졌거나 권한이 만료되면 기존 자연음을 대신 재생.
                    MediaPlayer.create(this, resId(), attributes, 0)
                }
            } else MediaPlayer.create(this, resId(), attributes, 0)
            if (player == null) error("Alarm audio not available")
            player?.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            player?.isLooping = true
            player?.setVolume(.12f, .12f)
            player?.start()
            fadeFrom = SystemClock.elapsedRealtime()
            handler.post(fadeRunnable)
            handler.postDelayed(stopRunnable, 5 * 60_000L)
        } catch (_: Exception) {
            stopAlarm()
        }
    }

    private fun stopAlarm() {
        handler.removeCallbacks(fadeRunnable)
        handler.removeCallbacks(stopRunnable)
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
        request?.let { audioManager?.abandonAudioFocusRequest(it) }
        request = null
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(fadeRunnable)
        handler.removeCallbacks(stopRunnable)
        try { player?.release() } catch (_: Exception) { }
        player = null
        request?.let { audioManager?.abandonAudioFocusRequest(it) }
        request = null
        super.onDestroy()
    }
}
