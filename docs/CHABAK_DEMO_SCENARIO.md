# CHABAK 2.0 시연 시나리오

2026-11-11 KST · 약 2분 50초 · Pleos Connect v2.1.0 에뮬레이터

현재 검증한 시연은 CHABAK 화면 조작, Vehicle SDK, 테스트용 Android Intent를 사용한다. **실제 Gleo 발화 시연은 마이크·스키마 등록·Gleo 실행 환경 확인 후 별도로 검증해야 한다.**

## 시연 전 준비

- 표준 `app/build/outputs/apk/debug/app-debug.apk`를 설치한다. 기존 8085 고캠핑 서버의 `/health`가 정상인지 확인한다.
- 에뮬레이터를 P·속도 0으로 두고 SDK 배터리가 보호 기준 30%보다 높게 조회되는지 확인한다.
- 원래 여행을 보존하고 시연용 여행을 앱에서 만든다. 캠핑장 위치가 있는 여행을 선택한다. 기존 차박과 알람의 실행 상태를 확인한다.
- 환경 조성에서 `차박 시작 시 조명 적용`을 명시적으로 켠다. 시연 후 동의 설정과 바꾼 조명을 복원한다.
- Virtual Console을 별도 화면에 열고 Emulation Controls의 Ambient Brightness·Ambient Color 카드를 준비한다. 카드 헤더 갱신이 늦으면 닫고 다시 연다.
- 소리 재생은 짧게 수행한다. 이번 검증은 자연음 재생 상태와 Android 오디오 활성화를 확인했다.

```powershell
$adbPath = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
& $adbPath forward --no-rebind tcp:8088 tcp:8088
```

Virtual Console 주소는 `http://127.0.0.1:8088/`이다.

## 진행

| 시간 | 조작 | 설명과 확인할 결과 |
| --- | --- | --- |
| 0~15초 | 홈 화면 | 시간·날씨와 자연 배경, 차박지·내 여행·차박 중·에너지 메뉴를 보여준다. 날씨 미수신 상태는 화면 표시대로 설명한다. |
| 15~45초 | 차박지 찾기에서 경기·가평·숲·화장실·전기·매점 선택 후 추천 보기 | 고캠핑 등록 결과 최대 5개를 확인한다. 상세의 주소·시설·예약 정보를 보여준다. |
| 45~70초 | 시연용 여행을 선택하고 아래 START_STAY 테스트 Intent 실행 | 차박 중 화면 진입과 차박 기록 생성을 보여준다. 사전 동의·P·배터리 조건이 충족되면 시간대 조명 적용 결과를 확인한다. 반복 호출 시 기존 차박을 유지한다. |
| 70~90초 | 스마트 에너지 | 에뮬레이터 Vehicle SDK SOC, 공칭 용량·보호 잔량을 확인한다. 측정 소비전력이 없으면 예상 시간의 계산 불가 표시를 유지한다. |
| 90~110초 | 차박 중 자연음 ▶ → Ⅱ → ■ | 재생·일시정지·정지 상태와 플레이리스트 연결을 보여준다. 장르·스마트 모닝은 기존 기능으로 안내한다. |
| 110~145초 | 환경 조성에서 아침→낮→노을→밤 | 배경과 조명 프리셋 변화를 보여준다. 각각 White 25, Cyan 65, Purple 30, Blue 12를 SDK 값과 Console로 대조한다. 사전 동의한 현재 차박에서 실행한다. |
| 145~160초 | 아래 MARKET·weekend 테스트 Intent 실행 | 이번 주말 등록 장날 후보를 보여준다. 실제 개장 여부 확인이 필요하다고 안내한다. |
| 160~170초 | Gleo 검증 범위 설명 | “세 Action의 앱 실행과 Callback은 검증했습니다. 현재 PC에 마이크가 없어 Gleo 실제 음성 호출은 미검증입니다.”라고 설명한다. |

## 테스트용 Intent 명령

아래 명령 실행 장면에는 **테스트용 Intent**라고 표시한다. CHABAK의 실제 앱 실행 경로를 사용한다. 각 명령은 앞선 응답을 확인한 뒤 실행한다.

```powershell
# 선택한 시연용 여행의 차박 시작
& $adbPath shell am start --user 10 -n com.chabak.app/.MainActivity -a MANAGE_CAMPING_TRIP --es operation START_STAY

# SDK 배터리 조회
& $adbPath shell am start --user 10 -n com.chabak.app/.MainActivity -a MANAGE_CAMPING_TRIP --es operation READ_ENERGY

# 주말 오일장 후보
& $adbPath shell am start --user 10 -n com.chabak.app/.MainActivity -a SEARCH_TRIP_PLACES --es category MARKET --ez weekend true

# 화면 조작 대신 검색 파라미터 전달을 보여줄 때 사용
& $adbPath shell am start --user 10 -n com.chabak.app/.MainActivity -a SEARCH_CAMPING_PLACE --es region 경기 --es district 가평 --es nature 숲 --ez toilet true --ez electric true --ez store true
```

## 시연 조건이 충족되지 않을 때

| 상황 | 실제 화면을 기준으로 진행할 내용 |
| --- | --- |
| 서버 연결 실패·빈 결과 | 표시된 실패·빈 결과와 재시도 버튼을 설명한다. 결과 화면 증거는 `outputs/final-screenshots/live-tests/gleo-camping-live.png`에서 확인할 수 있다. |
| 조명 권한·지원·배터리·P 조건 부족 | 앱의 보류 이유를 보여주고 해당 항목은 PARTIAL 또는 BLOCKED로 설명한다. |
| Gleo 음성 입력 준비 완료 | 네 발화를 먼저 실제 검증하고 인식·매핑·Callback·응답 로그를 확보한 뒤 시나리오에 반영한다. |
| 스마트 모닝 소개 | 예약 UI와 취소를 보여줄 수 있다. 실제 알람이 울리는 장면은 사전 검증 후 추가한다. |

시연 후 임시 차박을 종료하고 시연용 알람을 취소한다. 원래 여행·동의·조명·차량 시뮬레이터 상태를 복원한다. 테스트 실행 결과와 추가 검증 항목은 `docs/CHABAK_FINAL_REPORT.md`에 정리했다.
