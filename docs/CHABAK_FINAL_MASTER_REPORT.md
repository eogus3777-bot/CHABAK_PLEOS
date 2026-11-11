# CHABAK STEP 121–200 최종 개발 보고서

프로젝트 `C:\AI\CHABAK_PLEOS`  
검증 환경 Pleos Connect v2.1.0, Android Automotive API 34, `emulator-5554`, 사용자 10  
앱 `com.chabak.app` 1.0, versionCode 1, Debug

## 검증 결과

| 항목 | 결과 |
| --- | --- |
| 기존 사용자 데이터 | SQLite v4→v5와 APK 업데이트를 거쳐 기존 여행 2건의 종전 열이 모두 일치했다. 좌표, 보호 기준, 준비물과 모닝 설정을 포함한다. 신규 검증 여행 1건은 분리해 보관했고 마지막에 기존 활성 여행을 다시 선택했다. |
| 고캠핑 | 에뮬레이터에서 8085 `/health`, `/recommend`, `/camps` HTTP 200. 경기·숲의 고유 ID 5건과 좌표 5쌍을 확인했고, 첫 추천의 ID 단건 조회에서 좌표가 일치했다. `mapX`는 경도, `mapY`는 위도다. 등록되지 않은 차량 내 숙박·충전 정보는 미확인이다. |
| 내비 | 새 여행에서 NaviHelper 목적지 설정, `route_started`, `destination_callback matched=true`, `ROUTE_GUIDING`을 두 차례 관찰했다. 시스템 내비 화면의 테스트 목적지 이름도 확인했고 테스트 후 안내를 종료했다. 실도착은 검증 범위 밖이다. |
| 차박과 여행 기록 | 차박을 수동으로 시작하고 앱 업데이트 이후 진행 상태를 복원했다. 테스트 이미지 두 장, 메모와 후기를 앱에서 저장하고 다시 열었다. 확대와 Android 사진 공유 선택 화면을 열었고, 외부 앱으로 보내는 동작은 실행하지 않았다. 한 장을 기록에서 제거한 뒤 테스트 원본이 MediaStore에 남아 있었다. 종료 시각과 보관 표시를 확인했다. |
| 날씨 | 실제 Open-Meteo 응답을 받아 앱에서 캠핑장 인근 값과 모델 시각 및 조회 시각을 표시했다. 대시보드의 지역 대표지점과 분리했다. 방문일 예보는 확인 필요로 둔다. |
| 에너지 | 에뮬레이터 SDK 응답 100%와 공칭 77.4 kWh, 보호 기준 30%에서 가용 70%p를 확인했다. 확인된 소비전력이 없으면 실제 사용시간과 귀가 가능 여부를 산출하지 않는다. 충전 여부는 미지원이고 충전 스위치는 꺼짐으로 반환됐다. 권한 거부와 바인딩 실패는 테스트 환경에서 재현했다. |
| 스마트 모닝 | 대체 시험 알람의 예약 화면, 수신 로그 `alarm_delivered test=true`, 자연음 시작 로그 `alarm_audio_started`, 종료 후 예약 없음 상태를 확인했다. 정확한 알람 권한이 없는 환경에서 예정 시각보다 지연될 수 있다. |
| 환경·조명 | 규칙 기반 추천, 자연음 직접 재생, Audio Focus 요청과 반환, Light Capability `READ_WRITE` 영역 1개를 확인했다. 아침·낮·노을·밤을 각각 선택했고, 앱 재시작 후 수동 밤 설정이 복원됐다. 검증 후 원래 자동 테마로 돌렸다. 조명 제어 요청은 포함하지 않는다. |
| 8086 | 연결 응답을 받지 못했다. 이 셸에는 INFO·FOOD 인증 설정이 없었다. 실제 응답, 음식 인증, 방향별 휴게소 검증은 보류했다. 기존 백엔드 소스와 8085 실행 상태를 유지했다. |

## 단계별 현황

