# CLAUDE.md — FamLog (팸로그)

> Claude Code가 이 저장소에서 작업할 때 가장 먼저 읽는 파일이다.
> 응답과 커밋 메시지, 문서는 **한국어**로 작성한다. 코드 식별자는 영어.

## 1. 프로젝트 한 줄 요약

가족 전용 단체 채팅 + 구성원별 일정 + 맛집·장소 + 앨범을 하나로 묶고,
**채팅에서 일정·기념일·할 일·장소를 AI가 인식해 "등록 후보"를 만들어 주는 Android 가족 앱**.
Google Play 출시가 목표다 (v1.0 목표: 2027년 2월 중순).

## 2. 문서 우선순위 (충돌 시 위가 이긴다)

1. **통합 사양서 v2.0** — 개발 기준 문서. 기능 ID, API, 데이터, 보안, 인수 기준이 모두 여기 있다.
   - 로컬 사본: **`docs/spec/`** (장별 Markdown 21개). 색인·"기능 ID → 읽을 곳" 표는 `docs/spec/README.md`
   - 이 폴더는 사본이다. **여기서 직접 고치지 않는다.** 원본은 문서 레포(`yunseo-1/Famlog`)의 `docs/spec/`이고,
     사양 변경은 Notion(page id `416fc8bd-3e5e-8128-aab8-dc6d43bbd7f2`) → 문서 레포 → `scripts/sync-spec.sh` 순서로 반영한다.
   - 사양과 다르게 구현해야 하거나 사양이 애매하면 **구현 전에 질문**한다. 추측해서 채우지 않는다.
   - 경로 읽는 법: 사양서의 `famlog-backend/` = 이 저장소, `famlog-backend/api/` = 이 저장소 루트의 Spring 프로젝트(`src/`). `worker/`, `infra/`는 루트 아래에 만든다. `../docs/api/openapi.yaml` 등 문서 레포 경로는 문서 레포(`yunseo-1/Famlog`)를 뜻한다.
2. **Figma 화면 지도** — `docs/design/figma-map.md` (노드 ID, 화면 구조, 컴포넌트, 토큰)
3. **ERD 초안** (Notion page id: `40dfc8bd-3e5e-81f6-b5e9-e8e2a5e9c7dc`) — 참고용. 사양서 10장(데이터 구조)이 우선.
4. 인수인계 문서 — `docs/handoff/HANDOFF.md` (결정 로그, 알려진 불일치, 다음 작업)

## 3. 절대 규칙

- **AI는 임의로 등록하지 않는다.** 채팅 인식 결과는 `registration_candidates`(PENDING)에만 저장하고,
  실제 일정·할 일·장소는 `POST /api/v1/candidates/{id}/approve`에서만 만든다. (사양서 06)
  절차: 탐지 → 등록 후보 생성 → 근거 메시지 표시 → 사용자 확인/수정 → 승인 후 등록.
- **모든 그룹 데이터 접근은 `GroupAccessChecker`를 거친다.** 그룹 경로에 비구성원이 요청하면 403,
  다른 그룹 리소스 ID로 직접 요청하면 404(존재 여부 숨김). 테스트 필수. (사양서 09 §9.3)
- **1:1 채팅은 없다.** 가족 그룹당 단체 채팅방 1개 (`chat_rooms.group_id` UNIQUE).
- **현재 가족 그룹**: 캘린더·장소·앨범·이야기 탭은 현재 그룹 데이터만. 채팅 탭만 전체 그룹 목록.
- **날짜 계산·구성원 매칭·장소 확정은 서버 코드가 한다.** LLM은 원문 표현만 추출한다.
- 시간은 DB에 UTC로 저장, API 응답은 `+09:00` ISO-8601. 날짜 해석 기준은 근거 메시지 **발송 시각**(Asia/Seoul).
- 메시지 본문·토큰·좌표·출생연도월 등 개인정보를 로그에 남기지 않는다. LLM 프롬프트·응답 원문도 저장하지 않는다. (사양서 08, 09)
- AI 결과(후보 카드·스토리·영상)에는 "AI 제안/AI가 쓴 초안" 표시와 앱 안 [신고]를 둔다. (사양서 07 §7.2)
- 그룹 AI 분석이 꺼졌거나 개인이 분석 제외한 메시지는 LLM으로 보내지 않는다. (사양서 06 AR-10)
- 스키마 변경은 **Flyway 마이그레이션 파일**로만. 직접 DDL 금지.
- 새 라이브러리 추가 전에 이유를 적고 사용자에게 확인받는다.
- 앱 UI는 `docs/design/` 의 토큰·컴포넌트 이름을 따른다. 임의 색상(hex) 하드코딩 금지 → `FamColors.*`.

