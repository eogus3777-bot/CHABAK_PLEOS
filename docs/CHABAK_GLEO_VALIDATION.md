# CHABAK 3차 — 실제 Gleo 음성 호출 검증

검증일: 2026-11-09 KST. 대상: `D:\AI\CHABAK_2_GITHUB`, `emulator-5554`, Android 사용자 10. 추가 직접 오디오 시험 및 복구 확인: 13:21–13:40 KST.

## 결론

**현재 Pleos 에뮬레이터에서 사용자가 Gleo에게 말하면 CHABAK이 실제로 실행되는지는 미검증이다. 이번 실행에서는 실제 발화 → CHABAK 기능 → Gleo 음성 응답의 성공을 확인하지 못했다. 연동 완료로 판정하지 않는다.**

Gleo 설치·프로세스·Android 녹음 트랙과 **CHABAK 스키마 로딩 및 Action 3개 변환**은 관측했다. 사용자의 최종 확인은 **PC에 마이크가 없음**이다. 앞서 잘못 선택한 “발화했지만 반응 없음”은 실제 발화 결과로 사용하지 않는다. 물리 마이크 발화 시험은 미수행이다.

사용자의 직접 시험 요청에 따라 Windows 한국어 합성 음성을 만들고, 설치된 Android Emulator의 공식 `injectAudio`로 입력을 시도했다. 버퍼 대기 방식은 시간 초과·에뮬레이터 무응답, 실시간 방식의 1초 무음은 연결 종료·에뮬레이터 종료로 끝났다. 호스트 로그에 **DirectSoundCapture 초기화 및 virtio 마이크 생성 실패**가 반복되어, Gleo에 유효한 음성이 전달됐다고 검증할 수 없었다. 앱 데이터를 초기화하지 않고 AVD를 복구했으며 원래 마이크 ON 설정·CHABAK 일반 실행·APK 해시 일치를 확인했다. **오디오 입력 경로의 실패를 Gleo 인식 엔진 실패로 판정하지 않는다.**

| 시험 | 최종 판정 | 실제 발화 수행 | 판정 근거 |
| --- | --- | --- | --- |
| A — 차박 시작 | **BLOCKED** | 미수행 | 물리 마이크 없음, 대체 입력 경로도 실패. `START_STAY` 음성 수신·실행 미관측. |
| B — 캠핑장 검색 | **BLOCKED** | 물리 발화 미수행; 합성 음성 입력 시도 | 공식 에뮬레이터 오디오 주입이 실패하여 Gleo 인식·검색 Action 시험까지 도달했는지 확인 불가. |
| C — 주말 오일장 | **BLOCKED** | 미수행 | 공통 오디오 입력 경로 미확보. Action·주말 인자 미관측. |
| D — 배터리 조회 | **BLOCKED** | 미수행 | 공통 오디오 입력 경로 미확보. 음성에 의한 SDK 조회·응답 미관측. |

실제 음성 시험 합계: **PASS 0 / FAIL 0 / BLOCKED 4 / NOT TESTED 0**. BLOCKED는 시험 전체의 판정이다. 그 뒤의 미수행 통신 단계는 아래 표에서 NOT TESTED로 구분한다. Assistant 버튼·Activity 실행의 관측 결과를 A–D의 음성 시험 결과로 바꾸어 집계하지 않았다.

## 환경과 증거

관측 자료는 [outputs/gleo-validation](../outputs/gleo-validation/)에 저장했다. `outputs/`는 기존 `.gitignore` 대상이므로 로컬 자료이며 Git에 자동 포함되지 않는다. 원본 전체 logcat 대신 시간대·프로세스·기능을 필터링하고 인증 관련 행과 긴 토큰·기기 식별자를 가린 자료를 저장했다. 사람의 마이크 원음은 저장하지 않았다. 추가 시험의 WAV는 Windows가 생성한 정해진 테스트 문장·무음이다.