| STEP | 완료 범위 | 남은 검증 또는 기능 |
| --- | --- | --- |
| 121–130 | 공통 일기 조사, 여행 ID 연계, v5 마이그레이션, 다중 선택, 썸네일·확대·개별 제거, 메모·후기, 실제 시각 타임라인, 공유 선택 화면, 업데이트·복원·계측 | 기존 일기에는 tripId가 없어 읽기 전용으로 유지했다. 외부 대상 앱까지의 공유 전송, 대용량 실제 기기 스트레스 테스트는 미실행이다. |
| 131–140 | 주요 화면 실사·캡처, 홈 네 메뉴, 자연 배경·하단 카드 유지, 날씨 출처와 시각, 공식 상세, 실제 터치 경로 | 전체 폰트·해상도·테마 조합과 각 실패 화면의 시각 검수는 일부다. |
| 141–150 | 추천부터 종료 후 다이어리까지 새 여행에서 실행, 기존 준비물과 내비 유지, 수동 시작·종료·보관, 재실행 및 업데이트 복원 | 내비 SDK가 제공하는 도착 콜백은 조사했다. 앱은 지속적인 이동 상태나 실도착을 확정하지 않으며, 실제 도착 후 주차 상태에서 사용자가 차박을 시작한다. |
| 151–160 | 8085 실제 조회와 오류 스키마, 필터·좌표, 날씨 대상과 만료, 마지막 정상 검색 캐시 구분, Debug 연결 범위 제한, Python·Android 파서 회귀 | 8086 실데이터와 방향, 서버 중단·복구 시나리오, 운영 HTTPS는 미검증이다. |
| 161–170 | SDK 값·보호 기준 표시, 배터리 경계값·연결 실패 테스트, AAOS 조작 게이트, 시험 알람과 자연음, 조명 Capability, 앱 백업 정책 | 동적 주행 제한 UI는 현재 에뮬레이터 사용자 빌드에서 car_service 셸 제어가 제한되어 미검증이다. 정확한 알람 승인 상태, 프로세스 강제 종료 후 수신, 아침 전환 재현, 실차 및 조명 제어도 이번 작업에서 검증하지 않았다. |
| 171–180 | API 34 기기, 검증 여행 생성, SQLite 복원, 내비·에너지·모닝·환경·다이어리의 정상 경로와 분리 테스트 | 최초 설치 온보딩, Gleo 실제 음성, Wi-Fi 단절 및 실차 시나리오는 별도다. |
| 181–190 | 소개·시연·기술 구조·심사 Q&A 문서와 실제 화면 캡처, 검증용 여행의 32초 에뮬레이터 영상과 전체 녹화 절차 | 공모전 공식 제출 형식 확인은 별도다. |
| 191–200 | 코드와 앱 생명주기 점검, 검색·디코딩 변경, 안전 정책 검수, 전체 자동 테스트, Debug 업데이트 및 인수인계 | 성능은 단일 콜드 스타트 및 대표 화면 메모리 스냅샷 수준이다. 운영 부하·장기 동작·실차 안전성까지 검증하지 않았다. |

세부 단계 체크포인트는 `docs/CHABAK_MASTER_CHECKPOINTS_121_200.md`, 시연·발표 자료는 `docs/CHABAK_MASTER_DEMO.md`에 있다.

## 이번 변경 파일

- `TripStore.kt`, `TripDiary.kt`, `TripDiaryScreen.kt`에 v5 저장 구조와 화면을 연결했다.
- `MainActivity.kt`에서 여행 기록 진입, 수동 차박 이력과 상태 복원, 검색 캐시 안내, 공통 날씨를 연결했다.
- `ChabakWeather.kt`에서 대상 선택, 모델 응답 파싱, 조회 시각을 분리했다.
- `AndroidManifest.xml`과 main/debug의 `chabak_network_security.xml`에서 기본 평문 차단, Debug 에뮬레이터 연결 예외, Android 자동 백업 비활성화를 적용했다.
- `TripDiaryInstrumentedTest.kt`, `ChabakWeatherInstrumentedTest.kt`, `TripEnergyInstrumentedTest.kt`, `TripEnergyPlanTest.kt`에 저장 격리·중복 시작·날씨 및 보안·배터리 경계 검증을 추가했다.
- 사용자의 기존 미커밋 변경과 겹치는 파일이 있어 브랜치 분리·스테이징·커밋은 수행하지 않았다.

