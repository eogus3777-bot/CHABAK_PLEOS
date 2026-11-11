# CHABAK Gleo 연동 구현·검증 보고

최신 구성은 [Gleo 단일 음성 AI 구성·검증 보고](CHABAK_GLEO_ONLY.md)를 따른다. 아래는 최초 Gleo 보완 작업의 기록이며 이후 추가했던 자체 AI 대화 코드는 제거했다.

기준일: 2026-11-09 KST. 앱 측 Intent/Callback 구현과 Pleos 에뮬레이터 검증을 수행했다. **실제 Gleo 발화 → Action 생성 → 앱 실행 → Gleo 응답 음성까지의 연동은 미검증이다.** 스키마 승인·배포 등록 여부도 확인하지 않았다.

## 기존 프로젝트 분석

| 항목 | 확인한 구현과 연결 경로 |
| --- | --- |
| `assets/schema.json` | `com.chabak.app`, 버전 `1.0`, 호출어 `차박`. 아래 Action 3개가 이미 정의되어 있어 그대로 사용했다. |
| Manifest | `MainActivity`가 exported, `singleTop`. MAIN/LAUNCHER와 세 Action + DEFAULT 필터가 이미 등록되어 있어 변경하지 않았다. |
| 최초 실행 / 재수신 | 기존 `onCreate()` / `onNewIntent()`에서 Channel로 명령을 전달했다. Activity 재생성과 응답 기한을 관리하는 앱 내부 세션으로 전달 경로를 보완했다. |
| 캠핑장 추천 | `AppState.searchFromGleo()` → 기존 `fetchRecommendations()` → `GoCampingCatalog.recommend()` → 개발 프록시 `http://10.0.2.2:8085/recommend`. 기존 필터·결과 검증·추천 화면을 재사용한다. |
| 여행지 검색 | `AppState.searchNearby()` → `TripPlacesCatalog.search()` → 기존 주변 즐길거리 화면. 선택 여행의 캠핑장 좌표, 내장 공식 장소 자료, 등록 장날 계산을 사용한다. 휴게소는 방향별 진입·진출 경로 증빙이 필요하다. |
| 차박 시작 | 기존 화면 버튼과 동일한 `startSelectedStay()`를 사용한다. 조작 허용 상태, 보관된 여행, 다른 진행 중 여행, 저장 실패를 확인한다. 여행 기록·스마트 모닝 세션을 시작하며 차량 제어는 수행하지 않는다. |
| 에너지 | 기존 `rememberVehicleEnergy()` / `PleosEnergyReader`와 스마트 에너지 화면을 재사용한다. 권한·지원 capability·SDK 연결 실패를 구분한다. 수동 시뮬레이션 값을 Gleo 응답에 사용하지 않는다. |
| 기존 Callback | 공식 Broadcast action과 JSON 배열 문자열 `data`는 구현되어 있었다. 비공식 상태 `error`, 10초 에너지 대기, 긴 HTTP 대기와 별개인 음성 응답 기한 부재, 중복 요청·반복 Callback 보호 부재를 보완했다. |

실제 Gradle 의존성과 캐시된 배포 소스를 확인했다. Vehicle `2.3.2`, NaviHelper `3.0.5`, Kotlin/Compose, AndroidX Lifecycle을 사용한다. `Vehicle.initialize`, `getEvBattery`, 충전량 capability/get/register/unregister 및 실제 Listener 선언을 확인했다. 추가 Pleos SDK 의존성이나 확인되지 않은 SDK 클래스를 도입하지 않았다. `GleoCommandSession`, `GleoCommandModel`, `GleoResponse`는 이 프로젝트의 내부 코드다.

## Action별 동작

| Action | 인자와 처리 |
| --- | --- |
| `SEARCH_CAMPING_PLACE` | 필수 `region`, 선택 `district`, `nature`, Boolean `toilet/electric/store`. `electric`은 캠핑용 전기이며 차량 충전소 조건이 아니다. 기존 추천 API와 화면으로 전달한다. |
| `SEARCH_TRIP_PLACES` | 필수 `category`: `REST_AREA/BREWERY/MARKET`. 선택 `date`: `YYYY-MM-DD`, 또는 Boolean `weekend`. `date`와 `weekend=true` 동시 지정은 거부한다. 기본 날짜는 선택한 여행 날짜다. |
| `MANAGE_CAMPING_TRIP` | 필수 `operation`: `START_STAY/READ_ENERGY`. 차박 시작은 기존 시작 함수를 호출하며 이미 시작된 여행은 `ALREADY_STARTED`로 반환한다. 배터리 조회는 SDK 충전량 0~100만 성공으로 반환한다. |

