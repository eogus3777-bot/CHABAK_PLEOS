# CHABAK 2.0 최종 통합 개발·검증 보고서

2026-11-11 KST · `D:\AI\CHABAK_2_GITHUB` · `feature/chabak-2-ui`

## 결과 요약

차량 앰비언트 조명 SDK 읽기·쓰기·재조회, Virtual Console 양방향 반영, 동의 기반 차박 조명 자동화, UI 간소화를 구현했다. 기존 캠핑장 추천·여행지·준비물·다이어리·스마트 에너지·자연음·스마트 모닝을 보존했다.

**Gleo 실제 음성 연동은 BLOCKED다. 현재 PC에 마이크가 없어 발화 인식부터 Gleo 최종 음성 응답까지 검증하지 못했다. 설치된 CHABAK의 Android Intent 실행과 Callback 방송 수신은 PASS다.**

| 판정 | 확인한 범위 | 근거 |
| --- | --- | --- |
| PASS | Debug 빌드, Android 테스트 APK, JVM 20개 | `final-build.txt`, `jvm-test-summary.json` |
| PASS | Pleos Android 회귀 테스트 57개, 실패·무시 0개 | `android-regression.txt` |
| PASS | 확장 차박 UI 시나리오 1개 | `stay-automation-ui.txt`, `final-stay-scenario.json` |
| PASS | 확장 실서버 검색·백그라운드 Intent 시나리오 1개 | `gleo-live-searches.txt`, `gleo-live-searches.json` |
| PASS | 백엔드 테스트 18개, 실행 중인 서버 HTTP 점검 7건 | `backend-unit.txt`, `backend-live.json` |
| PASS | 11개 화면 변경 전후 실제 앱 캡처 | `before-screens.json`, `after-screens.json` |
| PARTIAL | 스마트 모닝 예약·취소와 시험 알람 분리 | 예약 데이터와 취소를 확인. 실제 알람 발화 시각·소리 재생은 NOT TESTED |
| PARTIAL | 차박 자동화의 조건별 검증 | 정상 배터리·P·사전 동의 경로를 실증. 저잔량 자동화 거부와 실행 도중 차량 상태 변화는 통합 시험 추가 필요 |
| BLOCKED | 네 발화의 Gleo 음성 전체 흐름 | 물리 마이크 없음. `audio-endpoints.json` |
| BLOCKED | 실차 조명·차량별 색상·시장 배포 제어 권한 | 실차와 해당 앱 승인 상태 확인 환경 없음 |
| NOT TESTED | Android Studio의 Sync 버튼, 재부팅 후 알람, 장시간 운용·청취 음질 | Gradle 구성·의존성 해석과 빌드는 CLI에서 확인 |

위 근거 파일은 `outputs/final-validation/`에 있다. PASS는 표에 적힌 시험 범위에 한정한다. 스크린샷과 검증 자료는 기존 Git ignore 설정에 따라 로컬 `outputs/` 폴더에 보관했다.

## 작업 기준점과 환경

- 시작 HEAD는 `b8ea12f79f93e3687a8119c88fec3cc4f1ec5efe`였다. 기존 staged 추가 파일 7개를 포함하는 복구용 커밋 `6675a91bd14bb3040c9e3bf39923c3a5b0887ca2`와 브랜치 `codex/chabak-final-baseline-20261111`을 만들었다. 원래 브랜치와 인덱스 상태를 유지했다.
- 이전 APK를 `outputs/final-validation/baseline-debug.apk`에 보존했다. 기존 사용자 여행·설정은 임시 여행 기반 테스트 후 비교·복원했다. `stay-restoration.json`에 결과를 기록했다.
- Gradle 9.6.0, AGP 9.4.1, Kotlin 2.2.10, Compose BOM 2026.02.01, compile/target SDK 37, min SDK 28을 사용한다. 기존 의존성을 재사용했다.
- Vehicle SDK 2.3.2, NaviHelper 3.0.5를 확인했다. Light 메서드와 권한은 설치된 AAR의 실제 시그니처도 대조했다.
- `emulator-5554`, `Pleos_Connect_v2.1.0`, Android 14/API 34, 현재 사용자 10에서 실행했다. Virtual Console은 공식 안내에 따라 호스트 8088로 전달했다.
- Gleo `ai.umos.ambientai` 4.2.0.15와 Assistant 서비스 설정·실행 PID를 확인했다. 모델 공급자 패키지는 `ai.pleos.gleoai.model.provider` 4.1.21-coral-aosp다.
- 기존 8085 고캠핑 서버를 계속 사용했다. API 키 값은 출력·저장하지 않았다.