| 항목 | 관측 결과 | 증거 파일 |
| --- | --- | --- |
| 실행 AVD | `Pleos_Connect_v2.1.0`, Android 14 / API 34, x86_64, 빌드 `RELEASE.release_connect_v2.1.0 test-keys` | `environment.json` |
| 부팅 설정 | `fastboot.forceColdBoot=yes`, `hw.audioInput=yes`. 현재 부팅 방식 자체는 별도 추적하지 않음 | `avd-config-selected.txt` |
| Gleo | 시스템 앱 `ai.umos.ambientai`, 버전 `4.2.0.15` / code 169. 초기 PID 2708, 재시작 후 32130 | `package-ai.umos.ambientai.txt`, `gleo-restart-summary.json` |
| Assistant 지정 | Assistant와 VoiceInteractionService 모두 `ai.umos.ambientai/.service.AiAssistantService`, 지원 사용자 10 | `environment.json`, `voiceinteraction-before.txt` |
| VoiceInteraction 관측 | `mBound=true mService=null`, `Hotword detection service=null`. 반면 ActivityManager에는 Assistant 서비스가 실행·바인딩된 것으로 표시됨. 이것만으로 Gleo 고유 호출어 엔진 고장으로 판정하지 않음 | `gleo-restart-voiceinteraction.txt`, `gleo-restart-services.txt` |
| Gleo 내장 구성 표시 | GAA SDK `3.7.3.1`, AMI SDK `3.11.3`, GAAS `4.2.10-coral-aosp`, STT/TTS `3.3.1`, Speech SDK `1.17.1`, Model Provider `4.1.21-coral-aosp` | `gleo-settings.xml`, `gleo-settings.png` |
| 실제 Gleo 음성 모드 | **STT SERVER / TTS SERVER**. Gleo 초기화 로그와 CAAS Binder의 `setMode SERVER, ai.umos.ambientai`가 일치 | `key-findings.log`, `gleo-restart-caas-filtered.log` |
| 에뮬레이터 마이크 | Extended Controls의 **Enable Host Microphone Access ON**을 직접 관측. 변경하지 않음 | `microphone-1.png`, `microphone-ui.txt` |
| 호출어 | Gleo 설정의 `'Gleo' 라고 불러서 깨우기` ON. 설정 화면에는 `Unknown [4ch]` 표시. 이 표시의 오류 의미는 공식 자료로 확인하지 못함 | `gleo-settings.xml`, `gleo-settings.png` |
| Android 녹음 | Gleo RECORD_AUDIO 허용. AudioFlinger에 PID 2708의 활성 녹음 트랙, 내장 마이크, 16 kHz 표시. 재기동 로그에도 `startRecording ... true`. **유효한 사람 음성 수신·인식을 입증하는 자료는 아님** | `environment.json`, `audio-flinger-selected.txt`, `key-findings.log` |
| PC 오디오 | 컨트롤러 상태 OK만으로 입력 장치 존재를 확인할 수 없었음. 사용자 확인: 물리 마이크 없음. 실제 호스트에서 DirectSoundCapture·virtio 마이크 생성 실패 반복 | `host-audio-devices.json`, `user-input-clarification.json`, `audio-host-errors.txt` |
| 음성 출력 | Android MUSIC/ASSISTANT 스트림의 mute=false 관측. Gleo 합성 음성 생성·스피커 출력은 미검증 | `audio-summary-before.txt` |
| 일반 네트워크 | Android 기본 네트워크 101, Wi-Fi INTERNET/VALIDATED. Gleo 음성·LLM 서버의 인증과 요청 성공을 뜻하지 않음 | `network-summary.txt` |
| 개발 서버 | 기존 8085 포트 LISTEN. 이번에 캠핑장 검색 HTTP 요청은 새로 수행하지 않음 | `environment.json` |
| 설치 APK | 설치 파일과 로컬 debug APK SHA-256 일치. APK 내부 스키마와 소스 스키마 해시 일치 | `installed-apk-sha256.txt`, `apk-schema-verification.json` |
| CHABAK 수신 경로 | Action 3개가 현재 사용자에서 `com.chabak.app/.MainActivity`로 해석됨. 명령은 보내지 않은 조회 검사 | `intent-resolution.txt` |
| 차량 조회 권한 | 설치 앱의 `pleos.car.permission.CAR_ENERGY/CAR_INFO` granted=true. **에뮬레이터 런타임 권한 확인이며 App Market·프로젝트 승인 증거가 아님** | `package-com.chabak.app.txt` |
| 개발자·스키마 승인 | 인증된 Playground 프로젝트/심사 화면에 대한 증거 없음. 승인·미승인 어느 쪽도 확정하지 않음 | 이 보고서의 공식 문서 비교 및 후속 조치 |