개별 타입 Extra, JSON 객체 문자열 `ACTION_PARAM`, JSON 문자열로 한 번 더 감싼 `ACTION_PARAM`을 지원한다. `ACTION_PARAM`이 있으면 그 객체를 사용한다. 숫자를 문자열로 바꾸거나 문자열 `"true"`를 Boolean으로 추정하지 않는다. 필수 인자 누락은 `asking`, 타입·열거값·날짜·길이·JSON 오류는 `fail/INVALID_PARAMETERS`로 응답한다.

Callback은 공식 action `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE`로 보내며 Extra는 `packageName`, `response`다. `response`는 `functionName/status/message/mode/data` JSON이고, `data`는 객체 배열을 인코딩한 **문자열**이다. 상태는 `success/asking/fail`만 사용하며 영어 완전 문장과 `mode=voice`를 보낸다. 정상 조회의 결과 0건은 `success/NO_RESULTS`, 서비스 실패는 `fail/SERVICE_FAILURE`로 구분한다.

## 기한·중복·수명 주기

- 앱이 명령을 접수한 시점부터 6초 기한을 적용한다. 공식 Gleo의 7초 취소 규정에 여유를 두며, 앱 프로세스 시작 전의 지연까지 측정하거나 보장하지는 않는다.
- 동일 Action과 정규화된 인자가 실행 중 다시 도착하면 하나의 요청과 Callback으로 합친다. 완료 후 같은 인자는 새로운 요청으로 받는다. 공식 문서에 없는 요청 ID를 가정하지 않는다.
- 다른 명령이 실행 중 도착하면 `fail/REQUEST_IN_PROGRESS`로 응답한다. 대기열 때문에 응답 기한이 누적되지 않는다.
- 시간 초과는 `fail/TIMEOUT`, 종료는 `fail/APP_CLOSED`다. 실행 직전에도 기한을 확인해 타이머 처리가 지연된 만료 명령을 실행하지 않는다. 종료·시간 초과 이후의 지연 결과와 반복 완료 통지는 추가 성공 Callback을 보낼 수 없다.
- ViewModel이 Activity 재생성 중 세션과 원래 기한을 유지한다. 완료된 최초 Intent는 재실행하지 않는다. 취소된 이전 화면의 캠핑 조회 Callback은 무시한다. 재생성 중 읽기 전용 조회는 새 화면에서 재시도될 수 있다.
- 기존 HTTP 조회와 화면 동작은 유지한다. 느린 추천 결과가 시간 초과 후 화면에 표시될 수 있으나 음성 요청을 뒤늦게 성공으로 바꾸지 않는다. 프로세스 강제 종료를 넘는 요청 영속성·정확히 한 번 전달은 제공하지 않는다.
- Broadcast 전송 예외는 기록하고 앱 충돌을 방지한다. 전송 성공 로그는 Gleo 수신·음성 재생 확인을 의미하지 않는다.

## 실행한 검증

