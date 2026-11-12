# 20. 바이브 코딩 가이드: 요청 순서와 프롬프트

> **핵심 규칙 5가지**
>
> ① 한 번에 기능 ID 하나만 맡긴다. ② 코드 전에 계획을 먼저 받는다(Plan 모드). ③ 테스트를 먼저 또는 함께 만든다. ④ 사양서 사본(`docs/spec/`)을 근거로 삼게 하고, 사양과 다르게 해야 하면 먼저 묻게 한다. ⑤ 기능 하나가 끝나면 직접 확인 → 커밋 → HANDOFF 진행 현황 기록 → `/clear`로 대화를 비운다.

## 20.1 작업 루틴

| 순서 | 할 일 | 팁 |
| --- | --- | --- |
| 1 | 해당 레포 폴더에서 Claude Code 실행 | backend는 IntelliJ 터미널, mobile은 Android Studio 터미널 |
| 2 | Plan 모드(Shift+Tab)로 단계 프롬프트 전달 | 계획에 파일 목록·테스트 목록이 있는지 확인 후 승인 |
| 3 | 구현 → 테스트 실행 결과를 보여달라고 요청 | "테스트가 통과했다"는 말이 아니라 실행 출력으로 확인 |
| 4 | diff 검토 | 범위 밖 파일 수정, 새 라이브러리, 하드코딩된 비밀이 없는지 |
| 5 | 인수 기준을 직접 확인 (앱은 실기기·에뮬레이터) | 18장 해당 ID |
| 6 | 커밋: `feat(ID): 요약` | 레포마다 같은 기능 ID |
| 7 | `famlog/docs/handoff/HANDOFF.md` 진행 현황 갱신, `/clear` | 다음 기능은 새 대화로 |

## 20.2 공통 프롬프트 템플릿

```
[기능] {기능 ID} {기능 이름}
[참고] docs/spec/ 사양서 {절 번호} (기능 요구사항, 데이터, API, 인수 기준)
[범위] {모듈/패키지}만 수정. 다른 모듈 수정이 필요하면 먼저 물어볼 것
[규칙] CLAUDE.md 준수. 새 라이브러리는 이유를 말하고 승인받을 것
[완료 조건] 인수 기준 테스트 + 다른 그룹 접근 거부 테스트 통과, 테스트 실행 결과를 보여줄 것
먼저 구현 계획(변경 파일, 테스트 목록)을 보여주고 승인 후 진행해줘.
```

## 20.3 CLAUDE.md에 넣을 규칙

### famlog/CLAUDE.md (공통)

```
# FamLog 공통 규칙
- 응답과 주석은 한국어. 사양 기준은 docs/spec/ (원본: Notion 통합 사양서 v2.0)
- 구조: famlog/(문서) 안에 famlog-backend/, famlog-mobile/ 별도 git 레포
- API 계약은 docs/api/openapi.yaml. 계약을 바꾸면 backend에서 재생성해 커밋
- 기능 ID 단위 작업, 커밋 메시지 feat(ID): 요약
- AI 결과는 registration_candidates(PENDING)에만 저장, 실제 항목은 승인 API에서만 생성
- 메시지 본문·토큰·좌표·출생연도월을 로그에 남기지 않는다
- 비밀 값을 코드·문서에 쓰지 않는다 (.env, Secrets 사용)
- 날짜 예시는 실제 요일을 확인한다
```

### famlog-backend/CLAUDE.md

```
# Backend 규칙
- Java 21, Spring Boot 3, 패키지 com.famlog.{모듈}.{controller|service|domain|repository|dto}
- 빌드/테스트: ./gradlew test (Testcontainers 사용, Docker 필요)
- 스키마 변경은 Flyway(V{n}__설명.sql)로만. 기존 마이그레이션 파일 수정 금지
- 모든 그룹 데이터 접근은 GroupAccessChecker 경유. 다른 그룹 리소스는 404
- 시간은 timestamptz(UTC) 저장, 응답은 +09:00
- 에러는 { code, message, traceId } 형식, 코드 목록은 사양서 11.2
- 새 API마다 정상/검증 실패/권한 거부 테스트 3종 필수
- worker(Python)는 worker/ 아래. pytest, pydantic 스키마 검증, LLM은 LlmClient 경유
```