호스트 기록은 KST(+09:00), 에뮬레이터 `date`는 -08:00으로 표시되었다. 각 시도는 **에뮬레이터 epoch 초**를 기준으로 logcat을 잘라 과거 Intent 시험과 분리했다. 장치 시각이나 시간대는 변경하지 않았다.

## 실행한 환경 시험

| KST 시작 | 실행 경로 | 실제 관측 | 판정의 범위 |
| --- | --- | --- | --- |
| 13:03:26경 | Extended Controls → Microphone → Voice Assist 클릭 | Gleo `reason=assist` 로그, CHABAK 화면 유지. 듣기 UI·Action·Callback 미관측 | **FAIL: 이 버튼으로 관측 가능한 듣기 상호작용 진입을 확인하지 못함. 실제 발화 시험 아님.** `activation-ui-*`, `after-voice-assist-ui.png` |
| 13:05:18경 | 표준 `android.intent.action.ASSIST` | `am start` Status ok, Gleo MainActivity `onCreate` → `onDestroy`, CHABAK 화면 유지 | **FAIL: 듣기 상호작용 미관측.** OS의 Activity 실행 성공과 음성 성공을 구분. `activation-assist-*`, `after-assist.*` |
| 13:05:40경 | 표준 `KEYCODE_VOICE_ASSIST` 231 | Gleo `reason=assist` 로그. CHABAK 화면 유지 | **FAIL: 듣기 상호작용 미관측.** `activation-voice-key-*`, `after-voice-key.*` |
| 13:06:07경 | Gleo MAIN/LAUNCHER 암시적 Intent | Activity resolve 오류 | **FAIL: 해당 셸 런처 명령**. 패키지 미설치로 해석하지 않음. `activation-launcher-command.txt` |
| 13:06:28경 | 조회로 발견한 `.HistoryActivity` 명시 실행 | 리마인더 안내 화면 정상 표시 | **PASS: Gleo 화면 실행만 확인.** 음성 제어 증거 아님. `activation-history-*`, `after-gleo-history.*` |
| 13:07:11경 | VoiceInteraction 정보에 선언된 `.SettingsActivity` 열람 | 버전·호출어 설정 표시. 설정 변경 없음 | **PASS: 구성 열람만 확인.** `gleo-settings.*` |
| 13:08:29–13:10:41경 | 사용자에게 실제 PC 마이크 발화 요청, 수동 발화 관측 대기 | 이 구간 인식/Action/Callback 미관측. 후속 응답에서 물리 마이크 없음 확인 | **BLOCKED: 물리 발화 미수행.** `human-observation-*`, `user-input-clarification.json` |
| 13:10:41경 | Gleo 프로세스 1회 재시작, HistoryActivity 및 Voice Assist 재시도 | 프로세스·음성 녹음 초기화, 스키마 3개 변환 관측. 듣기 패널은 false, Action·Callback 미관측 | **PASS: 재기동·스키마 로딩**, **FAIL: 관측 가능한 듣기 진입 확보**. `gleo-restart-*`, `key-findings.log` |
| 13:18:17경 | Gleo 초기화 완료 후 ACTION_ASSIST 재시도 | MainActivity `onCreate` → `onDestroy`, HistoryActivity 유지, Action·Callback 0 | **FAIL: 듣기 상호작용 미관측.** `post-restart-assist-*`, `after-post-restart-assist.*` |
| 13:18:22경 | CHABAK MainActivity 일반 실행 | Status ok/HOT, 기존 주변 즐길거리 화면 복귀 | **PASS: 일반 실행만 확인.** Gleo 호출과 분리. `normal-launch-*`, `normal-launch.xml`, `normal-launch.png` |

재시작에는 `am force-stop --user 10 ai.umos.ambientai`와 실제 설치 컴포넌트 실행을 사용했다. 앱 데이터 삭제, 계정 변경, 숨은 설정 초기화, 음성 모드 변경은 수행하지 않았다. Gleo 전용 비공개 입력 API나 추측한 Extra는 사용하지 않았다.

