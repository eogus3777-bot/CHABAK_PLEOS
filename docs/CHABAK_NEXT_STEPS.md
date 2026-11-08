# 공모전 전 개발 우선순위

1. **P0 빌드 복구** — 사용자 Android Studio의 정상 실행 환경에서 Gradle Sync, Debug APK, JVM 및 계측 APK를 확인한다. 이 작업의 소스 변경은 Git 권한이 정상인 환경에서 로컬 커밋한다.
2. **P0 Pleos 실행 준비** — SDK Manager에서 공식 시스템 이미지 사이트를 추가하고 API 34 Pleos Connect 2.1.0 AVD를 Cold boot로 시작한다. ADB 대상과 앱 로그를 확인한다. [공식 설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)
3. **P0 실데이터 및 저장 복원** — 호스트 서버의 인증 상태를 확인한 뒤 공식 추천과 앱 결과를 대조하고 여행 A/B, 마이그레이션, 재시작, 화면 전환을 검증한다.
4. **P1 차량 기능** — NaviHelper 목적지와 왕복 상태, Virtual Console SoC·용량, 앱 주행 제한, 자연음·모닝 알람, Gleo ADB를 검증한다. 시뮬레이션과 실차 검증을 구분한다.
5. **P2 데이터 확장 및 시연 판정 갱신** — 도로공사 INFO·FOOD 권한과 실제 응답, 경로 방향 근거, 노지 관리기관 증빙을 확인한다. 핵심 흐름이 안정된 뒤 지역 경험을 검토한다.
