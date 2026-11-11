# CHABAK Gleo 단일 음성 AI 구성·검증 보고

기준일: 2026-11-09 KST. 사용자의 방향 수정에 따라 자체 AI 대화 기능을 제거하고 **Gleo → CHABAK Action → 기존 기능 → Gleo Callback** 구조만 유지했다. **실제 Gleo 발화 인식·Action 생성·응답 음성까지의 연동은 미검증이며, 아래 실행 결과는 설치된 앱의 Intent 테스트다.**

## 의존성 분석과 선별 제거

제거 전 참조를 조사했다. 자체 대화 기능은 MainActivity의 추가 진입점과 전용 파일에서 기존 기능을 호출할 뿐, 기존 고캠핑·날씨·차량 리더·음악·스마트 모닝 구현이 자체 대화 코드에 의존하지 않았다.

| 범위 | 처리 |
| --- | --- |
| 캐릭터 버튼·별도 대화 창 | 홈·차박 중 화면에 추가했던 진입 버튼과 Dialog 연결 제거. 기존 메뉴·카드·배경·정적인 Gleo 안내 화면 유지. 원래 있던 공용 캐릭터 이미지 에셋은 삭제하지 않음. |
| 자체 대화 세션·서버 클라이언트·도구·오디오 포커스 | 전용 파일 5개와 MainActivity 연결 제거. 기존 자연음·음악·스마트 모닝의 오디오 포커스는 보존. |
| 자체 STT/TTS | 공식 SDK 두 의존성, 전이 의존성용 security Maven 그룹, STT/TTS 서비스 권한 제거. |
| 자체 LLM 서버 | `backend/ai_conversation.py`, 해당 테스트 제거. 기존 고캠핑·도로공사·여행지 백엔드는 보존. |
| 대화용 빌드 설정 | `AI_BASE_URL`, Release AI URL 설정과 이 기능 때문에 켠 BuildConfig 생성 제거. 공급자 키 설정을 요구하는 코드 없음. |
| 자체 음성 진단·테스트 | 전용 Android 테스트 3개 제거. 과거 진단 자료는 Git에서 제외된 이전 QA 폴더에만 남으며 현재 기능의 성공 근거로 사용하지 않음. |
| Gleo 요청 세션 | `GleoCommandSession` 유지. 자유 대화 이력이 아니라 Intent 중복·기한·1회 Callback을 관리하는 코드임. |

제거한 전용 코드 파일:

- `app/src/main/java/com/chabak/app/AiConversationClient.kt`
- `app/src/main/java/com/chabak/app/AiConversationController.kt`
- `app/src/main/java/com/chabak/app/AiConversationUi.kt`
- `app/src/main/java/com/chabak/app/AiReadOnlyTools.kt`
- `app/src/main/java/com/chabak/app/PleosConversationSpeech.kt`
- `app/src/androidTest/java/com/chabak/app/AiConversationInstrumentedTest.kt`
- `app/src/androidTest/java/com/chabak/app/AiConversationUiInstrumentedTest.kt`
- `app/src/androidTest/java/com/chabak/app/PleosConversationSdkProbe.kt`
- `backend/ai_conversation.py`
- `backend/test_ai_conversation.py`

## 유지한 공식 Gleo 계약

`assets/schema.json`의 기존 Action 3개와 인자, MainActivity의 exported·singleTop 및 Gleo Intent Filter를 유지했다. 최초 실행과 `onNewIntent()`를 모두 처리한다. 타입 Extra와 `ACTION_PARAM` JSON/JSON 문자열 포장을 지원한다.

| Action | 기존 기능 연결 | 현재 구분해야 할 제한 |
| --- | --- | --- |
| `SEARCH_CAMPING_PLACE` | `region/district/nature/toilet/electric/store` → 기존 고캠핑 추천 API → 기존 추천 화면 | `electric`은 캠핑용 전기. 차량 충전소·실제 숙박 허가·예약 완료를 의미하지 않음. 개발 프록시 8085 필요. |
| `SEARCH_TRIP_PLACES` | `category/date/weekend` → 기존 주변 즐길거리 검색·화면 | `REST_AREA/BREWERY/MARKET` 지원. 휴게소는 방향별 진입·진출 자료가 없으면 실패를 반환. 저장된 공식 자료를 실시간 영업 확인으로 표현하지 않음. |
| `MANAGE_CAMPING_TRIP` / `START_STAY` | 기존 화면 버튼과 같은 `startSelectedStay()` → 여행 기록·앱 차박 세션 | 정차 조작 허용·진행 여행·보관 상태·저장 결과 검사. **차량의 캠핑 모드·조명·공조·전력을 제어하지 않음.** |
| `MANAGE_CAMPING_TRIP` / `READ_ENERGY` | 기존 Vehicle SDK 리더와 스마트 에너지 화면 | 실제 지원·권한·연결 상태 확인. 에뮬레이터 값은 해당 출처를 명시하며 실차 측정으로 처리하지 않음. |