### famlog-mobile/CLAUDE.md

```
# Mobile 규칙
- Kotlin, Compose(Material 3), MVVM + Hilt, 모듈: app, core/*, feature/*
- 빌드/테스트: ./gradlew assembleDebug testDebugUnitTest
- UI는 core/designsystem의 FamTheme·토큰만 사용 (하드코딩 색 금지). 코랄은 행동 요소에만
- 카테고리 색: 일정 #2F6FED, 기념일 #C2255C, 장소 #1F9D6B, 할 일 #7A4FE0
- core/model·domain에는 Android 의존성 금지 (iOS 대비)
- 토큰은 Keystore 암호화 DataStore, 로그 출력 금지
- 사진은 Photo Picker만, 위치는 포그라운드·대략적 위치 우선
- 글자 크기 200%, TalkBack contentDescription 확인
```

## 20.4 요청 순서 한눈에 보기

| 단계 | 내용 | 레포 | 주차 |
| --- | --- | --- | --- |
| S0 | 문서 레포 정리, CLAUDE.md 3종, 사양서 사본 | famlog | W1 |
| S1 | 로컬 인프라 (Docker Compose) | backend | W1 |
| S2 | 서버 골격·공통 (에러, 로깅, Flyway V1, 테스트 기반) | backend | W1 |
| S3 | 보안 골격 (JWT, GroupAccessChecker, 요청 제한) | backend | W1 |
| S4 | AUTH-01\~03 로그인·연령·세션 | backend | W2 |
| S5 | GRP-01\~05 그룹·초대·역할 | backend | W2\~3 |
| S6 | 앱 골격 (모듈, 테마, 하단 탭, 네트워크, Photo Picker EXIF 검증) | mobile | W1 |
| S7 | 앱 로그인·그룹 화면 | mobile | W3 |
| S8 | CHAT 서버 (STOMP, seq, 읽음) | backend | W4 |
| S9 | 미디어 업로드 + 워커 썸네일·EXIF 정리 | backend | W4 |
| S10 | 앱 채팅 화면 (Room, 오프라인 재전송) | mobile | W5 |
| S11 | FCM 푸시 | both | W5 |
| S12 | 일정·할 일·공휴일 서버 | backend | W6 |
| S13 | 캘린더 앱 | mobile | W6 |
| S14 | 장소 서버 (카카오 로컬, 지역, PostGIS) | backend | W7 |
| S15 | 장소 앱 (목록·지도) | mobile | W7 |
| S16 | 워커 인식 파이프라인 골격 + 규칙 필터 | backend | W8 |
| S17 | 날짜·구성원 정규화 (테스트 우선) | backend | W8 |
| S18 | LLM 추출 + 후보 생성 + 평가셋 | backend | W8\~9 |
| S19 | 후보 API + 앱 후보 카드·수정 시트 | both | W9 |
| S20 | 설정·동의·계정 삭제·내보내기·신고·기기 관리 | both | W10 |
| S21 | 보안 테스트·관측·백업 | backend | W10 |
| S22 | 출시 준비 (정책 페이지, 릴리스 빌드, 내부 테스트 트랙) | both | W10\~11 |

## 20.5 단계별 프롬프트

### S0. 문서 레포 정리

**작업 위치:** `famlog/`

```
famlog/ 를 문서 레포로 정리해줘.
1) docs/spec/ 에 통합 사양서 v2.0을 장별 Markdown 파일로 저장 (01-overview.md ~ 20-vibe-coding.md).
   원본은 내가 준 docx(또는 Notion)이고, 표는 Markdown 표로 바꿔줘.
2) 사양서 20.3의 내용으로 famlog/CLAUDE.md 를 만들고,
   famlog-backend/CLAUDE.md, famlog-mobile/CLAUDE.md 초안도 같은 내용으로 만들어줘.
3) .gitignore 에 famlog-backend/, famlog-mobile/ 추가.
4) docs/handoff/HANDOFF.md 의 W1 체크리스트를 사양서 17.2·20.4 기준으로 갱신.
파일 목록을 먼저 보여주고 진행해줘.
```

