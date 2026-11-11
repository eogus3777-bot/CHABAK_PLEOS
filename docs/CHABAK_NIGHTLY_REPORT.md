# CHABAK 야간 통합 개발 보고서

점검일 2026-11-07 (Asia/Seoul)  
프로젝트 `C:\AI\CHABAK_PLEOS`

## 단계별 결과

| 단계 | 상태 | 변경 및 확인 결과 |
| --- | --- | --- |
| 059 | 소스 반영, 앱 미검증 | 차박이 캐릭터, 플로팅 버튼, 채팅, 음성 명령 라우터, STT/TTS와 전용 마이크 선언을 제거했다. Gleo 스키마, 인텐트 및 콜백 코드를 보존했다. 캐릭터 이미지 파일은 보존했다. |
| 060 | 소스 반영, 화면 미검증 | 홈 하단에 차박지 찾기, 내 여행, 차박 중, 스마트 에너지를 배치했다. 넓은 화면은 두 열, 좁은 화면은 스크롤 가능한 한 열이다. 기존 여행 선택창과 자연 배경 전환을 재사용했다. 탭 전환과 버튼 가림은 기기에서 확인하지 못했다. |
| 061 | 호스트 실데이터 검증, Android 미검증 | 호스트 8085에서 `/health`, `/camps`, `/recommend`가 HTTP 200을 반환했다. 경기·숲 및 화장실·전기 조건 추천 5곳 모두 시설 개수 필드와 예약 링크가 있었고, 미확인 EV 충전과 차량 숙박은 null이었다. 앱의 상세 표시, 선택, 재시작 후 복원은 미검증이다. |
| 062 | 공식 문서·코드 확인, SDK 실행 미검증 | 기존 Vehicle SDK 2.3.2의 초기화, 권한과 capability 확인, SoC 조회·구독, 용량 조회 및 실패 경로를 검토했다. 기존 숙박 에너지 추정과 여행별 계획 저장을 유지했다. 최소 잔량 대비 여유는 숙박 추정치이고, 귀가에 필요한 에너지는 미확인으로 표시된다. Pleos Virtual Console과 실제 차량 수신은 미검증이다. |
| 063 | 진단 보완, 실제 음식 응답 미검증 | INFO `key`와 FOOD `serviceKey` 호출을 유지했다. 퍼센트 인코딩된 인증값을 한 번 디코딩한 뒤 쿼리를 구성하고, HTTP 오류·통신 실패·응답 형식 오류·제공기관 거절을 구분했다. `--check`는 HTTP 상태와 숫자 제공기관 코드만 추가 표시한다. INFO와 FOOD 모두 현재 검사에서 `key_missing`이었다. |
| 064 | 기존 흐름 검토 및 진입점 연결, 전체 흐름 미검증 | 준비물 화면에서 현재 여행의 가는 길·귀가 화면으로 바로 이동할 수 있게 했다. 여행 저장 구조나 DB 스키마 변경은 없다. 기존 코드의 장소 변경 시 준비물 및 이동 상태 갱신, 여행 ID 기반 저장, 방향별 경로 증거 검사와 노지 허용 검사 경로를 검토했다. 여행 A/B, 뒤로 가기, 재시작 및 자연음·알람과의 충돌은 기기 미검증이다. |
| 065 | 보고서 작성, Android 검증 보류 | Python 테스트와 고캠핑 호스트 확인을 마쳤다. Android Debug 빌드, JVM 테스트, 계측 테스트, 화면 시연은 이번 변경 기준으로 미완료다. |

## 변경 파일과 백업

