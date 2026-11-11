# CHABAK 기술 아키텍처

- **Android** — Kotlin/Compose 단일 :app 모듈. MainActivity가 시작·재사용 인텐트를 전달하고 AppState가 추천·여행·휴식 화면 상태를 관리한다.
- **고캠핑** — Android의 GoCampingCatalog → 에뮬레이터 호스트 10.0.2.2:8085 → Python server.py → 한국관광공사 API. 공식 원본 필드를 정규화하고 페이지 완료 후 캐시하며, 추천 근거는 등록 시설로 제한한다. 공급자 실패 시 오류 응답을 반환한다.
- **도로공사** — RestAreaCatalog → 10.0.2.2:8086 → expressway.py → INFO·FOOD. 휴게소 코드·노선·방향이 일치해야 후보가 되며 경로 링크 증거와 수집 시각을 함께 판단한다.
- **여행 저장** — TripStore가 SQLite 트랜잭션으로 장소·일정·체크리스트·왕복 이동·에너지 계획을 tripId별 저장한다. 설정과 알람에는 SharedPreferences를 사용한다.
- **Pleos** — NaviHelper 3.0.5의 위치·목적지·경로 콜백과 Vehicle SDK 2.3.2의 SoC·용량 조회를 별도 어댑터로 처리한다. TravelUxGate는 AAOS 차량 UI 제한을 확인한다.
- **휴식** — 자연음은 MediaPlayer와 Audio Focus를 사용한다. 스마트 모닝은 AlarmManager, 수신기, 미디어 재생 포그라운드 서비스로 이어진다. 화면 연출 제한은 알람 예약·음향 재생과 별도로 적용한다.
- **Gleo** — schema.json과 SEARCH_CAMPING_PLACE 인텐트, 응답 브로드캐스트로 연동한다. 내부 장소 검색과 같은 추천 경로를 재사용한다.

공식 자료 [NaviHelper 3.0.5](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/intro), [Vehicle SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/intro), [Gleo Callback](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback), [고캠핑](https://www.data.go.kr/data/15101933/openapi.do).