## 스키마 등록에 관하여 새로 확인한 사실

재시작 후 Gleo 프로세스에서 아래 기록을 확보했다. 원문 시각은 epoch다.

```text
1794197445.984 ... [Schema] Loaded schema.json from com.chabak.app:
1794197446.199 ... ToolRepository | setAppCapability | converted | package=com.chabak.app functionCount=3
1794197446.228 ... ToolRepository | setAppCapability | complete | functionCount=22 packageCount=4
```

따라서 **이번 기동에서 CHABAK 파일 자체를 찾지 못했거나 Action 3개를 모두 파싱하지 못했다는 설명은 맞지 않는다.** 이 로그는 로컬 변환의 증거다. 서버 심사 승인, LLM에 전달된 최종 도구 목록, 특정 발화의 Action 선택 성공까지 증명하지 않는다.

동시에 `com.chabak.app.step058lab`의 스키마도 로드되었다. 해당 스키마 내부 `packageName`은 `com.chabak.app`이며 `functionCount=1`로 변환되었다. 정식 앱 3개와 같은 대상 패키지가 중복된다. Android의 무패키지 `SEARCH_CAMPING_PLACE` 조회도 두 앱을 반환했다. **중복 구성은 확인됐지만 실제 Gleo 매핑 실패의 원인인지는 미검증이다.** 공식 예시처럼 package를 지정한 Intent는 Android의 무패키지 해석과 구별해야 한다. 실험 앱을 삭제·비활성화하거나 데이터를 변경하지 않았다.

## A–D의 통신 단계별 판정

아래 인자는 **사용자가 요청한 기대값**이며 실제 Gleo가 생성한 값이 아니다.

| 시험 | 요청 발화 | 기대 Action·인자 |
| --- | --- | --- |
| A | “글레오, 차박 시작해줘.” | `MANAGE_CAMPING_TRIP`, `operation=START_STAY` |
| B | “경기도에 전기랑 화장실 있는 캠핑장 찾아줘.” | `SEARCH_CAMPING_PLACE`, `region=경기`, `electric=true`, `toilet=true`; 그 밖의 조건은 발화에 맞게 선택되는지 관찰 |
| C | “이번 주말에 갈 만한 오일장 찾아줘.” | `SEARCH_TRIP_PLACES`, `category=MARKET`, `weekend=true` 또는 의미상 일치하는 날짜. 실제 선택과 표시 날짜를 대조해야 함 |
| D | “차박할 때 배터리 얼마나 남았는지 알려줘.” | `MANAGE_CAMPING_TRIP`, `operation=READ_ENERGY` |

| 통신 단계 | A | B | C | D | 현재 증거·한계 |
| --- | --- | --- | --- | --- | --- |
| 1. 실제 발화 인식 | BLOCKED | BLOCKED | BLOCKED | BLOCKED | 물리 마이크 없음. 합성 B 발화의 에뮬레이터 입력도 RPC 실패. 유효한 음성 수신·인식 미검증 |
| 2. Action·인자 선택 | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | 스키마 변환 로그만 존재. 발화에 의한 function call·인자 기록 없음 |
| 3. CHABAK Intent 수신 | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | 초기 정상 관측 구간 Action START 로그 0. 추가 주입 중에는 장치 무응답으로 로그 회수 실패. 직접 보낸 CHABAK Action 0 |
| 4. 기존 기능 실행 | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | 해당 발화에 따른 기능 실행 미관측. 기존 화면은 음성 실행 증거가 아님 |
| 5. Gleo Callback 전달·수신 | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | 초기 정상 관측 구간 `ChabakGleo` 로그 0. 추가 입력 중 장치 로그 회수 실패. 전송 로그만으로 Gleo 소비 성공을 판단하지 않아야 함 |
| 6. Gleo 결과 안내 | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | 결과 문장·Gleo TTS·사용자 청취 확인 없음 |

필터 로그는 해당 버퍼에 남은 기록의 관측이다. 로그 부재만으로 내부 동작이 절대 없었다고 증명하지 않는다. 물리 발화가 없었고 대체 입력도 실패하여 end-to-end 성공으로 판단할 근거가 없다.