**확인:** docs/spec 파일이 장별로 있고, 바깥 레포 git status에 하위 레포가 안 보인다.

### S1. 로컬 인프라

**작업 위치:** `famlog-backend/infra/`

```
사양서 13장을 참고해 로컬 개발용 infra/docker-compose.yml 을 만들어줘.
- postgres: PostGIS + pgvector 확장이 가능한 이미지 (확장 생성 init SQL 포함)
- redis 7, minio(+버킷 자동 생성: famlog-media, 비공개)
- 모든 비밀은 .env 에서 읽고 .env.example 만 커밋
- DB·Redis·MinIO 포트는 127.0.0.1 에만 바인딩
README에 실행·초기화 방법을 적고, docker compose up 후 확장이 설치됐는지 확인하는 명령도 알려줘.
```

**확인:** `CREATE EXTENSION postgis, vector`가 확인되고 MinIO 콘솔에 버킷이 있다.

### S2. 서버 골격·공통

**작업 위치:** `famlog-backend/api/`

```
Spring Boot 3 + Java 21 프로젝트 골격을 만들어줘. (사양서 11.1, 11.2, 9.8, 13.3)
- 패키지 com.famlog.common: 공통 에러 응답 {code,message,traceId}, ErrorCode enum(11.2 전체),
  전역 예외 처리, traceId MDC 필터, 로그 마스킹(메시지 본문·토큰 필드)
- Flyway V1: users, social_accounts, user_sessions, device_tokens, consents (사양서 10.2 컬럼)
- 설정 프로필 local/dev/prod, springdoc-openapi, Actuator(health만 공개)
- Testcontainers(PostGIS) 기반 통합 테스트 베이스 클래스
- GitHub Actions: PR마다 ./gradlew test
계획 먼저 보여줘.
```

**확인:** `./gradlew test` 통과, /v3/api-docs 응답.

### S3. 보안 골격

**작업 위치:** `famlog-backend/api/`

```
사양서 9.2, 9.3, 9.6 기준으로 보안 골격을 만들어줘.
- JWT 발급·검증 (Access 30분, kid 포함 비대칭 서명), Refresh는 무작위 문자열 + 해시 저장,
  재발급 시 교체, 폐기된 토큰 재사용 감지 시 사용자 전 세션 폐기
- GroupAccessChecker: requireMember(groupId, userId), requireRole(groupId, userId, role)
  (그룹 테이블은 다음 단계에서 만들 테니 인터페이스와 테스트 더블로 먼저)
- Bucket4j + Redis 요청 제한 (사양서 9.6의 수치), 초과 시 429 RATE_LIMITED
- 테스트: 만료/위조 토큰 401, 재사용 감지, 요청 제한
```

**확인:** SEC-T3 해당 테스트 통과.

### S4. AUTH-01~03

**작업 위치:** `famlog-backend/api/`

```
[기능] AUTH-01 소셜 로그인, AUTH-02 연령 확인, AUTH-03 로그인 기기 관리
[참고] 사양서 4.1, 9.2, 10.2(users, social_accounts, user_sessions, consents), 11.3, 18.1
- POST /auth/{provider}/login: 카카오는 액세스 토큰으로 사용자 정보 API 검증,
  Google은 ID 토큰 서명·aud·iss·exp 검증. provider enum에 APPLE 미리 포함(미구현)
- 이메일·실명은 저장하지 않는다. 신규 가입 시 필수 동의(약관·개인정보) 버전 기록
- 출생연도·월로 만 14세 미만이면 가입 거부
- 세션 목록·원격 로그아웃 API
- 외부 API 호출은 인터페이스로 분리하고 테스트에서는 가짜 구현 사용
```

### S5. GRP-01~05

**작업 위치:** `famlog-backend/api/`

