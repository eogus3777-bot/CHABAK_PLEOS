# 여행 주변 장소 데이터 (STEP 261–300)

`app/src/main/assets/trip_places.json`은 공식 공개자료를 내려받아 만든 **시점별 저장자료**입니다. 실시간 장소 검색 API나 학습 모델의 추천 결과가 아닙니다. APK에 포함하므로 별도 장소 서버 없이 검색할 수 있습니다.

- 한국농수산식품유통공사 찾아가는양조장정보: <https://www.data.go.kr/data/15048756/fileData.do>
  - 2026-07-31 기준 원자료 64행. 이용허락범위 제한 없음으로 등록된 자료입니다.
  - CSV에 좌표가 없습니다. 좌표를 추정하거나 주소 중심점으로 대체하지 않습니다.
  - 산사원만 포천시 관광 원문의 방문지 주소·지도 좌표를 대조했습니다. 나머지 63곳은 좌표가 없으므로 거리 추천과 내비 연결 대상에서 제외합니다.
  - 방문 가능 여부는 원자료에 등록된 값입니다. 현재 영업시간, 체험 운영, 예약 가능 여부를 보장하지 않습니다.
- 전통시장 표준데이터: <https://www.data.go.kr/data/15012894/standard.do>
  - 공개 표준조회 페이지 499행 중 해석 가능한 장날과 좌표가 있는 204행을 사용합니다. 전국 전체 자료를 확보했다는 뜻이 아닙니다.
  - `1·6일`은 1·6·11·16·21·26일로 해석하며, 31일을 임의로 추가하지 않습니다. 명시된 날짜 목록에 31일이 있으면 해당 월에 실제로 존재하는 경우만 계산합니다.
  - 휴장, 임시 개장, 주말 변경은 검증하지 않았습니다. 계산 결과는 등록 장날 후보입니다.

## 갱신

프로젝트 루트에서 `python backend/refresh_trip_places.py`를 실행합니다. 인증키를 요구하지 않는 공개 다운로드만 사용합니다. 포털이 인증·CAPTCHA를 요구하거나 자료 형식이 달라지면 중단하며 기존 저장자료를 유지합니다. 새 자료를 검토한 후 APK를 다시 빌드해야 반영됩니다. 출처의 이용 조건과 현재 영업 정보도 갱신 시 다시 확인하세요.

선택한 캠핑장의 공식 좌표를 기준으로 직선거리 50km 이내, 최대 5곳을 표시합니다. 오일장은 조회 기간에 장날이 맞는 장소를 우선하며, 날짜를 명시한 음성 인터페이스와 주말 검색은 일치하는 날짜만 포함합니다. 선택한 여행의 날짜가 기본값이며, 일요일의 ‘이번 주말’은 그 일요일입니다.

## Gleo 인터페이스

기존 `SEARCH_CAMPING_PLACE`를 보존하고, `SEARCH_TRIP_PLACES`와 `MANAGE_CAMPING_TRIP`을 더해 일반 Schema의 Action 3개 제한을 지킵니다.

- `SEARCH_TRIP_PLACES`: `category` = `REST_AREA`, `BREWERY`, `MARKET`; 선택 인자 `date`(ISO 날짜) 또는 `weekend`(Boolean).
- `MANAGE_CAMPING_TRIP`: `operation` = `START_STAY`, `READ_ENERGY`.
- JSON 또는 JSON 문자열로 인코딩된 `ACTION_PARAM`, 개별 타입 Extra를 지원합니다. 잘못된 타입·날짜와 과도한 입력을 거부합니다.
- 응답은 기존 Broadcast 계약을 유지합니다. `functionName/status/message/data`를 보내며 `data`는 JSON 배열을 인코딩한 문자열입니다. 성공, 추가 인자 필요, 결과 없음, 경로 미확인, 서비스 오류를 구분합니다.

ADB Intent는 실제 Gleo 음성 인식·Action 선택·응답 발화 검증을 대신하지 않습니다. Schema는 Gleo 공식 등록·검토 절차를 거쳐야 합니다. 서비스 인증 또는 계정 설정을 우회하지 않습니다.

## 휴게소와 내비

기존 `backend/expressway.py`(8086)를 사용합니다. 환경변수는 `CHABAK_EXPRESSWAY_INFO_KEY`, `CHABAK_EXPRESSWAY_FOOD_KEY`이며 공통 키는 기존 `CHABAK_EXPRESSWAY_API_KEY`로 설정할 수 있습니다. 값은 로그나 소스에 기록하지 마세요. `/health`의 설정 여부는 API 조회 성공을 의미하지 않습니다. `python backend/expressway.py --check`는 키를 출력하지 않고 오류 종류만 점검합니다.

FOOD 결과에는 `foodStatus`를 추가했습니다. `empty`는 정상 조회 후 대표 음식이 없는 경우이고, `error`는 인증·공급자 오류 등이며 `foodError`에 정제된 진단을 제공합니다. 기존 `foods` 배열과 INFO 응답 계약은 유지합니다.

진행 방향과 진입·진출 링크가 확인되지 않으면 가까운 휴게소를 경로상 장소로 추천하지 않습니다. 현재 NaviHelper에서 확인된 경로 정보에는 이 링크가 없으므로 ‘경로상 확인 필요’로 표시합니다. 인증키가 없는 실행 환경에서도 임시 자료를 제공하지 않습니다.

장소 내비 연결은 공식 좌표가 있는 후보만 사용하며, 사용자 확인 후 **목적지를 변경**합니다. 경유지 추가 또는 도착으로 처리하지 않습니다. 캠핑장 선택, 여행 좌표, SQLite 경로 기록을 이 동작으로 덮어쓰지 않습니다. 차량 화면 조작 허용 여부를 다시 검사하고, 기존 `PleosTripNavigation`의 목적지 일치·안내 상태 검증을 재사용합니다.

## 공식 인터페이스 문서

- <https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability>
- <https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback>
- <https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples>
- <https://document.pleos.ai/en/docs/pleos-only/app-integration-guide/guide/deliver-schema>