Callback은 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` Broadcast의 `packageName/response` Extra를 사용한다. `response`는 `functionName/status/message/mode/data`이고 `data`는 JSON 객체 배열을 인코딩한 문자열이다. `success/asking/fail`과 `mode=voice`를 유지하여 최종 음성 응답은 Gleo가 담당한다. CHABAK은 별도 TTS를 실행하지 않는다.

필수 인자 누락·잘못된 타입·날짜·열거값·서비스 실패를 구분한다. 정상 검색 0건은 `success/NO_RESULTS`, 처리 실패는 `fail`이다. 앱 접수 후 6초 기한과 실행 직전 만료 확인, 실행 중 같은 인자의 중복 합치기, 다른 진행 요청의 명확한 거부, 지연·반복 Callback 차단을 유지했다. 이는 Gleo의 공식 7초 취소 규정에 여유를 두는 앱 측 처리이며 프로세스 시작 전 지연까지 보장하지 않는다.

## Gleo의 대화 능력과 외부 앱 제어 범위

Pleos 공식 소개는 Gleo가 LLM과 음성 기술로 대화 맥락을 이해하는 AI라고 설명한다. 그 제품 기능과 외부 앱의 실행 인터페이스는 구분해야 한다. Gleo Interface는 앱 스키마에 정의한 기능을 Action으로 실행하고 Callback을 받는 계약이다. Gleo가 자유롭게 대화한다고 해서 CHABAK의 모든 화면·기능을 자동으로 제어하거나 앱에 자유 대화 전체를 전달하는 것은 아니다.

현재 CHABAK에서 노출한 범위는 위 세 Action의 네 작업이다. 음악 재생, 스마트 모닝 알람 설정, 차량 조명·공조·충전 변경, 날씨 대화, 임의 화면 조작은 이 스키마에 노출하지 않았다. **앱에 미노출된 기능과 Pleos 플랫폼 전체의 미지원은 같은 뜻이 아니다.** 기존 수동 기능은 유지한다.

현재 에뮬레이터에서 `ai.umos.ambientai/.service.AiAssistantService`가 Assistant/Voice Interaction 서비스로 등록된 것을 확인했다. 표준 Android `ACTION_ASSIST`, Assistant 키와 Voice Assist 키로 진입을 시도했으나, 사용자의 실제 발화가 CHABAK Action으로 전달되고 Gleo가 답하는 흐름을 확인하지 못했다. 내부 오류 로그만으로 CHABAK 스키마나 인증이 원인이라고 단정하지 않았다. 승인·등록 상태를 우회하거나 확인되지 않은 Gleo 입력 API에 텍스트를 주입하지 않았다.

실제 발화 검증에는 정상 작동하는 Gleo 서비스·계정/환경, 마이크, CHABAK 스키마 검토·등록 상태가 확인된 Pleos 환경이 필요하다. CHABAK에 별도 LLM 키나 STT/TTS SDK를 다시 넣는 방식으로 해결하지 않는다.

## 실행·검증 결과

| 검사 | 결과와 증거 범위 |
| --- | --- |
| Gradle 앱·테스트 APK / JVM | `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` 성공. JVM 20개 통과. |
| 전체 Android 회귀 | 53개 통과. 기존 캠핑·여행·에너지·다이어리·주행 제한·Gleo 인자/기한/중복/Callback 테스트와 실제 설치 앱의 차박 시작 테스트 포함. |
| 기존 백엔드 | 18개 통과. 자체 대화 서버의 모의 엔진 테스트는 제거했으며 성공 수에 포함하지 않음. |
| APK 구성 | 자체 AI 클래스, STT/TTS SDK 클래스, OpenAI 주소·8087 접속 주소, 자체 AI용 native library가 없는 것을 확인. Gleo 요청 세션과 기존 스키마 3개는 APK에 존재. |
| 일반 UI | 자체 캐릭터 진입·대화 창 제거, 기존 홈 메뉴·카드와 Gleo 안내 유지 확인. |
| 캠핑 추천 Intent | `경기/가평/숲/toilet=true/electric=true/store=true`를 기존 추천 화면으로 연결. 고캠핑 출처의 추천 5곳과 필터 적용, `success/FOUND` Callback 확인. |
| 여행지 Intent | `MARKET/date=2026-11-09/weekend=false`를 기존 검색 화면으로 전달. 운천전통시장·조회 날짜 표시와 `success/FOUND` Callback 확인. |
| 차박 시작 Intent | 임시 여행에서 실제 MainActivity 수신 후 `STARTED`, 기록 1개 생성. 반복 호출은 `ALREADY_STARTED`, 기록 추가 없음. 기존 여행 선택·원본 기록·테스트 전 모닝 상태로 복원 확인. `spokenVoiceTest=false`. |
| 에너지 조회 Intent | 실제 에뮬레이터 Vehicle SDK에서 잔량 100%, 공칭 용량 77.4 kWh 표시. Callback 충전량 100과 `pleos_emulator` 출처, `success/ENERGY_READ` 확인. 실차 조회 아님. |
| 실제 Gleo 발화·음성 Callback | **미검증**. Intent 테스트를 음성 연동 완료로 보고하지 않음. |
| 음악·스마트 모닝 | 기존 코드·서비스·권한·음원·오디오 포커스 보존, 관련 기존 회귀 통과. 실제 스피커 재생·알람 발화·재부팅 복원은 이번에 다시 검증하지 않음. |

차박 시작 테스트의 첫 실행에서는 기능 실행·중복 방지가 성공한 뒤 `ActivityScenario.close()`가 AAOS의 PAUSED 상태에서 실패했다. 기존 원본 두 여행은 수정하지 않았고, 복사값으로 부모 여행을 유일하게 확인하여 임시 기록만 복구했다. 이후 테스트를 ActivityScenario 종료 대기에 의존하지 않도록 수정하고, 원본 선택·설정 복구가 종료 오류보다 먼저 보장되게 했다. 재실행에서 원본 상태 복원과 전체 회귀를 확인했다. 테스트 직전 복구 정보를 앱의 비공개 QA 파일에 기록하며 기존 진행 중 차박이나 예약 알람이 있으면 이 테스트는 해당 상태를 변경하지 않는다.

## 재현

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --offline --console=plain
$python = "$env:LOCALAPPDATA/Python/pythoncore-3.14-64/python.exe"
& $python -B -m unittest discover -s backend -v
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
$device = 'emulator-5554'
$androidUser = (& $adb -s $device shell am get-current-user).Trim()
& $adb -s $device install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s $device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $device shell am instrument --user $androidUser -w -r com.chabak.app.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s $device shell am start -W --user $androidUser -n com.chabak.app/.MainActivity
```