```
[기능] GRP-01~05 가족 그룹
[참고] 사양서 2장, 4.1, 10.2(group_members, group_invites), 11.3, 18.1
- 그룹 생성 시 OWNER + 채팅방 자동 생성, 그룹당 20명·사용자당 10개 제한
- 초대 코드 8자리(0/O/1/I 제외), 7일 유효, 재발급 시 이전 코드 만료, 5회 실패 10분 잠금
- 역할·호칭·캘린더 색 수정, 내보내기, 권한 변경, OWNER 위임, 나가기
- 현재 그룹 저장 PUT /users/me/current-group
- S3의 GroupAccessChecker를 실제 구현으로 교체
- 테스트: 권한 매트릭스(2.2) 전체, 다른 그룹 접근 거부
```

### S6. 앱 골격

**작업 위치:** `famlog-mobile/`

```
Android 앱 골격을 만들어줘. (사양서 3.1, 12장, 13.2, 13.3, famlog-mobile/CLAUDE.md)
- 모듈: app, core/{model, network, database, datastore, designsystem, common}, feature/*(빈 화면)
- minSdk 26, targetSdk 36, Compose Material 3, Hilt, edge-to-edge, 예측형 뒤로가기
- core/designsystem: famlog/docs/design 의 design-tokens.json 과 FamTheme.kt 를 옮겨 테마 구성
- 하단 탭 5개(채팅·캘린더·장소·앨범·이야기), 앱 바 "우리 가족 ▾" 자리
- Retrofit + 인증 인터셉터(401 시 refresh 1회 재시도), Keystore 암호화 DataStore 토큰 저장
- 디버그 화면 하나: Photo Picker로 사진을 고르고 EXIF 촬영 시각·GPS가 읽히는지 표시
  (ACCESS_MEDIA_LOCATION 필요 여부 포함해서 결과를 정리해줘)
```

**확인:** 에뮬레이터 실행, 실기기에서 EXIF 검증 결과를 HANDOFF에 기록.

### S7. 앱 로그인·그룹 화면

**작업 위치:** `famlog-mobile/feature/auth, group`

```
[기능] SCR-01~04, SCR-70 (AUTH-01·02, GRP-01~04 앱 화면)
[참고] 사양서 3.2, 4.1, docs/api/openapi.yaml, docs/design/figma-map.md
- 카카오 SDK·Credential Manager(Google) 로그인 → 서버 로그인 API
- 약관 동의(필수/선택 분리), 출생연도·월 입력
- 그룹 만들기 / 초대 코드 입력 / App Links 초대 링크 처리
- 역할·호칭·색 설정, 그룹 전환 바텀시트
- ViewModel 단위 테스트, 글자 200%에서 레이아웃 확인 스크린샷
```

### S8. CHAT 서버

**작업 위치:** `famlog-backend/api/chat`

```
[기능] CHAT-01~04, CHAT-06
[참고] 사양서 4.2, 10.2(messages), 11.3, 11.4, 18.1(CHAT-02)
- STOMP: CONNECT 시 JWT, SUBSCRIBE·SEND마다 멤버십 검사
- 방별 seq 부여(동시성 안전하게), (sender_id, client_message_id) 멱등 처리
- 서버 간 중계는 Redis Pub/Sub 구조로 (지금은 1대지만 구조는 맞춰서)
- afterSeq/beforeSeq 페이징, 읽음 lastReadSeq, 메시지별 안 읽은 수 계산
- 답장·공감·5분 이내 삭제
- 테스트: 중복 전송 1건만 저장, 순서 보장, 다른 그룹 구독 거부(SEC-T4)
```

### S9. 미디어 업로드

**작업 위치:** `famlog-backend/api/media`, `worker/`

```
[기능] CHAT-05 사진·동영상 전송 (서버·워커)
[참고] 사양서 4.2, 8.6, 9.1, 10.2(media_assets)
- POST /media/upload-urls: 개수·크기·MIME 검사 후 10분 Presigned PUT URL, 객체 키는 UUID
- POST /media/{id}/complete: 매직 바이트 검사 → Redis Streams 'media' 이벤트 발행
- 워커: 썸네일 3단계 생성, 동영상 미리보기 이미지, EXIF 촬영 시각 추출,
  GPS 태그 제거한 원본으로 교체, 사용자가 위치 사용에 동의한 경우에만 location 저장
- 실패 재시도 3회 후 DLQ
- 테스트: 위장 파일 거부, 처리 후 원본에 GPS 없음(SEC-T6)
```