## 마이크 없는 직접 오디오 시험

CHABAK 앱에는 STT/TTS·자체 AI를 추가하지 않았다. PC의 설치된 Windows Runtime 한국어 음성 **Microsoft Heami / ko-KR**로 테스트 WAV 5개를 생성했다. 앱 의존성을 바꾸지 않고 QA 출력 폴더에만 gRPC 도구를 설치하고, SDK Emulator 37.1.11에 동봉된 `emulator_controller.proto`를 그대로 컴파일해 사용했다. 로컬 gRPC 인증 값은 파일에서 메모리로만 읽었고 로그에 쓰지 않았다.

| 직접 시험 | 관측 결과 | 판정과 증거 |
| --- | --- | --- |
| 한국어 테스트 음성 생성 | 호출어·B/A/C/D 문장의 WAV 생성. 자체 앱 TTS가 아닌 PC의 QA 입력 파일 | **PASS: 입력 파일 준비**. `input-audio-manifest.json`, `input-*.wav` |
| 공식 API 상태 확인 | localhost 인증 연결, `getStatus`/`getMicrophoneState` 정상, booted=true | **PASS: 제어 API 접속**. `audio-capability-audio.json` |
| 13:26:33 B 합성 음성 입력 | 16 kHz/PCM16/mono, 13.33초: 무음 → “글레오” → 간격 → B 문장 → 무음. `MODE_UNSPECIFIED` 주입 RPC가 28.337초 뒤 DEADLINE_EXCEEDED. 출력 수집도 0 bytes/시간 초과. 이후 gRPC·장치 shell 무응답 | **FAIL: 오디오 전송 시험**. Gleo 인식 실패 판정은 아님. `synthetic-camping-audio.json`, `synthetic-camping-input.wav`, `audio-after-attempt-audio.json` |
| 13:29–13:31 복구 | 정상 종료 명령도 완료되지 않아 실행 경로·AVD를 확인한 해당 qemu만 종료. 같은 AVD를 snapshot load/save 없이 cold boot. 사용자 데이터 초기화 옵션 미사용. 마이크 ON 복원 및 일반 실행 확인 | **PASS: 1차 복구**. `audio-recovery-*` |
| 13:32:36 실시간 입력 경로 점검 | `MODE_REAL_TIME`, 20 ms 간격 패킷, **1초 무음만** 전송. 1.288초 뒤 UNAVAILABLE/connection reset, emulator 프로세스 종료. 이 시도에는 음성 명령이 없음 | **FAIL: 실시간 오디오 전송 시험**. `synthetic-silence-realtime-audio.json` |
| 호스트 오류 | `Attempt to initialize voice without DirectSoundCapture object`, `Failed to create voice 'virtio-snd-mic0'` 반복 | **캡처 백엔드 오류 확인**. `audio-host-errors.txt`, `audio-recovery-emulator-stdout.txt`, `audio-recovery-emulator-stderr.txt` |
| 13:33–13:35 최종 복구 | 동일 AVD cold boot, booted=true, 마이크 ON 복구 확인, CHABAK 일반 실행 Status ok, 설치 APK 해시 보존 | **PASS: 최종 복구**. `audio-recovery-final-*` |
| 13:38–13:40 진단 출력 정리 | 반복 오류로 진단 stdout 파일이 계속 커지지 않도록 AVD를 정상 종료 후 재실행. 연속 stdout은 보관하지 않음. booted=true, 마이크 ON, CHABAK Status ok 재확인 | **PASS: 일반 실행 상태 복귀**. `audio-final-launch.json`, `audio-final-clean-microphone-audio.json`, `audio-final-clean-chabak-launch.txt` |

확인된 것은 **현재 호스트의 캡처 백엔드가 마이크 voice를 만들지 못한다는 오류와 해당 입력 시험 실패**다. 물리 마이크 없음은 사용자에게 확인했지만, DirectSound 오류가 장치 부재만으로 발생했는지 드라이버·권한·에뮬레이터 구현 문제도 있는지는 분리하지 못했다. 무응답/종료의 내부 스택 원인도 확보하지 못했다. 같은 실패를 반복하지 않도록 수집 스크립트의 음성 주입 경로를 차단했다. 상태 조회·마이크 원복 기능만 실행 가능하다. C/D/A 음성 파일은 준비됐으나 입력하지 않았다.