| 검증 | 결과 |
| --- | --- |
| Gradle `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` | 성공. 앱 APK·테스트 APK 생성, JVM 테스트 20개 통과. |
| Pleos Connect AVD, Android 14/API 34 | `emulator-5554`, 사용자 10에 데이터 유지 업데이트 설치 성공. |
| 전체 Android 계측·Compose 회귀 | **52개 통과**, 실패·건너뜀 없음. ADB로 AndroidJUnitRunner 실행. |
| 일반 실행 | MAIN/LAUNCHER 실행과 Activity 재생성 시 불필요한 음성 Callback 없음. |
| 세 Action 최초·재수신 | 각 Action을 새 Activity와 동일 Activity `onNewIntent()`로 수신. 필수 누락 `asking`, 잘못된 인자 `fail`, 재생성 후 완료 명령 재응답 없음. |
| 인자·오류·중복 | 여섯 캠핑 인자, JSON 두 형식, 타입 Extra, 날짜·주말, 잘못된 JSON·길이·타입, 서비스 오류, 권한·미지원, 동시 요청, 타임아웃·지연·반복 Callback 검증. |
| 캠핑 실연결 | 여섯 인자를 실제 ADB Intent로 전달. 고캠핑 출처 응답과 기존 추천 화면의 `경기/가평/숲/화장실/전기/매점` 적용 및 추천 5곳 확인. `success/FOUND`. |
| 여행지 실연결 | `MARKET`, `date=2026-11-09`, `weekend=false`를 실행 중 Activity로 전달. 기존 주변 즐길거리 화면에 운천전통시장과 조회 날짜 표시. `success/FOUND`. |
| 배터리 실연결 | 기존 스마트 에너지 화면에서 에뮬레이터 SDK 잔량 **100%**, 공칭 용량 **77.4 kWh** 확인. Callback은 충전량 100, `environment=pleos_emulator`, `vehicleControlPerformed=false`, `success/ENERGY_READ`. 실차 측정값이 아니다. |
| START_STAY | 기존 시작 함수로의 연결을 코드 확인. 시작·이미 시작·주행 제한·보관 여행·다른 진행 여행·저장 실패의 Callback은 격리된 함수 입력으로 검증. 이번 실연결 확인에서 사용자의 여행 기록을 새로 시작하지 않았다. |
| 기존 기능 회귀 | 캠핑 필터·출처·예약 링크, 여행·SQLite·다이어리·주행 제한, 에너지 계획·권한·연결 실패, 장날·배경·주변 장소 테스트 통과. 스마트 모닝·자연음·음악 코드는 유지했다. 실제 스피커 재생·알람 발화·재부팅 복원은 이번에 재검증하지 않았다. |
| Python 백엔드 | 기존 고캠핑·도로공사·여행지·HTTP 회귀 18개 통과. |
| 실제 Gleo 음성 / 실차 | **미검증**. 발화 인식, Action 선택, schema 승인·탑재, Gleo의 Callback 수신 및 최종 음성 출력은 별도 확인해야 한다. |

오프라인 `connectedDebugAndroidTest`는 캐시에 없는 `com.android.tools.utp:gradle-work-action:32.4.1` 때문에 시작하지 못했다. 앱·테스트 APK 빌드 성공 후 **실제 에뮬레이터에 설치하여 ADB 계측 실행을 완료**했다. 이 실행기 문제를 해결하려고 프로젝트 의존성·전역 설정을 바꾸지 않았다.