### S10. 앱 채팅 화면

**작업 위치:** `famlog-mobile/feature/chat`

```
[기능] SCR-10, SCR-11, SCR-12 (CHAT-01~06 앱)
[참고] 사양서 3.2, 4.2, docs/design/figma-map.md (Chat Bubble, Chat Input 컴포넌트)
- 그룹 목록(마지막 메시지·안 읽은 수), 채팅방, 서랍
- Room 캐시 + 재접속 시 afterSeq 동기화, 전송 대기열(WorkManager)로 오프라인 재전송
- Photo Picker 다중 선택(30개) → 업로드 → 묶음 표시, 썸네일 먼저
- 답장·공감·삭제, 메시지 길게 눌러 [신고] 자리 만들기(API는 S20)
- 후보 카드가 들어올 자리(message.type=CANDIDATE)만 만들어 두기
```

### S11. FCM 푸시

**작업 위치:** backend + mobile

```
[기능] NOTI-01, SET-02 일부
- backend: 기기 토큰 등록/삭제, 새 메시지 푸시(본문 미리보기는 사용자 설정 따름), 그룹 묶음
- mobile: 알림 채널 4개(채팅/일정/AI 제안/시스템), Android 13+ 알림 권한 요청 흐름(SCR-04),
  알림 탭 시 해당 그룹·메시지로 이동
- 방해 금지 시간에는 무음 처리
```

### S12. 일정·할 일 서버

**작업 위치:** `famlog-backend/api/schedule, todo`

```
[기능] SCH-01~04, TODO-01 (TODO-02, 음력은 컬럼만)
[참고] 사양서 4.3, 10.2(schedules, schedule_participants, todos, holidays), 11.3, 18.1(SCH-02)
- 반복은 RRULE 저장, 조회 기간 안에서 서버가 전개해 반환
- 구성원·역할 필터(memberIds, roles 여러 개), 참여 구성원 기준
- 리마인드 스케줄러(전날 20:00, 1시간 전), 공휴일 테이블과 조회 API(초기 데이터 마이그레이션)
- 일정 겹침 조회(SCH-05)는 등록 화면용 API로
- 낙관적 잠금 version
```

### S13. 캘린더 앱

**작업 위치:** `famlog-mobile/feature/calendar`

```
[기능] SCR-20, SCR-21 (SCH-01~05, TODO-01 앱)
- 월·주·목록 보기, [전체] + 구성원 칩 다중 선택 필터, 구성원 색 점 + 이름(색만으로 구분 금지)
- [일정 | 할 일] 전환, 공휴일 빨간 날 표시
- 일정 등록 폼: 참여 구성원, 장소 연결, 반복, 알림, 겹침 안내
- 인수 기준 SCH-02 시나리오를 UI 테스트로
```

### S14. 장소 서버

**작업 위치:** `famlog-backend/api/place`

```
[기능] PLC-01~06
[참고] 사양서 4.4, 8.6, 10.2(places, place_aliases), 11.3, 18.1(PLC-02·03)
- 카카오 로컬 검색 프록시 /places/search (키는 서버에만), 결과 캐시
- 주소에서 region1/2/3 추출, geom 저장(PostGIS), 지역별 개수 API
- 목록 필터: region1, region2, sharedBy, category 조합
- nearby: 좌표는 저장·로그 금지, 기능 플래그로 끌 수 있게, location_access_logs 기록
- 별칭 장소 CRUD
- 카테고리 변환표는 설정 파일로
```

### S15. 장소 앱

**작업 위치:** `famlog-mobile/feature/place`