## 실패 원인 분류

| 후보 | 확인 상태 | 필요한 추가 확인 |
| --- | --- | --- |
| Gleo 미설치 | **해당하지 않음** | 실제 시스템 패키지·버전·화면 확인 |
| Gleo 프로세스 미실행 | **해당하지 않음** | 기존 실행 및 재시작 확인. 다만 듣기 상태 진입은 별개 |
| 호스트 마이크 입력 경로 | **오류 확인** | 물리 마이크 없음, DirectSoundCapture·virtio 마이크 생성 실패. 공식 gRPC 대체 입력도 실패. 동작하는 캡처 장치 또는 Pleos/Android Emulator 측 수정 환경 필요 |
| 에뮬레이터 지원 제한 | **확정하지 못함** | 현재 이미지에서 Gleo의 호출어·SERVER 음성·외부 앱 Action을 함께 검증할 수 있는지 Pleos 확인 필요 |
| 스키마 파일 미탑재·전체 파싱 실패 | **이번 기동에서 배제 가능** | 3개 변환 로그 있음. 중복 실험 스키마 분리와 최종 도구 등록 확인은 남음 |
| 심사 미등록·미승인 | **알 수 없음** | 프로젝트 소유자의 콘솔/심사 증거 또는 Pleos 측 확인 필요 |
| 권한·Intent 연결 | 수신 경로와 RECORD_AUDIO/차량 조회 권한 확인 | 실제 Gleo가 발송하는 대상 패키지·Action·Extra는 미확보 |
| Gleo 음성 엔진 오류 | **확정하지 못함** | 녹음 경로와 SERVER 모드는 확인. 실제 인식·합성 호출 결과 없음 |
| 네트워크·인증 | 일반 네트워크 VALIDATED, Gleo 인증은 미확인 | Gleo SERVER 요청·인증 결과 필요. 리마인더 `userId is empty`, 서버 동기화 실패 로그가 있으나 음성 인증 실패의 직접 증거는 아님 |
| 실제 차량에서만 가능한 기능 | 이 시험의 실패 원인으로 확정하지 않음 | START_STAY는 앱의 차박 세션 시작이며 차량 모드 제어가 아님. 배터리는 에뮬레이터 SDK 값으로 시험 가능했던 이력이 있음. 실차 제어는 이번 시험 범위 밖 |

`HttpExporter`의 403은 로그 내보내기 실패다. 이를 STT·LLM·TTS 인증 거절로 바꾸어 해석하지 않았다. Gleo 재시작 로그에는 `sttMode:SERVER, ttsMode:SERVER`가 명시되어 있으므로 과거 자체 AI의 ON_DEVICE 오류를 현재 Gleo 실패 원인으로 적용하지 않았다. CAAS에 남은 과거 CHABAK locale 설정도 현재 CHABAK이 자체 STT/TTS를 실행한다는 뜻이 아니다.

## 공식 문서와 현재 환경 비교

