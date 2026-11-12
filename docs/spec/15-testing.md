# 15. 테스트 전략

| 계층 | 도구 | 대상·기준 |
| --- | --- | --- |
| 서버 단위 | JUnit 5, AssertJ | 날짜 정규화(30케이스+), 권한 검사, 후보 상태 전이, dedupKey |
| 서버 통합 | Spring Boot Test + Testcontainers(PostGIS, Redis) | API별 정상·권한·검증 실패, 트랜잭션(승인 동시성), Flyway 마이그레이션 |
| 보안 | 통합 테스트 + 스크립트 | 9.9 SEC-T1\~T10, 엔드포인트 목록 기반 IDOR 자동 테스트 |
| 워커 | pytest | Streams 소비·재시도·DLQ, 마스킹, 스키마 검증 |
| AI 평가 | evals 러너 | 수락률 대신 정답 라벨 일치율, 6.4 비생성 케이스, 프롬프트 변경 시 필수 실행 |
| 앱 단위 | JUnit, Turbine, MockK | ViewModel 상태, Repository 캐시·재전송 로직 |
| 앱 UI | Compose UI Test | 로그인→그룹→채팅→후보 승인 핵심 흐름, 글자 200% |
| 부하 | k6 | 동시 접속 200, 메시지 초당 50개에서 p95 확인 (출시 전 1회) |
| 수동 | 실기기 2대 이상 | 푸시, 오프라인 복구, Photo Picker EXIF, 저사양 기기 |

핵심 서비스(auth, group, chat, candidate) 라인 커버리지 70% 이상을 출시 기준으로 둔다.