```
[기능] SCR-30~32 (PLC-01~06 앱)
- 장소 검색 → 공유(카테고리·한줄평·별점) → 채팅방에 장소 카드
- 목록: 시·도 → 시·군·구 선택(개수 배지), 구성원 칩, 카테고리 필터
- 지도: Kakao Map SDK(16KB 페이지 호환 버전 확인), 화면 영역 안 핀
- 내 주변: 위치 동의(위치기반서비스 약관) 후 대략적 위치 권한, 플래그 꺼지면 버튼 숨김
```

### S16. 인식 파이프라인 골격

**작업 위치:** `famlog-backend/worker/`, `api/candidate`

```
[기능] ARC-01 대화 분석 트리거
[참고] 사양서 6.2 ① 탐지, 7.2, 8.7, 9.7
- api: 메시지 저장 시 규칙 필터(정규식 사전) 적용, 그룹 AI 꺼짐·개인 제외면 큐에 넣지 않음
- 30초 멈춤 디바운스 + 미분석 30개/10분 보조 트리거 → Redis Streams 'ai.extract'
- worker: 소비자 그룹, 재시도 3회·DLQ, LlmClient 인터페이스(가짜 구현 포함)
- 전송 전 마스킹과 가명 처리(M1, M2…) 함수 + 테스트
- 아직 LLM은 호출하지 말고 가짜 응답으로 끝까지 흐르게
```

**확인:** AR-10: 분석 제외 메시지는 큐에 없다.

### S17. 날짜·구성원 정규화 (테스트 우선)

**작업 위치:** `famlog-backend/api/candidate`

```
[기능] ARC-02·04 정규화
[참고] 사양서 6.2 ② 정규화 규칙 표, 6.3 예시, 6.5 AR-9
먼저 테스트만 작성해줘(구현 없이). 기준 시각이 주어지면 표현을 절대 날짜로 바꾸는 함수:
- 이번/다음 주 요일, 내일/모레/글피, "15일", 이번 주에/주말까지/이번 달 안에
- 오전·오후 생략 규칙, 아침·저녁 단서
- 월말·연말 경계: 2026-12-31(목) "다음 주 월요일" → 2027-01-04
- 6.3 예시 5개 (기준 2026-11-12 목)
30개 이상 케이스를 보여주고 내가 승인하면 구현해줘.
구성원 매칭(family_role, family_title, 같은 역할 여러 명, "우리" → 전체 추정)도 같은 방식으로.
```

### S18. LLM 추출 + 후보 생성

**작업 위치:** `worker/`, `api/candidate`

```
[기능] ARC-02~04, ARC-07 후보 생성
[참고] 사양서 6.1~6.4, 7.1~7.3, 9.7, 10.2(registration_candidates, candidate_evidence)
- prompts/extract_v1.md: 원문 표현만 추출하는 지시, 데이터 블록 분리, 인젝션 무시 지시
- JSON 스키마(pydantic) 검증 → api 내부 엔드포인트로 전달 → 정규화(S17) → 검증 → dedupKey → 저장
- 신뢰도 구간 처리(카드/제안함/버림), 장소는 카카오 검색 상위 3개
- 6.4 비생성 케이스 처리
- evals/: 가상 가족 대화 50건 + 정답 라벨, 실행 스크립트(일치율·오탐률 출력)
- LLM 제공자는 설정으로 선택, 프롬프트·응답 원문은 로그 금지
```

### S19. 후보 API + 앱 카드

**작업 위치:** backend + mobile

```
[기능] ARC-05, ARC-06, SCR-13, SCR-14
[참고] 사양서 6.2 ③~⑤, 6.5, 11.3, docs/design/figma-map.md (AI Suggestion Card 4:80, Resolved Chip 26:134)
backend:
- 후보 목록·상세, approve(수정 필드 + version, 한 트랜잭션으로 실제 항목 생성·item_sources·시스템 메시지),
  dismiss(사유), undo(5분), 동시 승인 409, 필수 누락 422, WebSocket candidate.* 이벤트
mobile:
- 근거 메시지 아래 카드(근거 인용, 추정 배지, "AI 제안" 표시, [등록][수정][무시], 메뉴의 [신고])
- 승인 후 Resolved Chip + 5분 [되돌리기], 다른 사람이 처리하면 "엄마가 등록했어요"
- 수정 바텀시트(AI 추정 칸 강조), 제안함 화면, 근거 탭 시 원문 스크롤·강조
AR-1~AR-8을 시나리오 테스트로 확인해줘.
```

