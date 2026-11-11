# CHABAK 테스트 보고

| 검사 | 실행 결과 |
| --- | --- |
| Python 백엔드 | python -B -m unittest discover -s backend -v, 8개 통과 |
| Kotlin JVM | TripEnergyPlanTest 및 ExampleUnitTest를 JVM 컴파일러와 JUnit 4.13.2로 직접 실행, 5개 통과 |
| Kotlin·Compose 소스 | Kotlin 2.2.10과 Compose 컴파일러, 캐시된 Android·Pleos 의존성 및 기존 R.jar로 앱 18개와 계측 소스 6개 직접 컴파일 성공 |
| Gradle | assembleDebug, testDebugUnitTest, assembleDebugAndroidTest가 원본·검증 사본 모두 구성 단계에서 실패. APK 패키징 및 의존성 충돌 보고 미완료 |
| Android 계측·Compose UI·E2E | ADB 연결 대상 없음. 계측 테스트 소스 컴파일만 확인 |
| 고캠핑 HTTP | 8085에서 키 상태와 응답 간 불일치. 공식 실데이터 재검증 보류. 독립 로컬 포트의 무키 테스트에서 health 200, camps와 recommend 503 |
| 도로공사 | python -B backend/expressway.py --check에서 INFO·FOOD key_missing |
| Pleos SDK와 Gleo | 공식 이미지 피드 접근 성공. AVD, 실차, 실제 음성, 최신 APK ADB 호출 실행하지 못함 |

Gradle 재현은 프로젝트에서 gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest를 실행한다. 전역 캐시 잠금 접근 오류가 있어 별도 캐시로 검사했고, Java 디렉터리 쓰기 조회가 false를 반환했다. 동일 Java 프로세스는 임시 파일 생성·삭제에 성공했다. 실행 환경과 Java 쓰기 판정의 불일치까지 확인했으며 Windows ACL은 변경하지 않았다.

직접 컴파일에서는 시스템 바 색상 API, 알림 액션 생성자, Vehicle 콜백 매개변수 이름, 테스트의 혼합 배열 타입 추론 경고가 확인됐다. APK 및 실행 동등성은 Gradle·기기 검증이 필요하다.