## 4. 기술 스택 (사양서 11장)

| 영역 | 선택 |
|---|---|
| 앱 | Kotlin, Jetpack Compose, MVVM + Hilt, Retrofit/OkHttp, STOMP, Room, Coil, Kakao Map SDK, Photo Picker, FCM. minSdk 26 / **targetSdk 36** |
| 서버 | Java 21, Spring Boot 3, Spring Security(JWT), WebSocket(STOMP), JPA + QueryDSL, Flyway |
| 워커 | Python 3.12, FastAPI, Redis Streams consumer (AI 인식·미디어·영상) |
| 데이터 | PostgreSQL 16 + PostGIS + pgvector, Redis 7, S3(로컬은 MinIO) |
| 인프라 | Docker Compose (MVP), GitHub Actions |

## 5. 저장소 구조

**이 저장소(`Famlog-BE`)는 백엔드 전용이다.** Android 앱은 별도 저장소에서 Android Studio로 작업한다.
`docs/design/`(Figma 지도·토큰·Compose 테마 초안)은 API 응답 형태와 화면 흐름을 맞추기 위한 참고 자료로만 둔다.

```
Famlog-BE/
├ src/main/java/com/famlog/{auth,user,group,chat,schedule,todo,place,candidate,media,
│                           album,video,story,assistant,notification,common}
│                           각 모듈: controller / service / domain / repository / dto
├ src/main/resources/db/migration/   Flyway (V1__*.sql …)
├ worker/    (예정) Python 워커 app/{consumers,ai,media,video,prompts}, evals/
├ infra/     (예정) docker-compose.yml, .env.example
└ docs/      spec/, design/, handoff/, adr/
```

(Android 앱 구조 참고: app + core/{network,database,designsystem} + feature/{chat,calendar,place,album,story,assistant,settings})

## 6. 작업 방식

- **기능 ID 하나씩** 작업한다 (예: `SCH-02`). 시작할 때 **`/feature SCH-02`** 명령을 쓴다.
  사양서 04 기능 요구사항 → 10 데이터 → 11 API → 18 인수 기준(자동 등록은 06 §6.5) 순서로 같은 ID를 읽는다.
- 요청 순서와 단계별 프롬프트는 사양서 `docs/spec/20-vibe-coding.md`에 있다.
- 순서: 서버 API + 테스트 → 인수 기준(서버 범위) 확인 → 커밋. 앱 화면은 Android 저장소에서 따로 한다.
- 화면 작업 시: 먼저 `docs/design/figma-map.md`와 `docs/design/screenshots/`를 본다.
  Figma MCP는 **Starter 요금제라 호출 한도가 작다.** 로컬 문서로 부족할 때만 특정 노드 ID 하나에 대해
  `get_design_context` / `get_screenshot`을 호출한다. 파일 전체를 훑는 호출은 하지 않는다.
- 커밋 메시지: `feat(SCH-02): 구성원별 일정 조회 API` 형식.
- 작업이 끝나면 `docs/handoff/HANDOFF.md`의 "진행 현황"을 갱신한다.

## 7. 자주 쓰는 명령 (W1에 실제 값으로 채울 것)

```bash
# 인프라
docker compose -f infra/docker-compose.yml up -d
# 서버
./gradlew test && ./gradlew bootRun
```