아래는 **음성 호출을 대신하는 것이 아닌 Intent 재현 명령**이다. 각 요청의 Callback 후 다음 명령을 실행한다. 고캠핑은 기존 8085 서버가 필요하다.

```powershell
& $adb -s $device shell am start -W --user $androidUser -a SEARCH_CAMPING_PLACE -p com.chabak.app --es region 경기 --es district 가평 --es nature 숲 --ez toilet true --ez electric true --ez store true
& $adb -s $device shell am start -W --user $androidUser -a SEARCH_TRIP_PLACES -p com.chabak.app --es category MARKET --es date 2026-11-09 --ez weekend false
& $adb -s $device shell am start -W --user $androidUser -a MANAGE_CAMPING_TRIP -p com.chabak.app --es operation READ_ENERGY
& $adb -s $device logcat -d -s ChabakGleo:I
```

차박 시작을 원래 여행의 기록에 남기지 않고 검사하려면 임시 여행·원복을 포함한 아래 테스트를 사용한다. 진행 중 차박·예약 알람이 있으면 이를 보존하고 테스트를 건너뛴다.

```powershell
& $adb -s $device shell am instrument --user $androidUser -w -r -e class com.chabak.app.GleoInstalledStayInstrumentedTest com.chabak.app.test/androidx.test.runner.AndroidJUnitRunner
```

검증 자료는 `outputs/gleo-only/`에 저장한다. APK는 `app/build/outputs/apk/debug/app-debug.apk`다. `start-stay.json`은 실제 Android Receiver가 받은 응답이며 `originalStateRestored=true`를 포함한다. 과거 자체 AI·Speech 진단은 현재 구성의 성공 증거로 사용하지 않는다.

## 이번 변경 파일

전용 파일 10개를 삭제하고 `MainActivity.kt`, `app/build.gradle.kts`, `settings.gradle.kts`, `AndroidManifest.xml`에서 자체 AI 연결·설정만 제거했다. Gleo 운영 코드는 그대로 유지했다. `GleoInstalledStayInstrumentedTest.kt`를 추가했으며 구현 현황·제한사항·철회된 자체 AI 문서와 이 보고서를 갱신했다. `schema.json`과 기존 캠핑·날씨·차량·음악·스마트 모닝 구현은 변경하지 않았다.

## 공식 근거

- [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)
- [Action 3개 제한과 앱 스키마](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)
- [Intent·Callback 공식 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)
- [Callback 상태·data·mode·7초 취소](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)
- [스키마 전달·등록·검토](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/deliver-schema)
- [Gleo의 문맥 대화 제품 설명](https://document.pleos.ai/en/docs/connect/guide/connect)