## 재현 명령 — 프로젝트 루트의 PowerShell

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --offline --console=plain
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
$device = 'emulator-5554'
$androidUser = (& $adb -s $device shell am get-current-user).Trim()
& $adb -s $device install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s $device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $device shell am instrument --user $androidUser -w -r com.chabak.app.test/androidx.test.runner.AndroidJUnitRunner
```

새 개발 환경에서는 처음에 `--offline`을 빼고 의존성을 내려받는다. `install -r`는 앱 데이터를 유지한다. 특정 Gleo 검증만 반복하려면 다음 명령을 사용한다.

```powershell
& $adb -s $device shell am instrument --user $androidUser -w -r -e class com.chabak.app.GleoCommandSessionInstrumentedTest,com.chabak.app.GleoLiveCallbackProbe,com.chabak.app.TripPlacesInstrumentedTest com.chabak.app.test/androidx.test.runner.AndroidJUnitRunner
```

수동으로 Cold 실행 후 Warm 명령을 보낼 수 있다. 캠핑 추천에는 기존 고캠핑 서버가 필요하다. 아래 각 명령은 앞 요청의 Callback을 확인한 후 개별 실행한다. 진행 중 다른 명령을 보내면 `REQUEST_IN_PROGRESS`로 거부되는 것이 정상이다.

```powershell
& $adb -s $device shell am force-stop --user $androidUser com.chabak.app
& $adb -s $device shell am start -W --user $androidUser -a SEARCH_CAMPING_PLACE -p com.chabak.app --es region 경기 --es district 가평 --es nature 숲 --ez toilet true --ez electric true --ez store true
& $adb -s $device shell am start -W --user $androidUser -a SEARCH_TRIP_PLACES -p com.chabak.app --es category MARKET --es date 2026-11-09 --ez weekend false
& $adb -s $device shell am start -W --user $androidUser -a SEARCH_TRIP_PLACES -p com.chabak.app --es category MARKET --ez weekend true
& $adb -s $device shell am start -W --user $androidUser -a MANAGE_CAMPING_TRIP -p com.chabak.app --es operation READ_ENERGY
& $adb -s $device logcat -d -s ChabakGleo:I
```

각 명령을 새 실행으로 검사하려면 해당 명령 전에 `am force-stop`을 실행한다. 시험용 여행을 선택한 뒤 아래 명령으로 차박 시작을 검증한다. 같은 여행에 반복 실행하면 `ALREADY_STARTED`이며 새 시작 기록을 만들지 않아야 한다. 차량 조작 허용 상태를 읽을 수 없거나 제한 중이면 성공을 기대하지 않는다.

```powershell
& $adb -s $device shell am start -W --user $androidUser -a MANAGE_CAMPING_TRIP -p com.chabak.app --es operation START_STAY
```

JSON 전달 예시에서는 원격 Android 셸의 따옴표를 유지한다.

```powershell
$json = '{"region":"경기","district":"가평","nature":"숲","toilet":true,"electric":true,"store":true}'
& $adb -s $device shell "am start -W --user $androidUser -a SEARCH_CAMPING_PLACE -p com.chabak.app --es ACTION_PARAM '$json'"
```

실패 예시: `region`을 생략하면 `asking/MISSING_REGION`, `--es electric true`로 문자열을 보내면 `fail/INVALID_PARAMETERS`, `date=2026-02-30` 또는 `date`와 `weekend=true` 동시 지정도 `fail/INVALID_PARAMETERS`다. `am start`의 `Status: ok`는 Activity 전달 결과이므로 Callback 검증은 계측 Receiver 및 JSON 내용으로 확인한다.

실행 증거는 저장소에서 제외되는 `outputs/gleo/`에 저장했다: `instrumentation.txt`, `cold-warm-callbacks.json`, `energy-callback.json`, `manual-intent-callbacks.txt`, `camping-ui.png/xml`, `trip-ui.png/xml`, `energy-ui.png/xml`. 로그 시각은 에뮬레이터 시스템 시간대이며 이 보고서 기준일과 앱 날짜는 KST다. APK는 `app/build/outputs/apk/debug/app-debug.apk`다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `app/src/main/java/com/chabak/app/MainActivity.kt` | 최초·재수신을 세션으로 전달, 기존 기능 연결 유지, 지연 Callback 억제. |
| `app/src/main/java/com/chabak/app/GleoContract.kt` | 응답 모델·공통 Action·중복 비교, 타입 검증, 공식 상태·mode·전송 오류 처리. |
| `app/src/main/java/com/chabak/app/GleoCommandSession.kt` | 신규. 6초 기한, 실행 중 중복 합치기, 동시 명령 거부, 1회 완료, ViewModel 수명 관리. |
| `app/src/main/java/com/chabak/app/GleoCampingAction.kt` | 단일 Callback, 정상 0건과 서비스 오류 구분, 검색 예외 처리. |
| `app/src/main/java/com/chabak/app/GleoTripAction.kt` | 공식 상태값, 0건 결과, 시작 실패 이유, 배터리 범위·에뮬레이터·제어 미수행 표시. |
| `app/src/androidTest/java/com/chabak/app/GleoCommandSessionInstrumentedTest.kt` | 신규. 중복·동시 명령·타임아웃·인자·반복 Callback·전송 예외. |
| `app/src/androidTest/java/com/chabak/app/GleoLiveCallbackProbe.kt` | 일반 실행, 세 Action Cold/Warm, 동일 Activity 수신, 재생성·실제 Broadcast 확인. |
| `app/src/androidTest/java/com/chabak/app/TripPlacesInstrumentedTest.kt` | 공식 상태 기대값과 서비스·날짜·시작·배터리 회귀 보강. |
| `docs/CHABAK_GLEO_INTEGRATION.md` | 신규. 분석·구현·검증·재현 절차·미검증 범위. |
| `docs/CHABAK_IMPLEMENTATION_STATUS.md` | 이번에 검증한 Gleo·장소·에너지 현황 갱신. |
| `docs/CHABAK_KNOWN_LIMITATIONS.md` | 최신 APK·에뮬레이터 확인과 남은 실차·음성 한계 구분. |

`schema.json`, Manifest, Gradle 의존성, 기존 화면 레이아웃·스타일, 스마트 모닝·자연음·음악 구현과 백엔드는 변경하지 않았다.

## 확인한 공식 근거

- [Gleo Interface 개념](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)
- [App Schema 작성 규격](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)
- [Intent 및 Broadcast Callback 공식 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)
- [Callback 상태·data·mode·7초 취소 규격](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)
- [Schema 전달·Manifest·검토 절차](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/deliver-schema)
- [Vehicle SDK 2.3.2 공식 의존성](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)