## 재현과 인수인계

- Android Studio에서 `C:\AI\CHABAK_PLEOS`를 열고 Device Manager의 Pleos Connect v2.1.0 API 34를 사용한다. 실행 중인 AVD는 그대로 사용한다.
- 빌드 작업은 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest`다. 이 작업 환경의 명령줄 Gradle은 캐시 잠금 접근 오류가 있어 Android Studio에서 실행했다.
- 설치와 테스트는 아래 명령을 사용한다. 앱 삭제나 데이터 초기화 없이 업데이트한다.

```powershell
adb -s emulator-5554 install -r --user 10 .\app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5554 install -r --user 10 .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument --user 10 -w com.chabak.app.test/androidx.test.runner.AndroidJUnitRunner
python -B -m unittest discover -s backend -p "test*.py"
```

- 8085 프로세스가 이미 떠 있으면 그 상태를 유지한다. 꺼진 상태라면 `GOCAMPING_SERVICE_KEY`가 등록된 로컬 셸에서 `python .\backend\server.py`를 실행한다. 호스트 `127.0.0.1:8085/health`와 에뮬레이터 `10.0.2.2:8085/health`로 연결을 확인한다. 키는 서버 환경변수에만 둔다.
- 8086은 인증이 설정된 로컬 셸에서만 `python .\backend\expressway.py`와 `/health`를 확인한다. 휴게소의 실사용 검증은 INFO와 FOOD 응답 및 경로 방향을 각각 검증한 뒤 진행한다.
- 시연용 SoC·용량은 에뮬레이터 SDK 응답이다. 수동 소비전력 계산, 단색 테스트 사진과 테스트 메모는 시연용 입력이다. 실차 모델별 권한·차량 데이터·도착과 안전 조건은 별도 검증이 필요하다.

공식 참고 자료 [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker), [Android Sharesheet](https://developer.android.com/develop/ui/compose/sharing/send), [Android 네트워크 보안](https://developer.android.com/privacy-and-security/security-config), [고캠핑](https://www.data.go.kr/data/15101933/openapi.do), [Open-Meteo](https://open-meteo.com/en/docs), [Pleos NaviHelper](https://document.pleos.ai/api-reference/connect-sdk-pleos/NaviHelper/intro), [Pleos Vehicle SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro).

## 최종 빌드 증거

| 검사 | 실행 | 통과 | 실패 |
| --- | ---: | ---: | ---: |
| Python unittest | 9 | 9 | 0 |
| Kotlin JVM | 10 | 10 | 0 |
| API 34 Android 계측 | 27 | 27 | 0 |
| 합계 | 46 | 46 | 0 |

- APK `C:\AI\CHABAK_PLEOS\app\build\outputs\apk\debug\app-debug.apk`
- 빌드 시각 2026-11-07 11:45:27 KST, 크기 41,833,781 바이트, APK 서명 v2 검증 통과
- SHA-256 `8F8B5CF6F64EF54DC80B163615B39C3F7D1E03D77D2365FACF7DBE7133B20278`
- 마지막 업데이트 후 `am start -W`에서 단일 콜드 스타트 1,580ms. 대표 상태의 메모리 스냅샷은 PSS 약 97 MiB였다. 반복 측정과 운영 부하 검증은 별도다.
- 8085 입력 오류는 잘못된 limit에서 HTTP 400, 미등록 경로에서 HTTP 404, 불일치 검색에서 HTTP 200 및 빈 배열이었다. 정상 반복 조회의 에뮬레이터 왕복 시간은 이번 측정에서 약 0.07–0.55초였다.
- 시연 영상은 에뮬레이터의 검증용 여행 기록, 사진 확대, 스마트 에너지 화면을 담는다. 캡처의 내비 거리·시간과 왕복 배터리 표시는 Pleos 시스템 내비 화면의 값이다.