- `app/src/main/java/com/chabak/app/MainActivity.kt` — 캐릭터 전용 기능 제거, 홈 정리, 준비물에서 이동 정보로 연결.
- `app/src/main/AndroidManifest.xml` — 캐릭터 전용 음성 선언 제거. Gleo 인텐트와 알람 수신기·서비스 보존.
- `backend/expressway.py` — 휴게소 API의 인증값 인코딩 및 오류 진단 보완.
- `backend/test_expressway.py` — HTTP·제공기관 거절과 잘못된 응답을 구분하는 회귀 테스트 추가.
- `docs/CHABAK_NIGHTLY_REPORT.md` — 이 보고서.
- 작업 시작 시 Git이 저장소를 찾지 못해 브랜치를 만들 수 없었다. 수정 전 파일과 단계별 MainActivity 사본은 프로젝트 내부 `.chabak-backup/nightly-059-065`에 보관했다.

## 빌드와 테스트

- Gradle 9.6.0 Wrapper는 사용자 전역 캐시의 잠금 파일 접근이 거부됐다. 프로젝트 내부 캐시와 기존 Gradle 배포본으로 다시 시도했으나 원본과 프로젝트 내부 검증 사본 모두 Gradle 구성 단계에서 `Configuring project with invalid directory`로 중단됐다. Android 컴파일 및 테스트 작업까지 도달하지 못했다.
- ADB에 연결된 실행 대상은 없었다. 일반 Android AVD만 목록에서 확인했다. 기존 APK와 과거 테스트 기록을 이번 변경의 검증 결과로 사용하지 않았다.
- Python `unittest discover -s backend` 5개 통과. 테스트 응답으로 방향·코드 매칭, 음식 이름·가격, 인증 누락, 오류 진단을 검증했다.
- 고캠핑 호스트 검증에서는 공식 API 연동 경로의 데이터를 확인했다. 시작한 서버의 `/health`는 키 설정 상태를 보고했고, 상위 셸의 환경변수는 확인 시 비어 있었다. 실행 환경에 인증값을 주입하는 방식은 확인하지 못했다. 검사 후 테스트 서버를 종료했다.
- Vehicle SDK 시뮬레이션과 Gleo 실제 음성 호출은 실행하지 않았다. 음식 API 테스트의 모의 응답은 시연 데이터로 사용하지 않았다.

## 시연 상태와 다음 순서

1. Android Studio에서 Debug 빌드, JVM·계측 테스트를 다시 실행한다. 같은 빌드로 홈 네 항목, 화면 전환, 버튼 가림, 앱 종료 여부를 확인한다.
2. 고캠핑 호스트를 재실행할 때 `GOCAMPING_SERVICE_KEY`가 서버 프로세스에 전달되는지 `/health`로 확인한다. 앱에서 경기·숲 5곳 추천, 시설·예약 정보, 장소 선택, 준비물, 여행 A/B 분리와 재실행 복원을 순서대로 검증한다.
3. Pleos 실행 대상을 연결하고 Virtual Console에서 SoC·용량 변경과 앱 반영, 미지원·연결 끊김, 설정 복원, NaviHelper 목적지 전달 및 Gleo 실제 음성 호출을 검증한다. 현재 소스의 에너지 화면과 내비 흐름은 기기 시연 검증을 기다리는 상태다.
4. 도로공사 INFO 및 FOOD 키와 해당 서비스 이용 권한을 설정하고 `python backend/expressway.py --check`를 실행한다. 음식 API의 이전 거절 원인은 실제 응답이 필요하다. 왕복 방향별 링크 근거가 확보될 때까지 경로상 휴게소는 확정하지 않는다.
5. 자연음 4종 및 스마트 모닝은 기존 구현을 유지했다. Android에서 재생·알람·앱 복귀를 확인한 뒤 시연 순서에 포함한다. 노지 차박 허용 자료는 관리기관 근거를 확보한 뒤 반영한다.

## 공식 참고 자료

- [Pleos Vehicle SDK 2.3.2 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)
- [Pleos Connect Virtual Console](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide)
- [한국관광공사 고캠핑 정보 조회](https://www.data.go.kr/data/15101933/openapi.do)
- [한국도로공사 휴게소 푸드메뉴현황 조회](https://www.data.go.kr/data/15076643/openapi.do)