환경 정보는 `baseline.txt`, `environment.json`, `gleo-current-package.txt`, `gleo-assistant.txt`, `gleo-model-package.txt`에 있다.

## Gleo Interface

### 규격과 실행 경로

`schema.json`의 packageName은 `com.chabak.app`이다. Action은 아래 3개를 유지한다. Manifest의 `singleTop` Activity 및 DEFAULT Intent Filter와 일치한다.

| Action | 처리와 실제 검증 |
| --- | --- |
| `SEARCH_CAMPING_PLACE` | region·district·nature·toilet·electric·store를 기존 고캠핑 추천에 전달. 경기·가평·숲·화장실·전기·매점 조건으로 설치 앱의 최초 실행, 결과 5개, success Callback 확인. 이번 실행의 콜드 시작~응답 724ms |
| `SEARCH_TRIP_PLACES` | category·date·weekend를 기존 여행지 검색에 전달. 앱을 홈으로 보낸 후 MARKET·weekend 명령으로 복귀, 2026-11-14~15 등록 장날 후보 5개와 success 확인. 이번 실행의 명령~응답 484ms |
| `MANAGE_CAMPING_TRIP` | START_STAY가 기존 여행의 차박 세션을 기록. 반복 호출은 ALREADY_STARTED이며 세션을 추가하지 않음. READ_ENERGY는 실제 에뮬레이터 Vehicle SDK SOC 100%를 반환 |

장날 Callback의 `openingConfirmed=false`를 유지한다. 실제 개장 여부는 별도 확인이 필요하다. REST_AREA의 경로 방향·진입 연결을 검증하지 못한 경우 기존 실패 응답을 유지한다.

최초 실행은 `onCreate()`, 재전달은 `onNewIntent()`에서 기존 `GleoCommandSession`으로 전달된다. Activity 재생성 시 진행 중 요청·기한을 유지하고 소비된 Intent를 재실행하지 않는다. typed extra, JSON `ACTION_PARAM`, JSON 문자열로 인코딩된 envelope를 처리한다. 누락값은 asking, 잘못된 타입·날짜·operation은 fail로 응답한다.

동일한 실행 중 요청은 합치고 다른 중첩 요청은 REQUEST_IN_PROGRESS로 거부한다. 요청마다 완료 Callback을 한 번 전송하고 6초 기한을 적용한다. 만료 후 지연된 성공 응답을 차단한다. 정상 앱 실행·Activity 재생성은 음성 Callback을 만들지 않는다. 관련 경계 조건과 상태 보존은 회귀 테스트에 포함된다.

Callback은 공식 `functionName`, `status`, 완전한 영어 문장 `message`, `mode=voice`, JSON 객체 배열을 인코딩한 문자열 `data`를 사용한다. success·asking·fail 모두 Android 수신기로 확인했다. 공식 문서의 Gleo 제한시간은 7초다. CHABAK의 6초 기한은 앱 실행 환경에서의 응답 여유를 둔다. 서버 요청이 늦게 끝나면 화면 결과는 갱신될 수 있으며, 이미 만료된 Gleo 요청에 후속 성공 Callback을 전송하지 않는다. [공식 Callback 규격](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)

차박 시작의 success는 차박 상태 저장 완료를 의미한다. `setup=CHECKS_PENDING`은 이후 배터리·추천·조명 확인이 진행됨을 나타낸다. Callback 시점의 `vehicleControlPerformed=false`와 이후 SDK 조명 적용 증거를 각각 기록했다. 후속 환경 적용 결과는 차박 화면에 표시한다.

### 실제 음성 검증 상태