### S20. 설정·개인정보 기능

**작업 위치:** backend + mobile

```
[기능] SET-01·02, SEC-01, ACC-01·02, AUTH-03 앱, MOD-01, SCR-80~82, WEB-01
[참고] 사양서 4.1, 4.2, 8.3, 8.4, 8.9, 10.2(consents, reports, data_export_requests)
- AI 분석 그룹/개인 설정 + 처음 켤 때 안내(전송 범위·국외 이전 고지)
- 동의 내역 화면, 위치 동의 철회 시 사진 위치 삭제
- 계정 삭제(앱) + 웹 삭제 요청 페이지(간단한 정적 페이지 + 소셜 로그인 확인), 30일 배치 파기
- 데이터 내보내기 비동기 생성(ZIP), 7일 링크
- 신고 API + 최소 관리자 페이지(신고 목록·처리, 공휴일, 앱 버전, 기능 플래그). 관리자는 신고된 콘텐츠만 열람, 감사 로그
- 앱 잠금(생체/PIN), 알림 미리보기 숨기기
```

### S21. 보안 테스트·관측·백업

**작업 위치:** `famlog-backend/`

```
사양서 9.9 SEC-T1~T10을 자동화 가능한 것부터 테스트로 만들어줘.
- SEC-T1: springdoc 엔드포인트 목록을 읽어 그룹 경로 API 전체에 다른 그룹 사용자로 요청하는 테스트 생성
- 로그 검사 스크립트: 테스트 실행 로그에 메시지 본문·토큰 패턴이 없는지
- Prometheus 메트릭, 에러·로그인 실패 급증 알림 규칙
- DB 백업 스크립트(일 1회, 7일 보관)와 복구 절차를 docs/runbooks/backup.md 로
- 사고 대응 runbook 초안 docs/runbooks/incident.md (사양서 9.8)
```

### S22. 출시 준비

**작업 위치:** backend + mobile + famlog

```
사양서 16장 체크리스트 기준으로 출시 준비를 도와줘.
- mobile: 릴리스 서명 설정(Play App Signing), R8 규칙, allowBackup/dataExtractionRules,
  network security config, 16KB 페이지 호환 점검, versionCode 규칙, 내부 테스트 트랙 업로드 CI
- backend: 운영 docker-compose(Nginx TLS), app_versions 초기값
- famlog/docs/policies/: 개인정보처리방침·이용약관·위치기반서비스 약관·아동 안전 기준 초안
  (사양서 8장 내용 기반, "법률 검토 필요" 표시)
- Play Console 데이터 보안 양식에 넣을 답변 표
초안은 내가 전문가 검토를 받을 거라는 전제로 작성해줘.
```

## 20.6 막힐 때 쓰는 프롬프트

| 상황 | 프롬프트 |
| --- | --- |
| 범위가 커짐 | "지금 변경을 기능 ID 기준으로 나눠서, 이번 커밋에 들어갈 것만 남기고 나머지는 TODO로 정리해줘." |
| 테스트가 계속 실패 | "실패 원인을 추측하지 말고, 실패 테스트 1개를 골라 재현 → 원인 → 최소 수정 순서로 진행해줘. 테스트를 고치거나 지우지 마." |
| 사양이 애매함 | "사양서 {절}에서 결정되지 않은 부분을 질문 목록으로 정리해줘. 내가 답하기 전엔 구현하지 마." |
| 리뷰 요청 | "이번 diff를 보안(인가·로그·비밀), 사양 일치, 테스트 누락 관점으로 리뷰해줘. 수정은 하지 말고 목록만." |
| 세션 인계 | "오늘 한 일, 남은 일, 결정 사항을 HANDOFF.md 진행 현황 형식으로 정리해줘." |