- [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts)는 LLM의 function call → 앱 Action → Callback → 최종 답변 구조를 설명한다. 앱의 모든 기능을 자동 제어하는 계약은 아니다.
- [스키마 전달 가이드](https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/deliver-schema)는 `assets/schema.json`, Action/DEFAULT 필터, 스키마 검토·승인, Gleo 기동 시 스캔을 설명한다. 현재 APK의 파일·필터와 이번 기동의 로컬 스캔은 확인했지만 심사 상태는 확인하지 못했다.
- [에뮬레이터 설정](https://document.pleos.ai/en/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)은 SDK 시험 환경, 호스트 마이크 입력 설정, VHAL 사용을 위한 cold boot를 설명한다. 현재 마이크 입력·cold boot 설정은 맞지만 이 문서를 전체 Gleo 음성 서비스의 동작 보증으로 해석하지 않는다.
- [Android Extended Controls](https://developer.android.com/studio/run/emulator-extended-controls)는 호스트 마이크 입력과 Voice Assist 버튼의 표준 Assistant 키 전송을 설명한다. 문서상 AAOS에서는 해당 버튼이 제공되지 않는다고도 명시하므로, 현재 빌드에서 보이는 버튼을 Pleos 전용 음성 진입 보증으로 삼지 않는다.
- [Android Emulator 제어 문서](https://developer.android.com/studio/run/emulator-console)는 `injectAudio` 입력과 `streamAudio` 출력 캡처를 설명한다. 실제 호출 형식은 [AOSP proto](https://android.googlesource.com/platform/prebuilts/android-emulator/+/master/linux-x86_64/lib/emulator_controller.proto)와 로컬 SDK 동봉 proto를 확인했다. API의 존재와 이 Windows 호스트에서의 성공은 구분한다. `-audio none`은 오디오 전체를 끄는 옵션이므로 음성 시험의 해결책으로 사용하지 않았다.
- [에뮬레이터 v2.1.0 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note)는 2026-10-19 버전과 LLM SDK 제공 종료 등을 명시한다. LLM SDK 종료를 Gleo AI 앱 전체 미지원으로 확대 해석하지 않는다.
- [프로젝트·SDK 신청](https://document.pleos.ai/en/docs/pleos-only/create-my-project)은 개발자 등록·My Project·SDK별 접근 상태를 설명하며 v2.1.0부터 CRN이 불필요하다고 명시한다. CHABAK의 Gleo Interface 검증을 위해 자체 STT/TTS 자격 증명을 앱에 넣는 것은 필요한 다음 조치로 제안하지 않는다.
- [Callback 가이드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)와 [코드 예시](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples)는 실행 상태·결과 Broadcast·Gleo 음성 응답을 구분한다. Callback 전송과 Gleo 수신·최종 발화 모두를 관찰해야 PASS다.

## 해결을 위한 다음 조치와 수동 시험

1. **동작하는 오디오 캡처 경로를 먼저 마련한다.** 현재 PC에는 마이크가 없고 공식 오디오 주입도 실패했다. USB 마이크/마이크 포함 헤드셋을 연결해 Windows 기본 입력·레벨·접근 허용을 확인하거나, 마이크 없이 주입할 수 있는 공식 지원·수정된 에뮬레이터 환경을 제공받아야 한다. 마이크 ON 표시와 오디오 컨트롤러 OK만으로 신호가 입력된다고 판단하지 않는다. 현재 `injectAudio` 재실행은 차단했으며 같은 환경에서 반복하지 않는다. 캡처 경로가 정상화된 뒤 “글레오”의 듣기 표시·응답음을 확인하고 B부터 시험한다.
2. **중복 스키마를 분리한 시험 환경을 준비한다.** 기존 `step058lab` 데이터를 보존하면서 정식 `com.chabak.app`만 설치된 별도 v2.1.0 AVD를 사용한다. Gleo 재기동 후 해당 패키지 `functionCount=3` 및 중복 로드 부재를 확인한다. 현재 AVD의 사용자 데이터를 삭제하지 않는다.
3. **프로젝트 소유자가 등록·심사를 확인한다.** Playground → Workspace → My Project에서 CHABAK 프로젝트 및 사용하는 Vehicle State/NaviHelper 접근 상태를 확인한다. `com.chabak.app`, 현재 APK·스키마 해시와 Action 3개를 기준으로 Gleo 스키마 심사·승인 및 개발용 에뮬레이터 허용 여부를 Pleos 담당자에게 확인한다. 공개 가이드에서 확인되지 않은 전용 등록 버튼이나 우회 API는 제시하지 않는다. 키·Secret을 이 보고서나 앱·채팅·로그에 붙이지 않는다.
4. **듣기 진입이 계속 안 되면 Pleos에 환경별 지원·설정을 확인한다.** 전달할 진단은 이미지 v2.1.0/API34/x86_64, Gleo 4.2.0.15, GAAS 4.2.10-coral-aosp, SERVER/SERVER, 호출어 ON, 활성 녹음 트랙, `Unknown [4ch]`, Assistant 버튼의 무반응, `key-findings.log`다. 문의할 사항은 이 조합의 호출어/음성 입력 라우팅 지원, Gleo 자체 계정·서버 프로비저닝 요건, 개발 앱 스키마 승인 적용 방법이다. 인증이 원인이라고 미리 단정하지 않는다. 지원 이미지·계정·Devbox 또는 실차가 필요하다는 공식 답변을 받은 경우 그 환경에서 재검증한다.
5. **Gleo의 기본 음성 응답이 확인된 뒤 A–D를 차례로 시험한다.** A는 정차/P 상태 및 테스트용 여행을 선택하고 앱의 차박 기록 1회 생성·중복 여부를 확인한다. 차량의 캠핑 모드·조명·공조·충전 제어는 시험하지 않는다. B는 경기/전기/화장실, C는 MARKET/주말 날짜, D는 SDK 원본 잔량과 에뮬레이터 출처를 화면·실제 선택 인자와 대조한다. A의 테스트 기록은 일반 앱 기능으로 사용자가 확인·정리한다.
6. **각 시험에서 여섯 단계의 증거를 함께 남긴다.** 인식문, Gleo 선택 Action·인자, Android 대상 Activity, CHABAK 결과, Gleo의 Callback 수신 기록, 결과 문장·사용자 청취 확인을 확보한다. 현재 CHABAK `ChabakGleo` 로그는 상태/사유만 출력하므로 정확한 Extra나 Gleo 수신의 단독 증거가 아니다. 필요한 Gleo 진단 로그의 제공 방법은 Pleos에 확인한다.

다음 명령은 **시험을 관측만 하며 CHABAK Action을 전송하지 않는다.** PowerShell을 프로젝트 루트에서 열고, 실제 사람이 발화하기 직전에 시작 시각을 잡는다. 발화 뒤 캡처한다. `-HumanUtteranceConfirmed`는 사람이 실제로 발화한 경우에만 지정한다. 이 플래그 자체는 인식 성공 판정이 아니다.

```powershell
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
$from = [long]((& $adb -s emulator-5554 shell date +%s).Trim())
# 이제 PC 마이크에 B 발화를 말하고, 실제 반응을 확인한 뒤 다음 명령 실행
.\outputs\gleo-validation\capture-window.ps1 -Label manual-voice-b -StartEpoch $from -HumanUtteranceConfirmed
```

`-Label`을 `manual-voice-a/c/d`로 바꾸어 시도별로 분리한다. 화면·Gleo 결과 음성은 사용자의 화면 녹화/청취 기록도 함께 남긴다. 현재 수집 스크립트는 원음이나 Callback 수신기를 만들지 않으며 모든 필수 증거를 자동으로 확보하는 도구가 아니다.

## 보존한 기능과 이번 변경 범위

이번 작업의 추가 파일은 이 보고서와 `outputs/gleo-validation/`의 관측·오디오 QA 스크립트, 로그·스크린샷·JSON·합성 WAV, 공식 proto로 생성한 코드와 QA 전용 Python 도구다. 앱 소스·schema·Manifest·Gradle·백엔드·UI 디자인은 변경하지 않았다. 기존 Git 변경은 유지했다. CHABAK에 자체 AI/STT/TTS/LLM 서버는 다시 추가하지 않았다. 시험 중 발생한 AVD 무응답·종료는 위에 별도로 기록했고 복구 확인을 남겼다.

캠핑장 추천, 여행지 검색, 스마트 에너지, 스마트 모닝, 음악의 이번 전체 회귀 및 새 Gradle 빌드는 **NOT TESTED**다. 소스 수정 없이 설치 APK 해시·수신 경로와 일반 실행을 확인하는 범위로 한정했다. [이전 Intent 검증 보고서](CHABAK_GLEO_ONLY.md)의 빌드·회귀·Intent 성공은 과거 앱 측 증거이며 이 보고서의 음성 PASS에 합산하지 않는다.

현재 Gleo에서 실제 제어 성공을 입증한 CHABAK 기능은 **없다**. 앱이 노출한 실행 경로는 캠핑장 추천, 여행지 검색, 앱 차박 세션 시작, 지원 권한이 있는 Vehicle SDK 배터리 읽기다. 음악·스마트 모닝·차량 조명 등은 이 세 Action에 노출되지 않았으며, 플랫폼 전체의 미지원과 구별한다.