| 명령 | 실제 Gleo 음성 | 설치 앱 Intent |
| --- | --- | --- |
| “글레오, 차박 시작해줘.” | BLOCKED | PASS, 임시 여행 STARTED·ALREADY_STARTED 및 사전 동의 조명 적용 |
| “글레오, 경기도에 전기 있는 캠핑장 추천해줘.” | BLOCKED | PASS, 6개 검색 인자를 포함한 실서버 추천 |
| “글레오, 이번 주말 오일장 찾아줘.” | BLOCKED | PASS, 주말 날짜와 장날 후보 표시 |
| “글레오, 차박할 때 배터리 얼마나 남았어?” | BLOCKED | PASS, SDK SOC 및 에뮬레이터 출처 표시 |

| 통신 단계 | 상태 |
| --- | --- |
| 발화 인식 | BLOCKED, 물리 마이크 없음 |
| Gleo의 Action·파라미터 선택 | NOT TESTED |
| Android Intent 전달 | PASS, 테스트 도구에서 실제 설치 Activity로 전달 |
| CHABAK 기능 실행 | PASS, 위 기능 범위 |
| Callback 방송 전송·Android 수신 | PASS |
| Gleo의 Callback 수락·사용자 안내 음성 | NOT TESTED |

스키마의 로컬 인식·앱 심사·등록 승인 상태는 확인되지 않았다. 공식 안내는 로컬 스키마 전달과 등록 절차를 설명한다. 계정 내 승인 여부를 확인한 뒤 실제 발화로 Action 매핑을 검증해야 한다. [공식 Gleo 개념](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts), [앱 스키마 가이드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability), [스키마 전달](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/deliver-schema)

추가로 Logcat 버퍼에서 Gleo 모델 공급자의 `AbstractMethodError`와 Firebase 스택을 관찰했다. exit-info에도 해당 패키지의 과거 APP CRASH 기록이 있다. `sdk-gleo-logcat.txt`, `gleo-model-exits.txt`에 보존했다. CHABAK의 크래시 기록은 해당 수집 범위에서 발견되지 않았다. 이 기록만으로 실제 음성 호출 실패 원인을 확정할 수 없다. 모델 공급자의 현재 상주 PID는 조회되지 않았으며, 주문형 실행 여부는 미검증이다.

Gleo 자체의 자유 대화 범위는 서비스 환경에서 추가 검증해야 한다. CHABAK이 제공하는 외부 앱 실행 계약은 위 3개 Action이다. 자체 AI 캐릭터·STT/TTS·LLM 대화 서버는 현재 코드에 포함하지 않는다.

## 차량 앰비언트 조명

### SDK와 권한

실제 SDK의 `checkAmbientLightBrightnessCapabilities`, `checkAmbientLightBrightnessColorCapabilities`, 밝기·색상 getter/setter를 사용했다. 각 구역의 READ/READ_WRITE, 밝기 최솟값·최댓값을 먼저 확인한다. 이번 에뮬레이터는 좌석 5개가 묶인 구역 하나, 밝기 0~100, 두 속성 READ_WRITE를 반환했다. [Vehicle 공식 문서](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)

기존 CAR_LIGHTS로 조회는 가능했으나 실제 쓰기는 ERROR_SECURITY_PERMISSION으로 거부됐다. SDK 메서드 annotation의 CAR_LIGHTS와 런타임 요구사항이 달랐다. 설치 서비스가 선언한 CONTROL_CAR_LIGHTS는 이 이미지에서 normal 권한이었다. 공식 권한 구분과 SDK 상수를 대조하고 **debug Manifest에만 CONTROL_CAR_LIGHTS를 선언**했다. APK 설치 시 부여된 권한을 확인한 후 다시 시험했다. Release 병합 Manifest에는 해당 제어 권한이 없다. Release 제어는 권한 승인 후 별도 적용·시험이 필요하다. [공식 권한 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)

`light-sdk-permissions.txt`, `light-control-permission-type.txt`, `lighting-before-permission.txt`, `lights-write-before-permission.json`, `granted-light-permissions.txt`, `package-check.json`에 대조·재시험 근거가 있다.

### 구현

