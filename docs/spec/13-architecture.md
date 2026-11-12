# 13. 기술 아키텍처

## 13.1 구성도

```
[Android 앱 (Kotlin·Compose)]                       (추후 iOS 앱)
   │ REST / WebSocket(STOMP)                │ Presigned URL로 직접 업로드·다운로드
   ▼                                        ▼
[Nginx (TLS)] ─▶ [Spring Boot API 서버] ───▶ [S3 / MinIO (비공개 버킷)]
                    │   ├─▶ PostgreSQL 16 (+PostGIS, +pgvector)
                    │   └─▶ Redis 7 (Pub/Sub 중계, 캐시, 요청 제한, Streams 큐)
                    ├─▶ FCM (푸시)        ├─▶ 카카오 로그인·로컬 API     ├─▶ Google 로그인
                    ▼
               [Redis Streams] ──▶ [Python 워커 (FastAPI)]
                                     ├ ai: 인식·스토리·팸봇 (LlmClient → LLM API)
                                     ├ media: 썸네일·EXIF 정리·pHash·블러
                                     └ video: FFmpeg·librosa → S3 → (YouTube API, v1.2)
```

## 13.2 기술 스택

| 영역 | 선택 |
| --- | --- |
| 앱 | Kotlin, Jetpack Compose(Material 3), MVVM + Hilt, Coroutines/Flow, Retrofit·OkHttp·kotlinx.serialization, STOMP 클라이언트, Room, DataStore, WorkManager, Coil, Kakao Map SDK, Photo Picker, FCM, Credential Manager(Google 로그인), 카카오 SDK |
| 서버 | Java 21, Spring Boot 3, Spring Security(JWT), Spring WebSocket(STOMP), JPA + QueryDSL, Flyway, springdoc-openapi, Bucket4j(요청 제한), Testcontainers |
| 워커 | Python 3.12, FastAPI, Redis Streams consumer group, Pillow/OpenCV, imagehash, librosa, FFmpeg, pydantic(스키마 검증) |
| 데이터 | PostgreSQL 16 + PostGIS + pgvector, Redis 7, S3 호환 저장소 |
| 외부 | LLM API(미정, LlmClient로 분리), STT API(v1.1), 카카오, Google, YouTube Data API(v1.2) |
| 인프라 | MVP: 클라우드 VM 1대 + Docker Compose(API·워커·Postgres·Redis·Nginx) + S3. 성장 시 관리형 DB 분리 |
| CI/CD | GitHub Actions: 백엔드(테스트→이미지 빌드→배포), 모바일(빌드·테스트→Play 내부 테스트 트랙 업로드) |

## 13.3 저장소·패키지 구조

```
famlog/                          ← 문서 레포 (git)
├ CLAUDE.md                      공통 규칙
├ docs/spec/                     이 사양서의 Markdown 사본 (Claude Code가 읽는 기준)
├ docs/api/openapi.yaml          API 계약 (backend에서 생성해 커밋)
├ docs/design/                   figma-map.md, design-tokens.json
├ docs/handoff/HANDOFF.md        진행 현황
├ docs/runbooks/                 배포·백업·사고 대응
├ .gitignore                     famlog-backend/, famlog-mobile/ 제외
├ famlog-backend/                ← 별도 git 레포 (IntelliJ)
│  ├ CLAUDE.md
│  ├ api/   com.famlog.{auth, user, group, chat, media, schedule, todo, place,
│  │         candidate, report, notification, admin, common}
│  │         각 모듈: controller / service / domain / repository / dto
│  ├ worker/  app/{consumers, ai, media, video, prompts}, evals/
│  └ infra/   docker-compose.yml, nginx/, .env.example
└ famlog-mobile/                 ← 별도 git 레포 (Android Studio)
   ├ CLAUDE.md
   ├ app/
   ├ core/{model, network, database, datastore, designsystem, common}
   └ feature/{auth, group, chat, calendar, place, album, story, settings}
```

- `core/model`, 도메인·데이터 계층에는 Android 의존성(`Context`, `android.*`)을 넣지 않는다. iOS 확장 시 Kotlin Multiplatform 공유 모듈로 옮기거나 SwiftUI 앱이 같은 API 계약을 쓰게 하기 위해서다.
- 환경: `local`(Docker Compose) / `dev`(테스트 서버) / `prod`. 앱은 buildType·flavor로 서버 주소를 나눈다.
- 커밋 메시지: `feat(SCH-02): 구성원별 일정 조회 API`, 레포가 달라도 같은 기능 ID를 쓴다.