- 환경 조성의 기존 반투명 패널에 현재 밝기·색상, 밝기 슬라이더와 명시적 적용 버튼, 검증된 색상 버튼을 배치했다.
- 쓰기 전 권한·구역·지원 범위와 차량 P·화면 활성 상태를 확인한다. SDK 쓰기 Callback 후 getter로 값이 일치해야 적용 확인을 표시한다.
- Mutex로 조명 작업을 직렬화하고 요청 기한·제한적 연결 재시도를 적용했다. 권한·미지원·제한·조회 지연을 화면에 표시한다.
- 작업마다 SDK 클라이언트를 `finally`에서 release한다. Compose 화면 이탈 시 조회 작업을 취소한다. 앱 백그라운드에서는 자동 설정 작업을 취소하며 실제 쓰기 직전에도 활성 상태를 확인한다.
- OEM 주행 모드 자동 색상이 true인 경우 색상 쓰기를 보류한다. 이번 이미지의 자동 모드 capability 조회는 null이므로 해당 기능의 지원·실행은 미확인이다.

SDK 색상 capability는 구역 목록을 제공하며 허용 RGB 목록을 반환하지 않는다. 따라서 이번에 검증한 `ranchu/crp_avd/release_connect_v2.1.0` 이미지에서만 네 색상 프리셋을 노출한다. 다른 차량에서는 현재 RGB 표시와 capability 기반 밝기 기능을 제공하고 색상 지원 확인 메시지를 표시한다. [Virtual Console 공식 범위](https://document.pleos.ai/docs/pleos-only/app-test-guide/virtual-console-guide)

| 시간대 | 선택한 색상 | RGB | 밝기 | SDK 재조회 |
| --- | --- | --- | --- | --- |
| 아침 | White | `#FFFFFF` | 25% | PASS |
| 낮 | Cyan | `#00E5FF` | 65% | PASS |
| 노을 | Purple | `#AA00FF` | 30% | PASS |
| 밤 | Blue | `#2979FF` | 12% | PASS |

지원 팔레트 안에서 아침의 부드러움, 낮의 밝기, 저녁의 보라, 밤의 낮은 밝기를 선택했다. 추천값은 코드의 `TimeScene.ambientPreset()`에서 관리한다.

### 실제 쓰기·양방향 검증

| 항목 | 결과 | 증거 |
| --- | --- | --- |
| SDK 읽기 | PASS | `lights-read.json` |
| SDK setter Callback | PASS, 밝기 33·Cyan 요청 | `lights-write.json`, `lighting-instrumentation.txt` |
| setter 후 getter 일치 | PASS, 두 속성 verifiedChanges=2 | `lights-write.json`, `sdk-gleo-logcat.txt` |
| 앱 색상 버튼 → Console | PASS, 파랑 `#2979FF` | `lights-app-blue.png`, `console-after-app-blue.jpg` |
| 앱 슬라이더·적용 버튼 → Console | PASS, 밝기 50%·Console actual 값 관찰 | `lights-ui-brightness.json`, `lights-ui-brightness.png` |
| Console → SDK → 앱 | PASS, 42%·보라 | `lights-bidirectional.json`, `lights-app-from-console.png`, `console-input-purple42.jpg` |
| 실제 N·속도 0 상태의 조작 제한 | PASS, 환경 제어 UI 제한 | `lights-not-parked.json`, `lights-not-parked.png` |
| 실차·OEM 자동 조명 모드 | BLOCKED / NOT TESTED | 실차 없음, 자동 모드 capability 미확인 |

시험 후 최초 SDK 조회 값인 밝기 70·White, 배터리 100·P로 복원했다. Console 대시보드의 사용자 색상 미리보기와 Emulation Controls의 실제 RGB는 별도로 확인했다. 검증값은 SDK getter와 Emulation Controls에 근거한다. Console 카드 헤더가 열린 동안 이전 값을 유지한 사례가 있어 닫고 다시 열어 실제 수신 값을 확인했다. Console의 최종 관찰값은 `console-restored.json`에 기록했다. 앱의 최종 조명 값은 `after/08-environment.png`에서도 확인할 수 있다.

## 차박 자동화

UI 및 기존 Gleo START_STAY에서 동일한 차박 시작 함수를 사용한다. 차박 기록을 먼저 저장하고, 현재 시간·기존 날씨 데이터·사용자 설정·SDK 배터리로 후속 환경을 확인한다. 기존 추천 규칙을 재사용한다.

- 조명 자동 적용의 기본값은 꺼짐이다. 환경 조성에서 사용자가 사전 동의하면 차박 시작 시 적용한다.
- 차량 UI 허용, 실제 P, 앱 RESUMED, 현재 차박·여행 일치, SDK 지원·쓰기 권한, 10초 이내 SOC, 사용자 최소 보호 잔량 초과를 모두 확인한다.
- 이미 시작한 여행의 반복 START_STAY는 기존 차박을 반환한다. 새 세션·반복 환경 시작을 생성하지 않는다.
- 동의한 현재 차박에서 시간대·수동 배경 테마가 바뀌면 네 프리셋을 조건 확인 후 적용한다. Activity 재생성 후에는 차박 기록을 복원하며 조명 재적용 버튼으로 후속 확인할 수 있다.
- 자연음·장르와 스마트 모닝은 기존 화면에 연결한다. 추천을 표시하고 재생·알람은 사용자가 실행한다.
- 배터리·권한·지원·차량 상태가 충족되지 않으면 조명 결과에 보류 이유를 표시한다. 차박 상태와 다른 기능을 유지한다.

설치 앱의 Gleo START_STAY Intent와 UI 차박 시작 모두 사전 동의 상태에서 실제 SDK 조명이 바뀌었다. 네 시간대 전환·자연음·모닝 예약·중복 차박 방지·Activity 재생성 후 기록 유지와 원래 상태 복원을 확인했다. `final-stay-scenario.json`과 `outputs/final-screenshots/live-tests/`가 근거다. 저SOC 자동 적용 거부, 오래된 SOC, 쓰기 직전 기어 변화의 통합 시험은 NOT TESTED다.

## UI 점검

기존 홈 구조, 자연 배경 4종, 상단 시간·날씨, 하단 반투명 패널, 출발 전·차박 중 메뉴를 유지했다. 제목 아래 반복 설명·개발용 하단 문구·중복 소제목을 줄이고 홈 기능명을 34sp로 키웠다. 차박 중 상태와 자동화 결과는 어두운 반투명 영역에 묶고 주요 상태 글자를 24sp로 키워 밝은 배경에서도 대비를 확보했다. 배터리 단위·예상값·미지원 항목·권한·안전 경고·캠핑장 예약·출처 정보는 보존했다.

| 화면 | 변경 전 / 후 파일명 | 점검 내용 |
| --- | --- | --- |
| 홈 | `01-home.png` | 카드 부연 문구 정리, 제목 확대 |
| 차박 준비 | `02-preparation.png` | 상세·예약·여행 진입 유지 |
| 캠핑장 추천 | `03-recommendations.png` | 중복 안내 정리, 조건·결과 유지 |
| 준비물 | `04-packing.png` | 기존 체크 항목과 여행 범위 유지 |
| 차박 중 | `05-stay.png` | 중복 설명 정리, 환경 적용 결과 연결 |
| 플레이리스트 | `06-playlist.png` | 자연음 조작·장르 유지, 부가 문구 정리 |
| 스마트 에너지 | `07-energy.png` | 중복 미지원 설명 축약, 수치·보호 경고 유지 |
| 환경 조성 | `08-environment.png` | 실제 SDK 조명 패널, 테마·자연음 유지 |
| 스마트 모닝 | `09-morning.png` | 중복 문구 정리, 예약·권한·조건 유지 |
| 다이어리 | `10-diary.png` | 기존 기록·여행 연결 유지 |
| 여행지 | `11-nearby.png` | 날짜·유형·실패 원인 유지 |

모든 파일은 `outputs/final-screenshots/before/`, `after/`에서 같은 이름으로 비교할 수 있다. `live-tests/`에는 실제 차박 중·모닝 예약·네 배경 및 조명·검색 결과 화면을 추가했다. 기본 전후 캡처의 모닝 화면은 기존 여행이 차박 중이 아닌 상태이며, 활성 모닝 검증 화면은 별도로 보관했다.

## 서버와 기존 기능

8085 서버의 `/health`, `/recommend`, `/camps`, 지역·시설·자연·시군구 조건, 빈 결과, 잘못된 limit, 후속 정상 요청을 확인했다. HTTP 점검은 7/7 PASS였고 이번 캐시된 응답은 약 4~40ms였다. 이 수치는 최초 전체 수집 시간·외부 API 지연을 보장하지 않는다.

실서버 검색에서 최대 5개 결과와 조건 필드를 확인했다. 잘못된 키·공급자 거절·네트워크 타임아웃·잘못된 응답·동시 캐시 요청은 격리된 HTTP/단위 테스트에서 확인했다. 운영 중인 서버의 실제 키를 바꿔 시험한 경우는 NOT TESTED다. 서버 코드를 수정하지 않았다.

배터리는 SDK 100%→Console 24%→앱 24%·30% 보호 경고·차박용 가용량 0%p를 확인한 뒤 100%로 복원했다. 공칭 용량·실측 SOC·추정 계산을 구분하며 측정 소비전력 부재 시 예상 시간을 계산 불가로 표시한다. 권한 거절·서비스 bind 실패는 격리 Context 기반 회귀 시험에서 확인했다.

자연음 재생·일시정지·정지 및 Android `AudioManager.isMusicActive`를 확인했다. 기존 오디오 포커스·화면 이탈 중단·모닝 재생 서비스를 보존했다. 실제 청취 음질과 Gleo 발화 중 오디오 충돌은 NOT TESTED다. 모닝 시험 예약이 일반 예약을 보존하는지와 두 예약 취소를 확인했으며, 실제 알람 울림·재부팅 복원은 추가 시험 대상이다.

일반 MAIN/LAUNCHER 진입, 프로세스 force-stop 후 재실행과 기존 선택 여행 복원을 확인했다. 해당 새 프로세스에서 FATAL EXCEPTION은 발견되지 않았다. `normal-relaunch.json`, `relaunch-runtime.txt`가 근거다. 차박 중인 상태의 프로세스 재시작은 NOT TESTED이며, Activity 재생성의 차박 기록 유지는 확장 UI 시험에서 확인했다.

## 변경 파일

| 파일 | 변경 |
| --- | --- |
| `app/src/main/java/com/chabak/app/PleosAmbientLight.kt` | 신규 SDK capability·읽기·쓰기·재조회·정리 |
| `app/src/main/java/com/chabak/app/AmbientLightPanel.kt` | 신규 기존 패널용 조명 UI·동의 |
| `app/src/main/java/com/chabak/app/AmbientLightCapability.kt` | 이전 조회 전용 구현을 새 컨트롤러로 통합 |
| `app/src/main/java/com/chabak/app/MainActivity.kt` | 기존 차박 시작 연결·조건부 자동화·UI 간소화 |
| `app/src/main/java/com/chabak/app/TravelUxGate.kt` | Android CarPropertyManager 기반 실제 P 조회 |
| `app/src/main/java/com/chabak/app/PleosEnergyReader.kt` | 유효 SOC 조회 시각 기록 |
| `app/src/main/java/com/chabak/app/GleoTripAction.kt` | 차박 시작 후 CHECKS_PENDING 결과 의미 명확화 |
| `app/src/main/java/com/chabak/app/ChabakDesign.kt` | Gleo 안내의 중복 문구 정리·미검증 상태 표시 |
| `app/src/main/java/com/chabak/app/SmartEnergyScreen.kt` | 중복 안내 축약 |
| `app/src/main/AndroidManifest.xml` | 실제 P 조회용 normal CAR_POWERTRAIN |
| `app/src/debug/AndroidManifest.xml` | debug 전용 CONTROL_CAR_LIGHTS |
| `app/src/androidTest/java/com/chabak/app/AmbientLightInstrumentedTest.kt` | 신규 실제 SDK·재조회·복원·거절 경로 시험 |
| `app/src/androidTest/java/com/chabak/app/FinalStayScenario.kt` | 신규 선택형 실제 UI 자동화 시나리오 |
| `app/src/androidTest/java/com/chabak/app/GleoInstalledStayInstrumentedTest.kt` | 기존 격리 여행 시험 확장·설정 복원 |
| `app/src/androidTest/java/com/chabak/app/GleoLiveCallbackProbe.kt` | 선택형 실서버 콜드·백그라운드 검색 검증 |
| `app/src/androidTest/java/com/chabak/app/ChabakDesignInstrumentedTest.kt` | 변경된 실제 Gleo 상태 문구 검증 |
| `docs/CHABAK_FINAL_REPORT.md`, `docs/CHABAK_DEMO_SCENARIO.md` | 최종 보고서·시연 절차 |
| `outputs/final-validation/`, `outputs/final-screenshots/` | 로컬 검증 도구·결과·전후 캡처 |

기존 staged Gleo 세션·보고서 파일은 시작 기준점에 포함된다. `schema.json`, 빌드 의존성, 고캠핑·여행지 서버는 이번 작업에서 변경하지 않았다.

## 빌드 산출물과 재현

표준 Debug APK는 `D:\AI\CHABAK_2_GITHUB\app\build\outputs\apk\debug\app-debug.apk`다. 크기 51,059,872 bytes, SHA-256은 아래와 같다.

```text
716fb10ae97801d19528804a7e115d49ffa8d4629ede60f101252f8d31b75af2
```

프로젝트 루트 PowerShell에서 실행한다. AVD 사용자 번호는 실제 환경에서 다시 확인한다. `finalScenario`는 기존 차박·알람이 없는 경우에만 임시 여행을 만들고 원상 복원한다. `liveLights=true`는 에뮬레이터에서 실제 조명을 잠시 바꾼 뒤 복원한다.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:processReleaseMainManifest --offline --console=plain
$adbPath = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
& $adbPath install -r app/build/outputs/apk/debug/app-debug.apk
& $adbPath install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adbPath shell am instrument --user 10 -w -r -e liveLights true com.chabak.app.test/androidx.test.runner.AndroidJUnitRunner
& $adbPath shell am instrument --user 10 -w -r -e class com.chabak.app.GleoInstalledStayInstrumentedTest -e finalScenario true com.chabak.app.test/androidx.test.runner.AndroidJUnitRunner
& $adbPath shell am instrument --user 10 -w -r -e class 'com.chabak.app.GleoLiveCallbackProbe#installedActivitySendsCallbacksToAnAndroidReceiver' -e liveSearch true com.chabak.app.test/androidx.test.runner.AndroidJUnitRunner
py -3 -m unittest discover -s backend -v
```

첫 개발 과정의 쓰기 권한 실패와 확장 테스트 정리 단계의 ActivityScenario 대기 실패는 수정 후 재시험했다. 후자는 Intent 변경 뒤 Activity 추적이 끊긴 테스트 코드 문제였으며, 실제 Activity 종료·설정 복원 경로로 고쳤다. `gleo-live-searches-first-run.txt`에 최초 실패를 남겼고 최종 로그는 PASS다.

## 남은 조치

1. PC 마이크 연결과 Windows 입력 장치·에뮬레이터 마이크 권한·Host audio 입력을 확인한다. Gleo의 직접 누르기 또는 공식 호출 방식으로 인식을 확인한다.
2. 개발자 계정에서 현재 packageName·schema 버전·세 Action 등록 및 승인 여부를 확인한다. 실제 Gleo가 선택한 Action·파라미터와 앱 로그를 한 발화씩 대조한다.
3. 모델 공급자 AbstractMethodError 기록을 공식 지원에 전달하고 현재 AVD와 Gleo 패키지 조합의 호환성을 확인한다. 계정·네트워크·엔진의 원인 구분은 해당 환경에서 추가 진단한다.
4. 네 실제 발화에 대해 인식→매핑→Intent→기능→Callback 수락→Gleo 음성 안내까지 확인한 뒤 음성 시연 장면을 추가한다.
5. 실차의 Light capability·지원 색상·자동 모드·CAR_LIGHTS/CONTROL_CAR_LIGHTS 승인 범위를 확인한다. 승인 후 Release 권한 적용과 안전한 정차 시험을 진행한다.
6. 저SOC·오래된 SOC·중간 기어 변화, 실제 모닝 알람 울림, 앱 프로세스 재시작·기기 재부팅, 장시간 오디오·SDK 연결을 추가 검증한다.

현재 제출 자료에서는 에뮬레이터 SDK 기능과 Intent 기반 시연을 성공 범위로 제시하고, 실제 Gleo 음성 및 실차 제어는 위 검증 후 갱신한다.
