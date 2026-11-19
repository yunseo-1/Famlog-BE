# FamLog 인수인계 문서

- 작성일: 2026-11-12
- 인계: 기획·설계 (Claude 채팅) → 구현 (Claude Code)
- 담당자: 김윤서 (기획·백엔드·안드로이드, 1인 개발)
- **이 사본은 백엔드 저장소(`Famlog-BE`) 기준이다.** 서버·워커·인프라 진행은 여기 8장에 기록한다.
  Android 진행은 모바일 저장소(`Famlog-moblie`)의 HANDOFF, 사양 원본은 문서 레포(`Famlog`)에 있다.
- 최종 갱신: 2026-11-19 (S1~S5·S8·S9·S11·S12·S14 완료)

---

## 1. 지금까지 만든 것

| 산출물 | 위치 | 상태 |
|---|---|---|
| 프로젝트 허브 | Notion `FamLog (팸로그) - 가족 라이프 플랫폼` (`40dfc8bd-3e5e-81e7-a350-d047fb58d744`) | 완료 |
| **통합 사양서 v1.0** (개발 기준) | Notion `416fc8bd-3e5e-8128-aab8-dc6d43bbd7f2` | 완료 (7.4 채팅 기반 자동 등록 포함) |
| 기획서 | Notion `40dfc8bd-3e5e-811c-a214-ca77147cf511` | 배경·방향 문서 |
| 기능명세서 | Notion `40dfc8bd-3e5e-8190-b3bb-dbcadd1477ab` | 사양서로 대체됨 (참고용) |
| ERD 초안 | Notion `40dfc8bd-3e5e-81f6-b5e9-e8e2a5e9c7dc` | 컬럼 상세 참고용, 일부 구버전 (아래 4장) |
| 경쟁 서비스 분석 (카나나) | Notion `40dfc8bd-3e5e-8108-8869-dcf1b0f0cbe4` | 완료 |
| Figma 와이어프레임 + 디자인 패턴 | https://www.figma.com/design/1nECE5iK8TBDRSydCasO1j | 02 채팅방 화면만 미완 (아래 3장) |
| Figma 화면 지도 (로컬) | `docs/design/figma-map.md` | 이 패키지 |
| 디자인 토큰 / Compose 테마 초안 | `docs/design/design-tokens.json`, `docs/design/compose-theme/` | 이 패키지 |
| 화면 캡처 | `docs/design/screenshots/` | 2026-11-11~12 시점 스냅숏 |

## 2. 확정된 결정 (Decision Log)

| 날짜 | 결정 | 이유 |
|---|---|---|
| 11/03 | DB는 PostgreSQL 하나 (+PostGIS +pgvector) | 위치 검색·벡터 검색을 DB 하나로 |
| 11/03 | 채팅과 AI 처리는 큐(Redis Streams)로 분리 | AI 장애가 채팅에 영향 없게 |
| 11/11 | 서비스명 **FamLog (팸로그)** | 'Famory'는 Play에 같은 이름·같은 컨셉 앱이 있음 |
| 11/11 | Android 전용, Google Play 출시 목표 | 개인 계정 → 비공개 테스트 12명·14일 필요 |
| 11/11 | **1:1 채팅 없음**, 가족 그룹당 단체방 1개 | 데이터 범위 = 그룹 범위로 단순화 |
| 11/11 | 여러 그룹 소속 시 **앱 바에서 현재 그룹 전환** (채팅 탭만 전체 목록) | 친가·외가 데이터가 섞이지 않게 |
| 11/12 | 월간 스토리 우선 (주간은 추후) | 요구사항 |
| 11/12 | YouTube: v1.1은 저장+공유 시트, v1.2에 API 직접 업로드 | 감사 전 API 업로드 영상은 비공개 고정 |
| 11/12 | **AI 자동 등록은 후보 테이블 분리** (`registration_candidates`) | "AI가 임의로 등록하지 않는다" 요구사항을 구조로 보장 |
| 11/12 | 로그인: 카카오·Google 소셜 로그인 (제안, 오픈 이슈) | 부모님 세대 편의, 비밀번호 미보관 |
| 11/12 | 레포 분리: 문서(`Famlog`) · 백엔드(`Famlog-BE`) · 모바일(`Famlog-moblie`) | 사양서 v2.0 13.3 |
| 11/13 | 로컬 S3 저장소 **MinIO → SeaweedFS** | MinIO 커뮤니티 Docker 이미지 배포 중단. 서버는 S3 API만 사용 |
| 11/13 | **Spring Boot 3 → 4.1** | 3.x 오픈소스 지원 종료(3.5.16, 2026-06) |
| 11/13 | JWT **ES256 + kid**, Refresh 이력 테이블 `refresh_tokens`(V2) | 교체된 토큰 재사용 감지 → 사용자 전 세션 폐기 |
| 11/13 | 원격 로그아웃 즉시 반영: Redis에 폐기 세션 기록, 장애 시 거부 | 18.1 AUTH-03 |
| 11/13 | 요청 제한 Redis 장애 시 통과(fail-open) | 요청 제한 장애가 로그인 장애로 번지지 않게 |
| 11/13 | **연령 확인은 가입 단계**(`/auth/{provider}/signup`), 미가입 로그인은 404 `USER_NOT_REGISTERED` | 14세 미만 정보가 서버에 남지 않게 (A안) |
| 11/14 | 그룹: 에러 코드 `GROUP_LIMIT_EXCEEDED`·`OWNER_TRANSFER_REQUIRED`, 입장 메시지는 S8에서 이벤트로 | 앱 분기용, 메시지 테이블은 S8 |
| 11/17 | API 계약 `docs/api/openapi.yaml`을 **이 저장소에서 생성·커밋**, 문서 레포 반영은 `scripts/export-openapi.sh` 후 직접 커밋 | CI가 계약과 코드가 같은지 확인 |
| 11/17 | 채팅 seq는 `chat_rooms.last_seq`를 잠그고 +1 (V4) | 서버가 여러 대여도 방 안에서 겹침·빠짐 없음 |
| 11/17 | **새 구성원은 들어온 뒤 대화만 본다** (`group_members.join_seq`) | 카카오톡 방식, 개인정보 최소 (사양서 8장) |
| 11/17 | 안 읽은 수는 서버가 메시지마다 계산, 이후 `read.updated {fromSeq, lastReadSeq}`로 앱이 갱신 | 메시지별 숫자(카카오톡 방식) |
| 11/17 | 채팅 탭 요약은 `GET /groups/me`에 `lastMessage`·`unreadCount` 추가 | 엔드포인트를 늘리지 않음 |
| 11/17 | 공감: 한 사람당 1개, 6종 고정(HEART·LIKE·LAUGH·WOW·SAD·PRAY) | 서버는 이름만, 그림은 앱 |
| 11/17 | 삭제 거부는 403 `MESSAGE_NOT_DELETABLE` | 남의 메시지·5분 경과·시스템 메시지 |
| 11/17 | 그룹에서 나가거나 세션이 폐기되면 서버가 WebSocket 연결을 끊는다 | 이미 구독한 토픽을 계속 받지 않게 |
| 11/17 | 워커는 이 저장소 `worker/` (Python 3.12, Docker 이미지로 실행·테스트) | CLAUDE.md 구조, 로컬에 Python·FFmpeg 설치 불필요 |
| 11/17 | 사진은 묶음 메시지 1개(`IMAGE`, 30장까지), 동영상은 1개씩(`VIDEO`). 업로드 → 완료 → STOMP 전송 | 카카오톡 방식, 썸네일은 `media.updated`로 나중에 |
| 11/17 | 내려받기는 `GET /media/{id}/content?variant=` → 10분 Presigned URL로 302 | 앱 이미지 캐시 키 고정, 토큰은 저장소로 안 감 |
| 11/17 | 사진 원본은 다시 인코딩(회전 적용, 메타데이터 전부 제거, HEIC→JPEG), 동영상은 `-c copy`로 메타데이터만 제거 | GPS 제거 + 위장 내용 무력화 (9.1) |
| 11/17 | 워커는 S3·Redis만 쓰고 DB 반영은 API(`media-results` 스트림 소비) | 스키마·WebSocket 이벤트는 Java만 관리 |
| 11/17 | 사진 위치 저장은 `famlog.media.photo-location-enabled=false`(기본) + LOCATION 동의일 때만, 저장 시 `location_access_logs` | 위치기반서비스 신고 확인 전 (8.6) |
| 11/17 | pHash·흐림 점수·워커 FastAPI는 앨범 단계로 미룸 | 지금 쓰는 곳이 없음 |
| 11/18 | 푸시는 FCM HTTP v1 직접 호출(`google-auth-library-oauth2-http`로 OAuth만), firebase-admin 안 씀 | 쓰지 않는 의존성이 많고, v1은 어차피 기기별 호출 |
| 11/18 | 푸시는 **data 메시지만**(notification 없음, Android HIGH). 채널·묶음·화면에 있을 때 숨기기는 앱이 | 모바일 요청 (채널 4개, 열린 채팅방이면 표시 안 함) |
| 11/18 | 미리보기 숨기기·방해 금지는 사용자 단위(`user_notification_settings`, V6, `/users/me/notification-settings`), 그룹 알림 끄기는 `group_members.notif_muted` | 사양서 10장에 둘 곳이 없음. 기기·그룹마다 다를 이유가 적음 |
| 11/18 | 푸시 대상: 새 메시지(입장·퇴장 시스템 메시지 포함), 내보내진 본인, 새 기기 로그인(다른 기기로). 일정·AI는 해당 단계에서 | AUTH-03 "새 기기 로그인 알림" 포함 |
| 11/18 | 동영상 소리가 컨테이너에 못 들어가면(AMR-NB 등) 영상은 그대로, 소리만 AAC로 변환. 그래도 안 되면 `UNSUPPORTED_CODEC` | 에뮬레이터 카메라 영상이 `NOT_A_VIDEO`로 거부됨 (모바일 S10-2) |
| 11/18 | 반복은 RRULE 저장(`FREQ=WEEKLY\|MONTHLY\|YEARLY`, `INTERVAL`, `UNTIL`\|`COUNT`만), 펼치기는 java.time 직접 구현. 없는 날짜(31일·2/29)는 건너뜀 | 앱이 쓰는 범위만. 라이브러리 추가 없이 (필요해지면 lib-recur로 교체) |
| 11/18 | 반복 일정 수정·삭제는 시리즈 전체만 ("이번 일정만"은 v1.1) | v1.0 범위 |
| 11/18 | 종일 일정은 `startDate`·`endDate`(포함)로 받고 서울 00:00 ~ 끝날 다음 날 00:00으로 저장 | 시간대 혼동 방지 |
| 11/18 | 일정 필터 `memberIds`·`roles`는 합집합, 참여 구성원 기준. 기간 조회는 최대 100일, 페이징 없음 | SCH-02 |
| 11/18 | 리마인드는 `schedules.reminders`(text[]) 일정별 선택, 1분 스케줄러 + `schedule_reminder_sends` PK로 회차·종류마다 한 번 | 10.2에 알림 컬럼이 없음. 서버 여러 대 대비 |
| 11/18 | 할 일 완료는 구성원 누구나, 내용 수정·삭제는 등록한 사람·ADMIN 이상. 채팅방 한 줄 알림은 완료 요청의 `announce=true`일 때만 | 장 봐 온 사람이 체크. 그룹 설정화는 S20 |
| 11/18 | 공휴일은 마이그레이션으로 해마다 추가 (2026~2027 V7). 자동 갱신·관리자 수정은 S20 | 공공데이터 API 키 필요 |
| 11/18 | 시스템 메시지(입장·퇴장·할 일 완료)의 이름도 그룹 호칭이 있으면 호칭, 없으면 닉네임. 이미 남은 메시지는 그대로 | 앱 구성원 표시·푸시 `senderName`과 맞춤 (모바일 요청) |
| 11/19 | 장소 좌표는 `lat`·`lng` 컬럼 + DB 생성 컬럼 `geom`(geography). 거리 검색만 PostGIS SQL | hibernate-spatial 없이 (새 라이브러리 없음) |
| 11/19 | 공유는 `kakaoPlaceId`만 받고, 서버가 검색 때 Redis에 1시간 남겨 둔 카카오 결과로 저장 | 앱이 보낸 이름·좌표를 믿지 않는다. 카카오에 id 조회 API가 없음 |
| 11/19 | 검색에 x·y(위치)를 보내면 개인위치정보 이용으로 기록(`PLACE_SEARCH`), 목록 캐시 안 함 | 사양서 8.6 |
| 11/19 | 지역은 카카오 지번 주소에서: 시·도는 짧은 이름(서울·경기·강원), 일반구는 "수원시 영통구", 세종은 시·군·구 없음 | 카카오 region 표기와 맞춤 |
| 11/19 | 같은 그룹에 같은 카카오 장소는 하나 (409 `PLACE_ALREADY_SHARED`). 장소 수정·삭제는 공유한 사람·ADMIN 이상, 낙관적 잠금 없음 | 10.2 places에 version 없음 |
| 11/19 | 내 주변 기능 플래그는 설정값 `famlog.place.nearby-enabled` (꺼지면 404). DB `feature_flags`·관리 화면은 S20 | 사양서 8.6 출시 전 할 일 |
| 11/19 | 한글 목록 정렬(별칭·지역)은 DB가 아니라 서버 코드에서 가나다(유니코드) 순 | DB 정렬 규칙(collation)에 따라 한글 순서가 달라짐 |
| 11/19 | 동의 API는 사양서 11.3대로 GET·PUT만(철회는 `{agreed:false}`), 지금은 LOCATION만. AI 분석·국외 이전은 S16 | 모바일 요청 (S15-2 내 주변 전) |
| 11/19 | 내 주변은 위치 동의가 있어야 한다(403 `LOCATION_CONSENT_REQUIRED`). 장소 검색의 x·y는 막지 않는다 | 지도 중심일 수 있어 서버가 구분 못 함. 앱이 동의 시에만 현재 위치를 보낸다 |
| 11/19 | 추천 카테고리(`suggestedCategory`): 카카오 `CT1`(문화시설)은 `ETC`. 관광명소 `AT4`·숙박 `AD5`만 `TRAVEL` | 영화관·공연장이 여행지로 추천됨 (모바일 실기기 확인). 이미 공유된 장소는 그대로 |
| 11/19 | S16은 LLM을 부르지 않는다(워커 `LLM_PROVIDER=fake`). 제공자·모델·월 비용 상한·그룹별 하루 상한은 S18에서 정한다 | 사양서 20 S16 "가짜 응답으로 끝까지" |
| 11/19 | 그룹 AI 분석은 `group_settings.ai_analysis_enabled`(기본 꺼짐), `GET·PATCH /groups/{id}/settings`(변경 ADMIN 이상)를 S16에서 만듦. 처음 켤 때 안내·국외 이전 고지·`AI_ANALYSIS` 동의는 S20 | S19 앱에서 켜 볼 방법이 필요 |
| 11/19 | 개인 분석 제외는 `group_members.ai_opt_out`, `PATCH .../members/me/preferences`의 `aiOptOut`. 제외했던 동안의 메시지는 다시 켜도 보내지 않는다(`ai_opt_out_changed_at`) | 사양서 8.7 "큐에도 넣지 않는다" |
| 11/19 | AI를 켜기 전 대화는 분석·앞 맥락 모두 보내지 않는다 (켜는 순간 `chat_rooms.ai_analyzed_seq`를 마지막 seq로) | 사용자 결정 |
| 11/19 | 마스킹·가명 처리(M1…)는 API에서. 가리지 않은 원문은 Redis에 들어가지 않고, 처리한 스트림 항목은 ACK 후 지운다. DLQ에는 jobId·오류만 | 사양서 8.7 |
| 11/19 | 분석 대상은 사람이 보낸 TEXT(삭제 제외)만. 사진·장소·시스템 메시지는 분석도 앞 맥락도 아님 | 장소 공유는 이미 등록된 것 |
| 11/19 | 보조 분석(30개·10분)은 규칙 필터에 안 걸린 메시지도 보낸다 | 사양서 4.5 ARC-01 그대로, 비용 상한은 S18 |
| 11/19 | 정규화(S17): 오늘과 같은 요일·날짜는 오늘, 함께 말한 시각이 지났으면 다음 회차. 일요일의 "다음 주 월요일"은 내일(월~일 주) | 사용자 결정 |
| 11/19 | "이번 주말"(일정) → 토요일 추정(일요일에 말하면 오늘), 기한 "이번 주에" → 일요일 추정, "~까지/안에"는 확정 | 사양서 6.2 ② + 6.3 예시 2 |
| 11/19 | 시각만 있으면 안 지났으면 오늘, 지났으면 내일 (날짜 추정). 아침·저녁 등 단서로 정한 오전·오후는 추정 아님. 자정·밤 12시는 다음 날 00:00 | 사용자 결정, 6.3 예시 5 |
| 11/19 | "어제·지난주"는 지난 일로 돌려 후보를 만들지 않는다(6.4). "31일"은 그 날이 있는 다음 달, 월까지 정한 없는 날("다음 달 30일" 2월)은 날짜를 비운다 | 사용자 결정 |
| 11/19 | S18 LLM: `claude-haiku-5-5` + 프롬프트 캐싱, 월 비용 상한 $20(80%에 경고 로그), 그룹별 하루 100회. 워커에 `anthropic` SDK 추가 승인. S18-1(후보 생성, 가짜 LLM) · S18-2(실제 LLM·프롬프트·상한·평가셋)로 나눈다 | 사용자 결정. 키는 `infra/.env`의 `ANTHROPIC_API_KEY`만 |
| 11/19 | LLM 출력은 유형별 원문 표현 필드만(`dateText`·`attendeeTexts`…). 추정 표시·필수 누락·날짜·구성원·장소는 서버가 정한다. 다른 필드가 오면 `INVALID_OUTPUT` | 사용자 결정 (S18-1 계획 1) |
| 11/19 | 일정 장소는 별칭만 맞춰 보고 카카오 검색은 PLACE 후보에만 (호출 절약) | 사용자 결정 |
| 11/19 | 변경 요청("11시 말고 12시로")·완료 표현("샀어")은 S18에서 후보를 만들지 않는다. 기존 항목 수정·완료 후보(`target_ref`)는 S19 승인과 함께 | 사용자 결정, AR-8 |
| 11/19 | 지난 날짜 일정·지난 기한 할 일은 버리고, 해석 못 한 일정 날짜는 `missingFields:["date"]`로 만든다 | 사용자 결정 |
| 11/19 | 중복: 같은 `dedupKey`의 최근 후보가 PENDING(만료 전)·APPROVED면 근거만, DISMISSED면 안 만듦, EXPIRED·UNDONE·만료된 PENDING이면 새로. 직접 등록한 항목과는 비교하지 않는다 | 사용자 결정 |
| 11/19 | 만료 `expires_at`은 만들 때 정한다(일정 시각, 종일은 다음 날 0시, 나머지 7일). 만료 배치 없이 조회에서 거른다(S19) | 사용자 결정 |
| 11/19 | 결과가 왔을 때 그룹 AI가 꺼져 있으면 후보를 저장하지 않는다 (`ai_jobs` DONE, `candidate_count` 0). `candidate_count`는 새로 만든 후보 수 | 사용자 결정 |
| 11/19 | 사람 표현 자리의 청유형("가자·하자·먹자…")은 가족 전체(추정) | 사양서 6.2 ② "가자/우리", 6.3 예시 1 |
| 11/19 | S18-2: 인식은 생각(thinking) 끄고 시작. 평가셋이 목표 미달이면 "생각 켬·노력 낮음"과 비교 | 사용자 결정. 평가 일치율 100%·오탐 0%·날짜 100% (2026-11-20, `worker/evals/README.md`) |
| 11/19 | 그룹 하루 100회를 넘으면 그날 남은 메시지는 분석하지 않고 넘어간다 (다음 날로 미루지 않음) | 사용자 결정 |
| 11/19 | 월 비용은 UTC 달 기준(Console 청구와 같다), `ai_jobs` 토큰 × 설정 단가. 캐시 토큰 칸 추가(V11) | 사용자 결정 |
| 11/19 | 평가는 두 단계: 워커가 실제 LLM 결과 저장(`python -m evals.run`, 비용) → API 가 후보 생성 코드로 지표 계산(`./gradlew evalReplay`). 둘 다 CI 제외 | 사용자 결정. 날짜 정확도는 서버 정규화를 거쳐야 잴 수 있다 |
| 11/19 | 사람 표현의 부르는 말("막내야·딸아")은 떼고 찾는다 | 평가셋 e17 |
| 11/20 | S19: 후보 카드는 채팅 메시지로 만들지 않는다. 앱이 후보 API의 `anchorMessageId`(마지막 근거 메시지) 아래에 그린다 | 사용자 결정. 사양서 10.2 `type=CANDIDATE` 메시지는 쓰지 않음 |
| 11/20 | 수정 없이 [등록]하면 서버가 후보 값을 등록 요청으로 바꾼다. 채울 항목·고를 사람·장소 후보 여럿이면 422 `CANDIDATE_MISSING_FIELDS`(fieldErrors) → 앱이 폼을 연다 | 사용자 결정 |
| 11/20 | 기본값: 일정 참석자 없으면 말한 사람, 별칭에 연결된 장소가 없으면 메모 "장소: …", 끝 시각 없으면 시작과 같게. 기념일은 매년 반복 종일·가족 전원·전날 20시 알림(1주 전·음력은 v1.1). 장소는 승인한 사람이 공유, 한줄평은 AI 요약, 이미 공유된 장소면 연결만 | 사용자 결정 |
| 11/20 | 되돌리기: 승인한 사람이나 ADMIN 이상, 5분 안. 만든 항목·원문 연결·승인 시스템 메시지를 지운다 | 사용자 결정 |
| 11/20 | 후보 처리 푸시는 승인 시스템 메시지의 채팅 푸시뿐. 참석자·담당자 개별 푸시·후보 생성 푸시는 S20 알림 설정과 함께 | 사용자 결정 |
| 11/20 | 일정·할 일·장소 응답에 `source {messageId, seq}` (채팅에서 등록한 항목의 원문). 만료 승인은 409 `CANDIDATE_EXPIRED`. 같은 사람이 다시 처리하면 같은 결과(Idempotency-Key 대신) | 사용자 결정 |
| 11/20 | S19-1: `source`에 `candidateId` 추가. 마이그레이션 없이 후보의 `created_entity_*`·근거 메시지로 찾는다 | 앱 요청, 사용자 결정 |

## 3. Figma 상태

- 파일 키: `1nECE5iK8TBDRSydCasO1j` / 페이지: `🎨 Design Patterns`(0:1), `📱 Wireframes`(2:2)
- 완료: 화면 01, 03~07, 컴포넌트 12종, 토큰(변수), 디자인 가이드(패턴 A~H)
- **미완: 02 채팅방 화면 재구성** — Figma MCP 호출 한도(Starter)로 중단됨.
  현재 02(`6:114`)는 구버전 메시지 내용이다. 카드 컴포넌트는 최신 구조로 바뀌어 있다.
  만들어야 할 화면은 `figma-map.md` 5장 "02 계열 목표 구조"에 적어 두었다.
  → 앱 구현은 **목표 구조 문서 + 컴포넌트 기준**으로 진행하면 된다. Figma 반영은 한도가 풀리면 따로.
- 04~06 캡처는 앱 바가 "장소/앨범/이야기"로 찍혀 있지만, 실제 Figma는 **"우리 가족 ▾"(그룹 전환)**으로 바뀌어 있다.

## 4. 알려진 불일치 (구현 시 이렇게 처리)

| 위치 | 내용 | 처리 |
|---|---|---|
| ERD `users` | email, password_hash 컬럼 | 소셜 로그인 확정 시 제거, `social_accounts` 사용 (사양서 5.2) |
| ERD `group_members` | family_role, ai_opt_out 없음 | 사양서 5.2대로 추가 |
| ERD `archive_items.status` | SUGGESTED/DISMISSED 상태 | 쓰지 않는다. 후보는 `registration_candidates` |
| ERD `messages.type` | ARCHIVE_SUGGESTION | `CANDIDATE` 카드 타입으로 바꾸고 metadata에 candidateId |
| ERD `album_videos` | 앨범 전용 | 사양서 5.2 `videos`(source_type)로 일반화 |
| ERD `stories.period_type` | WEEKLY 기본처럼 보임 | MONTHLY 기본 |
| `suggestion_feedback` | archive_item_id 기준 | `candidate_id` 기준으로 기록 (승인/수정/무시 모두) |
| 기능명세서 | 구 ID 체계(SUM-01 등) | 사양서 4장 ID만 사용 |

## 5. MVP 범위와 일정 (사양서 12·13장)

포함: 소셜 로그인·만 14세 처리·계정 삭제 / 그룹 생성·초대·전환·가족 역할 / 단체 채팅(사진·동영상·읽음·푸시) /
구성원별·전체 일정, 할 일 / 장소 공유·지역별·구성원별 목록·지도 / ⭐ 채팅 기반 자동 등록(일정·기념일·할 일·장소)

| 주차 | 기간 | 목표 |
|---|---|---|
| W1 | 11/16~11/22 | 개발 환경 (아래 6장) |
| W2~3 | 11/23~12/06 | AUTH, GRP-01~04, SCR-01~03·70 |
| W4~5 | 12/07~12/20 | CHAT-01~05, NOTI-01, Photo Picker EXIF 검증 |
| W6 | 12/21~12/27 | SCH-01~03, TODO-01 |
| W7 | 12/28~01/03 | PLC-01~04 |
| W8~9 | 01/04~01/17 | ⭐ ARC (사양서 7.4 전체), 테스트 대화 데이터셋 |
| W10 | 01/18~01/24 | ACC-01, SET-01, 개인정보처리방침, QA |
| W11~12 | 01/25~02/07 | 비공개 테스트 (12명·14일) |
| W13~14 | 02/08~02/21 | v1.0 출시 |

## 6. 백엔드 진행 (사양서 20.4 단계)

| 단계 | 내용 | 상태 |
|---|---|---|
| S1 | 로컬 인프라 (PostgreSQL+PostGIS+pgvector, Redis, SeaweedFS) | ✅ PR #2 |
| S2 | 서버 골격·공통 (에러 형식, traceId, 로그 마스킹, Flyway V1, Testcontainers, CI) | ✅ PR #3 |
| S3 | 보안 골격 (JWT, Refresh 교체·재사용 감지, GroupAccessChecker, 요청 제한) | ✅ PR #4 |
| S4 | AUTH-01~03 소셜 로그인·가입·연령 확인·기기 관리 | ✅ PR #5 |
| S5 | GRP-01~05 가족 그룹·초대·역할·구성원 관리 | ✅ PR #6 |
| - | API 계약 `docs/api/openapi.yaml` 생성·CI 확인 | ✅ PR #7 |
| S8 | CHAT-01~04·06 서버 (STOMP, seq, 읽음) + 입장·퇴장 시스템 메시지 | ✅ PR #8 |
| S9 | CHAT-05 미디어 업로드 + 워커 썸네일·EXIF 정리 | ✅ PR #10 |
| S11 | NOTI-01 FCM 푸시 + SET-02 알림 설정 API + 새 기기 로그인 알림 | ✅ PR #12 |
| S12 | SCH-01~05 일정·반복·리마인드·공휴일·겹침, TODO-01 할 일 | ✅ PR #13 |
| S14 | PLC-01~06 장소 공유·지역/구성원 목록·내 주변·다녀옴·별칭, 일정 장소 연결 | ✅ PR #15 |
| S16 | ARC-01 인식 파이프라인 골격: 규칙 필터·30초 멈춤·보조 분석, 마스킹·가명, 워커 소비·DLQ·가짜 LLM, 그룹 AI 설정·개인 제외 | ✅ PR #18 |
| S17 | ARC-02·04 날짜·시각·구성원 정규화 (단위 테스트 89개, AR-9) | ✅ PR #19 |
| S18-1 | ARC-02~04·07 후보 생성: 검증·정규화·장소 상위 3개·중복 제거·신뢰도 (가짜 LLM) | ✅ PR #20 |
| S18-2 | 실제 LLM(claude-haiku-5-5, 캐싱)·프롬프트 extract_v1·호출/비용 상한·평가셋 50건 | ✅ PR #21 |
| S19 | ARC-05·06 후보 목록·상세·승인·무시·되돌리기, 원문 연결, candidate.* 이벤트 | ✅ PR #22 |
| S19-1 | ARC-06 원문 보기 강조용 `source.candidateId` | ✅ 이번 PR |
| S20~S22 | (다음) 설정·개인정보·신고, 보안 테스트, 출시 준비 | 다음 (S20) |
| S20~S22 | 설정·개인정보, 보안 테스트, 출시 준비 | |

모바일 S6·S7은 `Famlog-moblie`에서 완료(2026-11-17).

### API 계약 갱신 절차

1. API를 바꾸면 `./gradlew updateOpenApi` → `docs/api/openapi.yaml` 이 다시 만들어진다 (Docker 필요)
2. 이 저장소에 함께 커밋한다. 갱신을 잊으면 CI(`OpenApiContractTest`)가 실패한다
3. 머지 후 `sh scripts/export-openapi.sh <문서 레포 경로>` → 문서 레포에서 커밋·push

### 실시간 채팅 규칙 (S8, 앱 S10 참고)

- 연결: `ws(s)://{서버}/ws` (SockJS 없음), STOMP CONNECT 헤더 `Authorization: Bearer {accessToken}`, 하트비트 10초
- 보내기: `/pub/groups/{groupId}/messages` `{clientMessageId(UUID), body(≤2000자), replyToId?}`, 읽음: `/pub/groups/{groupId}/read` `{lastReadSeq}`
- 받기: `/sub/groups/{groupId}` → `{type, data}`: `message.created`·`message.deleted`·`read.updated`·`reaction.updated`
- 개인: `/user/queue/events` → 실패 `error {code, message, clientMessageId}`, 재전송 확인 `message.created`(이미 저장된 메시지), `session.revoked`
- 거부(토큰 만료·무효, 다른 그룹 구독·전송): ERROR 프레임 `message` 헤더 = 에러 코드, 서버가 연결을 끊는다.
  `AUTH_TOKEN_EXPIRED`면 재발급 후 재연결. 그룹에서 나가거나 다른 기기에서 로그아웃돼도 연결이 끊긴다 → 재연결·그룹 목록 새로 고침
- **앱은 seq 순서로 정렬한다.** 서로 다른 사람의 메시지는 도착 순서가 seq와 다를 수 있다. seq에 빈 곳이 보이거나 재연결하면 `GET /groups/{id}/messages?afterSeq={마지막 seq}`로 채운다
- 전송 제한 초당 5개/사용자: 오프라인 대기열을 한꺼번에 보내면 `RATE_LIMITED`가 올 수 있다 → 잠시 뒤 같은 clientMessageId로 다시 보낸다
- 답장 원문이 내가 들어오기 전 메시지면 `replyTo.body`가 null이다 (그룹 이벤트는 구성원 모두가 볼 수 있을 때만 본문을 넣는다. 앱 캐시에 있으면 그걸 쓴다)

### 사진·동영상 규칙 (S9, 앱 S10 참고)

1. `POST /media/upload-urls {groupId, items:[{mimeType, size}]}` → `items[].{mediaId, uploadUrl, headers, expiresAt}` (10분)
   - 사진 jpeg·png·webp·heic·heif 30MB, 동영상 mp4·mov 500MB·5분, 한 번에 30개
2. `uploadUrl`에 **응답의 headers(Content-Type)를 그대로 넣어** PUT (크기도 신고한 값과 같아야 한다)
3. `POST /media/{id}/complete` → `UPLOADED`. 형식이 다르면 400(파일 삭제). 실패하면 다시 불러도 된다
4. STOMP `/pub/groups/{id}/messages {clientMessageId, type:"IMAGE", mediaIds:[...]}` (사진 1~30, 보이는 순서) 또는 `type:"VIDEO", mediaIds:[하나]`
5. 메시지의 `media[].status`가 `UPLOADED`면 처리 중 → 그룹 이벤트 `media.updated {messageId, media}`로 `READY`(또는 `FAILED`·`REJECTED`)가 온다
   - 거부 사유(`media_assets.reject_reason`, API 응답에는 없음): 사진 `NOT_AN_IMAGE` 등, 동영상 `NOT_A_VIDEO`(읽을 수 없음)·`TOO_LONG`(5분 초과)·`UNSUPPORTED_CODEC`(소리를 AAC로 바꿔도 mp4·mov에 담을 수 없는 코덱)
6. 보기: `GET /api/v1/media/{id}/content?variant=THUMB_S|THUMB_M|THUMB_L|ORIGINAL` (Authorization 헤더, 302 따라가기). READY 전에는 404
- 로컬 에뮬레이터에서 업로드하려면 `infra/.env`에 `S3_PUBLIC_ENDPOINT=http://10.0.2.2:8333`

### 푸시 규칙 (S11, 앱 S11 참고)

**토큰**
- `PUT /api/v1/devices/token {token, appVersion?}` → 204. 앱 시작·`onNewToken` 때 부른다. 플랫폼은 로그인 때 보낸 기기 정보를 쓴다
  - 로그인한 기기(세션) 하나에 토큰 하나: 새 토큰을 보내면 그 기기의 이전 토큰을 바꾼다. 같은 토큰을 다시 보내면 앱 버전만 바뀐다
  - 한 사람이 여러 기기를 쓰면 기기마다 받는다. 같은 토큰이 다른 계정에 있으면 지금 계정으로 옮긴다 (한 폰에서 계정을 바꾼 경우)
  - 폐기된 세션이면 401 `AUTH_INVALID`, 토큰이 비었거나 512자를 넘으면 400
- `DELETE /api/v1/devices/token` → 204 (없어도 204). 알림을 받지 않으려 할 때
- 로그아웃·원격 로그아웃·전 세션 폐기 때 서버가 그 기기의 토큰을 지운다 (앱이 따로 부를 필요 없음). 만료된 세션의 토큰으로는 보내지 않는다
- FCM이 `UNREGISTERED`·`SENDER_ID_MISMATCH`·잘못된 토큰이라고 하면 서버가 지운다 → 앱은 시작할 때마다 PUT 하면 된다

**메시지**: data 메시지만 (notification 없음), Android 우선순위 HIGH. 값은 모두 문자열
| 키 | 값 |
|---|---|
| `type` | `CHAT_MESSAGE` · `MEMBER_REMOVED` · `NEW_DEVICE_LOGIN` · `SCHEDULE_REMINDER`(S12) (AI는 S16~) |
| `channel` | `CHAT` · `SCHEDULE` · `AI` · `SYSTEM` (앱 알림 채널) |
| `silent` | `"true"`면 방해 금지 시간 → 소리·진동 없이 |
| `sentAt` | +09:00 ISO-8601 |
| `groupId`, `groupName` | CHAT_MESSAGE·MEMBER_REMOVED. 묶음 키는 `groupId` |
| `messageId`, `seq`, `messageType` | CHAT_MESSAGE. `messageType` = TEXT·IMAGE·VIDEO·PLACE(장소 카드, S14)·SYSTEM(입장·퇴장) |
| `senderId`, `senderName` | CHAT_MESSAGE 중 사람이 보낸 것. 이름은 그룹 호칭(`familyTitle`)이 있으면 호칭, 없으면 닉네임 |
| `mediaCount` | IMAGE·VIDEO |
| `body` | TEXT·SYSTEM이고 받는 사람이 미리보기를 숨기지 않았을 때만. 100자 넘으면 잘라서 `…`. **숨김이면 키 자체가 없다** |
| `sessionId`, `platform`, `deviceName?` | NEW_DEVICE_LOGIN. 새로 로그인한 기기 (기기 관리 화면 `GET /users/me/sessions`의 sessionId) |
| `scheduleId`, `startsAt`, `allDay`, `reminder`, `title?` | SCHEDULE_REMINDER (`channel=SCHEDULE`, `groupId`·`groupName` 포함). `startsAt`은 그 회차 시작(+09:00), `reminder`는 `PREV_DAY_20`·`MIN_10`·`MIN_30`·`HOUR_1`·`DAY_1`. 미리보기를 숨긴 사람에게는 `title` 키가 없다 |

**받는 사람**
- `CHAT_MESSAGE`: 그룹 구성원 중 보낸 사람(시스템 메시지는 들어오거나 나간 본인), 나간 사람, 그룹 알림을 끈 사람, 그 메시지보다 늦게 들어온 사람을 뺀다
- `MEMBER_REMOVED`: 내보내진 본인 (`channel=SYSTEM`). 다른 구성원은 퇴장 시스템 메시지로 `CHAT_MESSAGE`를 받는다
- `NEW_DEVICE_LOGIN`: 처음 쓰는 기기(`deviceId`)로 로그인하면 그 사람의 다른 기기로. 같은 기기로 다시 로그인하면 보내지 않는다
- `SCHEDULE_REMINDER`: 그 일정의 참여 구성원 중 그룹에 남아 있고 그룹 알림을 끄지 않은 사람 (등록한 사람이라도 참여자가 아니면 받지 않는다)
- 채팅방을 보고 있는지는 서버가 따지지 않는다. 앱이 화면에 있으면 앱이 표시하지 않는다 (STOMP로 이미 받았다)

**설정 (SET-02, 화면은 모바일 S20)**
- `GET·PATCH /api/v1/users/me/notification-settings {previewHidden, quietHoursEnabled, quietStart:"HH:mm", quietEnd:"HH:mm"}`: 보낸 값만 바뀐다. 기본값은 미리보기 보이기, 방해 금지 꺼짐(22:00~07:00). 서울 시각, 자정을 넘겨도 된다. 시작=끝이면 400
- `GET·PATCH /api/v1/groups/{groupId}/members/me/preferences {notifMuted}`: 이 그룹 알림 끄기. 구성원이 아니면 403

**서버 설정**: `infra/.env`의 `FIREBASE_CREDENTIALS_PATH`(서비스 계정 키 경로, `~/` 가능). 비어 있으면 보내지 않고 로그만 남긴다 (CI·테스트). 운영은 `FAMLOG_PUSH_CREDENTIALS_PATH`
`infra/.env`의 `KAKAO_REST_API_KEY`(카카오 로컬 검색, 비밀). 비어 있으면 장소 검색만 500. 운영은 `FAMLOG_PLACE_KAKAO_REST_API_KEY`

### 일정·할 일 규칙 (S12, 앱 S13 참고)

**일정** (`/groups/{groupId}/schedules`, `/schedules/{id}`)
- 등록 `POST` → 201. 시간 일정 `{allDay:false, startsAt, endsAt}`(+09:00, 끝 ≥ 시작), 종일 `{allDay:true, startDate, endDate}`(마지막 날 포함). 길이 최대 1년
- `participantIds` 1~20명, 이 그룹의 활성 구성원. 나중에 나간 사람은 참여자로 남고(수정할 때 그대로 둘 수 있음), 알림만 받지 않는다
- `rrule`: `FREQ=WEEKLY|MONTHLY|YEARLY`, `INTERVAL`(1~99), `UNTIL`(YYYYMMDD = 그날 서울 시각까지, 또는 YYYYMMDDTHHMMSSZ) 또는 `COUNT`(1~999). 그 밖(BYDAY 등)은 400. 서버가 정해진 순서로 바꿔 저장
  - 회차는 첫 회차의 서울 요일·날짜·시각을 그대로 옮긴다. 31일 매월·2/29 매년은 그 날짜가 없는 달·해를 건너뛴다
- `reminders`: 키를 빼면 기본값(시간 일정 `[PREV_DAY_20, HOUR_1]`, 종일 `[PREV_DAY_20]`), `[]`이면 알림 없음, 최대 3개. 종일 일정은 `PREV_DAY_20`만
- 수정 `PATCH /schedules/{id}`: **값 전체 + 조회한 `version`**. version이 다르면 409 `VERSION_CONFLICT`, 없으면 400. 반복 일정은 시리즈 전체가 바뀐다 (이번 회차만은 v1.1)
- 삭제 `DELETE` → 204 (시리즈 전체). 수정·삭제는 등록한 사람 또는 ADMIN 이상, 아니면 403 `GROUP_ROLE_REQUIRED`
- 다른 그룹(나간 그룹 포함) 일정 id → 404
- 기간 조회 `GET ?from=&to=`(서울 날짜, 포함, 1~100일) → `{items:[회차]}`, 시작 순. 반복 일정은 회차마다 한 줄(같은 `scheduleId`, `recurring:true`). 기간에 걸친 일정도 나온다
  - `memberIds`·`roles`(여러 개, 반복 파라미터): 그 구성원이 참여하는 일정만. 둘 다 주면 합집합. 역할은 이 그룹에서의 `familyRole`
- 겹침 `GET /schedules/conflicts?startsAt=&endsAt=&memberIds=&excludeId=` → `{items:[{scheduleId, title, allDay, startsAt, endsAt, userIds}]}`. `userIds`는 요청한 구성원 중 그 일정 참여자. 딱 붙은 일정은 겹침 아님. 쿼리의 `+`는 `%2B`로
- 리마인드: 서버가 1분마다 보낸다(푸시 규칙의 `SCHEDULE_REMINDER`). 알림 시각이 등록·수정보다 앞이면 보내지 않는다 (20:30에 내일 일정을 넣으면 전날 20:00 알림은 없음). 서버가 멈췄다 켜지면 지난 5분까지만 보낸다
- 음력(`calendar_type`·`lunar_month_day`)은 컬럼만
- 장소 연결(S14): 요청 `placeId`(이 그룹에 공유한 장소, 아니면 400). 수정은 값 전체라 `placeId`를 빼면 연결이 비워진다. 응답 `place: {id, name, address, category}`, 장소가 지워지면 null

**공휴일** `GET /holidays?year=` (로그인만, 2000~2100) → `{items:[{date, name, substitute}]}` 날짜 순. 2026~2027만 있다 (없는 해는 빈 목록)

**할 일** (`/groups/{groupId}/todos`, `/todos/{id}`)
- 등록 `POST {title, assigneeId?, dueDate?}` → 201. `kind`는 TASK만 (SHOPPING은 v1.1, 400). 담당자는 활성 구성원
- 목록 `GET ?assigneeId=&done=&cursor=&size=`(기본 50, 최대 100) → `{items, nextCursor}`. 안 한 일(기한 이른 순, 기한 없음은 뒤) 다음에 완료한 일(최근 완료 순). `done=true|false`로 한쪽만
- 수정 `PATCH {version, title?, assigneeId?, dueDate?, done?, announce?}`: 보낸 키만 바뀐다. `assigneeId`·`dueDate`는 `null`을 보내면 지운다
  - 제목·담당자·기한은 등록한 사람 또는 ADMIN 이상(403), `done`은 구성원 누구나. version이 다르면 409
  - `done:true, announce:true`로 **새로** 완료하면 채팅방에 시스템 메시지 "○○님이 '우유 사 오기' 할 일을 완료했어요." (payload `{kind:"TODO_DONE", userId, todoId}`) → 다른 가족에게 채팅 푸시. ○○은 그룹 호칭, 없으면 닉네임 (입장·퇴장 메시지도 같다)
- 삭제 → 204 (등록한 사람 또는 ADMIN 이상). 다른 그룹 id → 404

### 장소 규칙 (S14, 앱 S15 참고)

**검색** `GET /places/search?query=&x=&y=&page=&groupId=` (카카오 로컬 프록시, 키는 서버에만)
- 한 쪽 15개, `page` 1~45 → `{items, hasMore}`. `x`(경도)·`y`(위도)는 함께 보내고, 보내면 가까운 순 + `distanceM`
- 항목: `kakaoPlaceId, name, categoryDetail, suggestedCategory, address(도로명 우선), region1~3, lat, lng, phone, placeUrl`
- `groupId`를 주면 그 그룹에 이미 공유된 장소에 `sharedPlaceId` (구성원이 아니면 403)
- 사용자당 분당 30회 (429 `RATE_LIMITED` + Retry-After)

**공유** `POST /groups/{groupId}/places {kakaoPlaceId, category, oneLineReview?, rating?, shareToChat?}` → 201
- **검색 후 1시간 안에** 그 검색 결과의 `kakaoPlaceId`로 공유한다. 지나면 400 "다시 검색"
- `category`: `RESTAURANT`(맛집)·`CAFE`·`TRAVEL`(여행지)·`LIFE`(병원·생활)·`ETC`. 검색 추천값은 `application.yml`의 `famlog.place.category-map`(카카오 분류 코드 → 카테고리, 없는 코드·`CT1` 문화시설은 `ETC`). `rating` 1~5, `oneLineReview` 최대 100자
- 같은 그룹에 같은 장소가 있으면 409 `PLACE_ALREADY_SHARED`
- `shareToChat`(기본 true)이면 채팅방에 `PLACE` 메시지: `body` = 장소 이름, `payload` = `{placeId, name, category, address, region1, region2, rating}` (공유한 순간 값). 푸시 본문 "장소를 공유했어요: ○○"

**목록** `GET /groups/{groupId}/places` → `{items, nextCursor}` 최신 공유 순 (기본 50, 최대 100)
- `region1`(시·도: 서울·경기·…), `region2`(시·군·구, region1 필요), `sharedBy`·`category`(여러 개), 지도 영역 `swLat·swLng·neLat·neLng`(모두)
- 항목에 `marks: {visited:[userId], want:[userId]}`
- 지역별 개수 `GET .../places/regions?sharedBy=&category=` → `{total, items:[{region1, count, children:[{region2, count}]}]}` 가나다 순, 시·군·구 없음(세종)은 `region2:null`로 맨 뒤

**내 주변** `GET .../places/nearby?lat=&lng=&radiusKm=`(1~20, 기본 3) → `{items:[{place, distanceM}]}` 가까운 순 최대 50
- 좌표는 저장·로그하지 않고, 쓸 때마다 `location_access_logs`(NEARBY). 기능이 꺼져 있으면 404 → 앱은 버튼을 숨긴다

**위치 동의** (사양서 8.3, 앱 S15-2)
- `GET /users/me/consents` → `{items:[{type, agreed, docVersion, agreedAt, currentVersion, reconsentRequired}]}` (TERMS·PRIVACY·AI_ANALYSIS·OVERSEAS_TRANSFER·LOCATION 순)
- `PUT /users/me/consents/LOCATION` 동의 `{agreed:true, docVersion:"1.0"}`(현재 버전 `famlog.consent.location-version`과 다르면 400), 철회 `{agreed:false}` → 내가 올린 사진의 위치(`media_assets.location`)도 지운다. 여러 번 해도 같다
- 다른 종류는 400 (필수 동의는 탈퇴로만, AI 분석·국외 이전은 S16)
- 이력은 지우지 않는다: 철회는 `withdrawn_at`, 새 버전 동의는 새 행
- 내 주변은 동의가 없으면 403 `LOCATION_CONSENT_REQUIRED` (위치 이용 기록도 안 남김). 검색 x·y에 현재 위치는 동의한 경우에만 보낸다

**하나·수정·삭제** `/places/{id}`: 다른 그룹 장소 → 404
- `PATCH {category?, oneLineReview?, rating?}`: 보낸 키만, `oneLineReview`·`rating`은 null이면 지운다. 공유한 사람 또는 ADMIN 이상(403)
- `DELETE` → 204. 일정·별칭의 연결은 비워지고 표시도 지워진다. 채팅 카드는 남는다 (눌러서 404면 "삭제된 장소")
- 다녀옴·가고 싶어요 `PUT|DELETE /places/{id}/marks/{VISITED|WANT}` → `{visited, want}` (여러 번 해도 같다)

**별칭 장소** `GET|POST /groups/{groupId}/place-aliases`, `DELETE .../place-aliases/{aliasId}`
- `POST {alias(1~30자), placeId?, addressText?(200자)}` → 201. 같은 별칭 409 `ALIAS_DUPLICATED`, 다른 그룹 장소 400
- 응답 `{id, alias, place:{id,name,address,category}|null, addressText, createdBy, createdAt}`, 목록은 가나다 순
- 삭제는 만든 사람 또는 ADMIN 이상, 다른 그룹 별칭 id → 404

### AI 분석 규칙 (S16, 앱 S19·S20 참고)

**그룹 설정** `GET|PATCH /groups/{groupId}/settings` — `{aiAnalysisEnabled}` → `{aiAnalysisEnabled, aiEnabledAt}`
- 기본 꺼짐. 조회는 구성원, 변경은 ADMIN 이상(MEMBER 403 `GROUP_ROLE_REQUIRED`), 값 없으면 400
- 켜면 그 뒤 메시지부터 분석한다 (켜기 전 대화는 보내지 않는다)

**개인 분석 제외** `PATCH /groups/{groupId}/members/me/preferences` — `{notifMuted?, aiOptOut?}` 보낸 값만 (둘 다 없으면 400). 응답 `{notifMuted, aiOptOut}`
- 본인만 바꾼다 (관리자도 못 바꾼다, 사양서 8.8). 제외했던 동안의 메시지는 다시 켜도 보내지 않는다

**트리거** (서버 안, API 계약 아님)
- 분석 대상: 그룹 AI 켜짐 + 사람이 보낸 TEXT + 보낸 사람이 분석 제외 안 함. 아니면 Redis·스트림 어디에도 넣지 않는다 (AR-10)
- 규칙 필터에 걸리면 마지막 메시지 30초 뒤, 안 걸리면 10분 뒤, 30개 쌓이면 바로. 5초마다 꺼내 `famlog:ai.extract`로 보낸다
- 요청: 구간 메시지(최대 50) + 앞 맥락 최대 20 + 구성원 `{ref:"M1", familyRole, familyTitle}` + 발송 시각(+09:00), 본문은 전화·계좌·카드·주민번호·이메일 마스킹
- `ai_jobs`에 요청 기록(구간, 임시 ID 대응, 상태, 모델·프롬프트 버전, 토큰, 지연). 원문 컬럼 없음
- 워커: 소비 그룹 `ai-workers`, 재시도 3회 후 DLQ(`famlog:ai.extract:dlq`), LLM 출력은 pydantic 스키마 + 근거 메시지가 구간 안인지 검증, 실패는 `INVALID_OUTPUT`
- 결과(`famlog:ai.extract-results`) → `ai_jobs` DONE/FAILED, 후보는 아래 "후보 생성 규칙"
- LLM (S18-2): 워커 `LLM_PROVIDER=anthropic`, `claude-haiku-5-5`, 생각 끔, 시스템 지시문(`worker/prompts/extract_v1.md`) 5분 캐시, 구조화 출력(JSON 스키마) + pydantic 검증, 타임아웃 30초. 거절은 `LLM_REFUSED`(재시도 안 함). 프롬프트·응답 원문은 남기지 않는다
- 상한 (S18-2, `famlog.ai.limits`): 그룹별 하루(KST) 요청 100개를 넘으면 그날 남은 메시지는 분석 안 함. 월(UTC) 비용 20달러에 닿으면 새 요청을 만들지 않음, 80%에 그달 한 번 경고 로그. 비용 = `ai_jobs` 토큰(입력·출력·캐시 읽기·쓰기) × `famlog.ai.pricing`

### 정규화 규칙 (S17, 서버 내부 · S18에서 후보 생성에 연결)

`candidate/normalize/`의 순수 함수. LLM은 원문 표현(dateText·timeText·사람 표현)만 넘기고 서버가 계산한다.
- `DateNormalizer.scheduleDate / deadline`: 오늘·내일·모레·글피, N일/N주 뒤, 요일(이번·다음·다다음 주), N일, 이번·다음 달 N일, M월 D일·M/D, 주말, 기한(이번 주에·주말까지·이번 달 안에·다음 주까지) → 날짜 + 추정 여부 / 지난 일 / 해석 못 함
- `TimeNormalizer`: 숫자·한글 숫자 시각, 반·분, 오전·오후·아침·저녁·밤·새벽·점심 단서(시간 표현 → 같은 메시지 순), 단서 없으면 1~6시 오후·7~11시 오전·12시 낮(추정)
- `DateTimeNormalizer.schedule / anniversary`: 날짜 + 시각 → 날짜·시작 시각(null = 종일)·추정 키(`date`, `startTime.ampm`), 기념일은 월·일
- `MemberMatcher`: 호칭 → 역할(같은 역할 여러 명이면 후보 묶음) → "나"(보낸 사람), "우리·다 같이·모두·가자"(가족 전체 추정), 조사 제거, 못 찾은 표현은 그대로
- 지원 안 함(날짜 비움): "열흘 뒤", 한글 숫자 날짜("십오일"), "월말쯤", "다음 주말까지"(이번 주 일요일로 계산됨)

### 후보 생성 규칙 (S18, 앱 S19 참고)

워커 결과의 후보를 서버가 검증·정규화해 `registration_candidates`(PENDING)에만 저장한다. 실제 일정·할 일·장소는 만들지 않는다. 조회·승인 API와 WebSocket 이벤트는 S19.

**LLM 출력** (워커 `schemas.py`, 원문 표현만. 다른 필드는 `INVALID_OUTPUT`)
`{type, fields, confidence, evidence:[{messageId, spans}]}`
- SCHEDULE `{title, dateText, startTimeText, endTimeText, locationText, attendeeTexts[]}` · TODO `{title, dueText, assigneeTexts[]}` · ANNIVERSARY `{title, dateText, personText}` · PLACE `{name, regionHint, opinion}`

**저장되는 `fields`** (S19 응답의 바탕)
- SCHEDULE `{title, date, startTime, endTime, allDay, location:{text, aliasId, placeId}|null, attendeeIds, attendeeChoices, unmatchedAttendees}`
- TODO `{title, dueDate, assigneeIds, assigneeChoices, unmatchedAssignees}`
- ANNIVERSARY `{title, month, day, personText}` (음력·미리 알림은 승인 폼에서)
- PLACE `{name, regionHint, kakaoPlaceId, options:[{kakaoPlaceId, name, category, categoryDetail, address, region1~3, lat, lng, phone, placeUrl}] (≤3), opinion, recommendedBy}`
- 날짜 `yyyy-MM-dd`, 시각 `HH:mm`(Asia/Seoul). 가족 전체는 `attendeeIds:["ALL"]`(추정). `*Choices`는 같은 역할이 여러 명이라 고를 묶음, `unmatched*`는 못 찾은 사람 표현(글자로 보여 준다)
- `inferred_fields`: `date`, `startTime.ampm`, `attendeeIds`, `assigneeIds`, `dueDate`. `missing_fields`: `title`, `date`, `name`(채워야 등록)

**처리**
- 기준: 날짜 표현이 들어 있는 근거 메시지(없으면 첫 근거)의 발송 시각·보낸 사람·본문(오전·오후 단서)
- 근거가 이 요청의 분석 구간(같은 방, `(from_seq, to_seq]`)이 아니거나 지운 메시지면 그 후보를 버린다
- 신뢰도 0.5 미만 버림. 0.75 이상 채팅방 카드, 0.5~0.75 제안함 — 구간은 저장하지 않고 `confidence`로 S19가 나눈다
- 지난 일정·지난 기한·지난 일 표현의 기념일은 버린다. 날짜를 해석 못 한 일정은 `missing_fields`에 `date`
- 장소: PLACE는 `regionHint + name`으로 카카오 검색 상위 3개, 1개면 `kakaoPlaceId` 자동 선택, 실패·0개면 이름만. 일정 장소는 별칭(띄어쓰기 무시 일치)만
- `dedupKey`: `유형|정규화 제목|날짜`(기념일 `MM-DD`, 장소 `PLACE|kakao:ID` 또는 `PLACE|name:이름`). 같은 그룹 저장은 advisory lock으로 하나씩
- 만료 `expires_at`: 일정 시각(종일은 다음 날 0시), 나머지·날짜 없는 일정은 7일 뒤

### 후보 API 규칙 (S19, 앱 S19 참고)

**목록** `GET /groups/{groupId}/candidates?status=&type=&fromSeq=&toSeq=&cursor=&size=` — 최신 순, `{items, nextCursor}`
- `status` 기본 PENDING, 여러 개 가능 (`status=PENDING,APPROVED`). 만료된 PENDING 은 빠진다
- `fromSeq`·`toSeq`: 카드를 붙일 메시지(`anchorSeq`) 범위. 채팅 화면은 보이는 메시지 범위로 `PENDING,APPROVED` 를 받아 카드·Resolved Chip 을 그린다
- 항목 `{id, groupId, type, status, fields, inferred, missingFields, confidence, surface, anchorMessageId, anchorSeq, version, decidedBy, decidedAt, dismissReason, entityType, entityId, linkedExisting, expiresAt, createdAt}`
  - `surface`: `CARD`(0.75 이상, 채팅방 카드) / `INBOX`(제안함에만). 제안함(SCR-14)은 둘 다 보여 준다
  - `fields`·`inferred`·`missingFields` 형태는 위 "후보 생성 규칙"
**상세** `GET /candidates/{id}` → `{candidate, evidence:[{messageId, seq, senderId, sentAt, spans, text}]}` (지운 메시지는 `text` null)

**승인** `POST /candidates/{id}/approve` `{version, schedule? | todo? | place?}` → `{candidate}`
- 값 없이 보내면 후보 값 그대로. 못 바꾸면 422 `CANDIDATE_MISSING_FIELDS`, `fieldErrors[].field` = `title`·`date`·`attendeeIds`·`assigneeIds`·`kakaoPlaceId`·`name` → 수정 폼(SCR-13)
- 수정 폼 값: 일정·기념일은 일정 등록 요청(`ScheduleRequest`), 할 일은 할 일 등록 요청, 장소는 `{kakaoPlaceId(후보 options 중 하나나 방금 검색한 것), category, oneLineReview}`. 후보 값과 다르면 EDITED 로 기록
- 409 `CANDIDATE_ALREADY_DECIDED`(message 예: "엄마님이 먼저 등록했어요."), `CANDIDATE_EXPIRED`, `VERSION_CONFLICT`. 같은 사람이 다시 보내면 200 같은 결과
- 성공하면 채팅에 시스템 메시지 "○○님이 '…' 일정을 캘린더에 등록했어요." (payload `{kind:"CANDIDATE_APPROVED", userId, candidateId, entityType, entityId}`) → 다른 가족에게 채팅 푸시
- `entityType` SCHEDULE(기념일 포함)·TODO·PLACE, `linkedExisting` true 면 이미 공유된 장소에 연결

**무시** `POST /candidates/{id}/dismiss` `{version, reason?: ALREADY_REGISTERED | JOKE | WRONG}`. 무시한 내용은 다시 제안하지 않는다
**되돌리기** `POST /candidates/{id}/undo` — 승인한 사람·ADMIN 이상, 5분 안 (아니면 403 `CANDIDATE_NOT_UNDOABLE`). 항목·원문 연결을 지우고 승인 시스템 메시지는 `message.deleted` (앱은 지운 시스템 메시지를 숨긴다)

**WebSocket** `/sub/groups/{groupId}`
- `candidate.created`: 목록 항목과 같은 형태 (새 후보만. 근거만 늘어난 경우는 보내지 않는다)
- `candidate.resolved`: `{groupId, candidateId, status, decidedBy, decidedByName, entityType, entityId, linkedExisting}` → "엄마가 등록했어요" 로 바꾼다

**원문 보기 (ARC-06)**: 일정(상세·기간 조회 회차)·할 일·장소 응답의 `source {messageId, seq, candidateId}` (채팅에서 등록했으면, 아니면 null). `candidateId`는 그 메시지를 근거로 항목을 만든(장소는 기존 장소에 연결한) 승인 후보, 여럿이면 먼저 승인한 것. 앱은 `GET /candidates/{candidateId}`의 evidence spans로 추출 부분을 강조하고, null이면 메시지 전체를 강조한다 (S19-1)

### 다음 단계로 넘긴 것

- S20: 만료된 업로드(`PENDING_UPLOAD`)·삭제된 메시지의 미디어 파일 정리 배치, 워커 대기 메시지(PEL) 회수
- 앨범 단계: pHash·흐림 점수, `GET /groups/{id}/media?month=`
- 출시 전: 위치기반서비스 신고 확인 후 `photo-location-enabled` 켜기, "사진 위치 사용" 동의 API(설정 화면)
- S20: 푸시 발송 실패 재시도 (지금은 한 번 보내고 실패는 로그만). 계정 삭제(ACC-01)는 전 세션 폐기를 부르면 토큰도 함께 지워진다
- S16~: AI 후보 푸시 (`channel=AI`, 같은 발송기)
- S20: 비용 80%·상한 도달을 로그 대신 관리자 알림으로. 사용자: 처리방침용 Anthropic 데이터 처리 조건(학습 사용·보관 기간) 확인(S22 전)
- 프롬프트·모델을 바꾸면 `worker/evals/README.md` 순서로 평가하고 기록 표에 남긴다 (실제 호출은 사용자 확인 후)
- 다음(S20~): 기존 항목 수정·완료 후보(`target_ref`, "11시 말고 12시로"·"샀어"), 근거만 늘어난 후보의 이벤트, 후보 [신고](MOD-01), 참석자·담당자 개별 푸시, 기념일 1주 전 알림·음력(v1.1), 만료 후보 정리 배치
- S20: AI 분석 처음 켤 때 안내·국외 이전 고지·`AI_ANALYSIS`/`OVERSEAS_TRANSFER` 동의, 관리자가 구성원의 분석 제외 여부를 보는 화면(8.8), 스트림에 못 보내 `QUEUED`로 남은 `ai_jobs` 정리
- S20: `feature_flags` 테이블·관리자 화면 (지금은 `nearby-enabled`·`photo-location-enabled` 설정값), 오래된 `location_access_logs` 보관 기간
- 출시 전: 위치기반서비스 신고 확인 후 운영 `nearby-enabled` 값 결정, 카카오 로컬 API 이용 약관(결과 저장·캐시 범위) 확인
- S20: 공휴일 자동 갱신(공공데이터포털 특일 정보)·관리자 수정, 지난 `schedule_reminder_sends` 정리, 할 일 완료 알림을 그룹 설정으로 둘지
- v1.1: 반복 일정 "이번 회차만" 수정·삭제, 음력 반복(DIF-04), 장보기 목록(TODO-02)
- 매년: 다음 해 공휴일 마이그레이션 (2028년분은 2027년 하반기에, 우주항공청 월력요항 기준)
- S20: 삭제 예정 그룹·탈퇴 계정 파기 배치, 삭제 유예 계정 복구, 만료된 `refresh_tokens` 정리
- 모바일 요청 검토: `GET /users/me` (지금은 앱이 로그인 응답의 user를 저장해 씀)
- 자리 값: 캘린더 팔레트 뒤 8색(디자인 확정 시), 초대 링크 도메인 `famlog.app`(App Links 도메인 확정 시)

### 문서 레포(사양서)에 반영할 것

| 사양서 | 내용 |
|---|---|
| 10.2 | `refresh_tokens` 추가, `user_sessions.refresh_token_hash` 삭제 / `chat_rooms`·`audit_logs` 생성 시점(S5) |
| 11.2 | 에러 코드 추가: `INTERNAL_ERROR`(500), `METHOD_NOT_ALLOWED`(405), `USER_NOT_REGISTERED`(404), `AGE_RESTRICTED`(403), `GROUP_LIMIT_EXCEEDED`(409), `OWNER_TRANSFER_REQUIRED`(409) |
| 11.3 | `POST /auth/{provider}/signup` 추가, `/users/me/age-verification` v1.0 미사용, 나가기는 `POST /groups/{id}/leave` |
| 13.2 | Spring Boot 4.1, 로컬 S3는 SeaweedFS |
| 10.2 (S8) | `chat_rooms.last_seq`, `group_members.join_seq` 추가, `message_reactions`(message_id, user_id, emoji — PK(message_id, user_id)) |
| 11.2 (S8) | 에러 코드 `MESSAGE_NOT_DELETABLE`(403) |
| 11.3 (S8) | `DELETE /messages/{id}/reactions/me` 추가, `GET /groups/me`에 `lastMessage`·`unreadCount`, 메시지 목록 응답 `{items, hasMore}` |
| 11.4 (S8) | 연결 주소 `/ws`, 이벤트 형식 `{type, data}`, 이벤트 `reaction.updated`·`error`·`session.revoked`, ERROR 프레임 규칙 |
| 10.2 (S9) | `media_assets`: `scan_status` → `status`(PENDING_UPLOAD·UPLOADED·READY·FAILED·REJECTED), `reject_reason`·`upload_expires_at` 추가 / `location_access_logs.target_id` 추가 |
| 11.3 (S9) | `GET /media/{id}/content?variant=` 추가, 메시지 전송에 `type`·`mediaIds`, 메시지 응답에 `media` |
| 11.4 (S9) | 이벤트 `media.updated` |
| 13.2 (S9) | 워커 FastAPI·OpenCV·imagehash는 앨범 단계로 미룸, 워커 이미지 python:3.12-slim + FFmpeg |
| 10.2 (S11) | `user_notification_settings`(user_id PK, preview_hidden, quiet_enabled, quiet_start, quiet_end) 추가 |
| 11.3 (S11) | `GET·PATCH /users/me/notification-settings` 추가, `PUT /devices/token` 요청 `{token, appVersion}` |
| 4.2·13 (S11) | 푸시는 data 메시지만, 키 목록(위 "푸시 규칙"), FCM HTTP v1 직접 호출 |
| 10.2 (S12) | `schedules.reminders`(text[], 기본 `{PREV_DAY_20,HOUR_1}`) 추가, `schedule_reminder_sends`(schedule_id, occurrence_start, kind — PK) 추가, `holidays.id` + UNIQUE(date, name) |
| 11.3 (S12) | `GET /groups/{id}/schedules/conflicts`(SCH-05), `GET /todos/{id}`, 일정 기간 조회 응답 `{items}`(회차, 페이징 없음), 할 일 수정의 `announce` |
| 4.3 (S12) | 반복은 매주·매월·매년 + 간격·종료(날짜/횟수), 수정은 시리즈 전체 / 리마인드 선택지 5개 / 할 일 완료는 누구나 |
| 10.2 (S14) | `places`: `lat`·`lng`·`phone`·`place_url` 추가(`geom`은 생성 컬럼), UNIQUE(group_id, kakao_place_id) / `place_marks`(place_id, user_id, kind — PK) / `place_aliases.created_by`·UNIQUE(group_id, alias) / `location_access_logs.purpose`에 `PLACE_SEARCH` / `schedules.place_id` FK |
| 11.2 (S14) | 에러 코드 `PLACE_ALREADY_SHARED`(409), `ALIAS_DUPLICATED`(409) |
| 11.3 (S14) | 검색 `page`·`groupId`, 목록 지도 영역(`swLat` 등)·커서, `GET /places/{id}`, `PUT·DELETE /places/{id}/marks/{kind}`(PLC-05), 별칭 삭제 `/place-aliases/{aliasId}`, 일정 요청 `placeId`·응답 `place` |
| 8.6 (S14) | 장소 검색에 현재 위치를 보내면 `PLACE_SEARCH`로 기록 |
| 11.2·11.3 (위치 동의) | 에러 코드 `LOCATION_CONSENT_REQUIRED`(403), `PUT /users/me/consents/{type}` 요청 `{agreed, docVersion}`·지금은 LOCATION만, 내 주변은 위치 동의 필요 |
| 10.2 (S16) | `group_settings`(group_id PK, ai_analysis_enabled 기본 false, ai_enabled_by, ai_enabled_at), `group_members.ai_opt_out_changed_at`, `chat_rooms.ai_analyzed_seq`, `ai_jobs` 컬럼(위 "AI 분석 규칙") |
| 11.3 (S16) | `GET·PATCH /groups/{id}/settings` 요청·응답 `aiAnalysisEnabled`, preferences에 `aiOptOut`(보낸 값만) |
| 8.7 (S16) | 켜기 전 대화·제외했던 동안의 메시지는 보내지 않음, 스트림 항목은 처리 후 삭제 |

## 7. 오픈 이슈 (결정 필요)

사양서 19장이 기준이다. 백엔드에 직접 영향이 있는 것:

- [ ] LLM 제공자·모델, 월 비용 상한 (W8 전)
- [x] 로그인 방식: 카카오·Google 소셜만으로 구현 (S4)
- [x] 만 14세 미만: v1.0은 14세 이상만, 가입 단계에서 확인 (S4)
- [ ] 초대·App Links 도메인
- [ ] YouTube API 감사 신청 시점, BGM 음원 확보 방법
- [ ] KIPRIS 상표 확인 (FamLog / 팸로그)

## 8. 진행 현황 (Claude Code가 갱신)

| 날짜 | 기능 ID | 내용 | 커밋 |
|---|---|---|---|
| 2026-11-12 | - | 통합 사양서 v2.0 사본(`docs/spec/`), CLAUDE.md 사양서 v2.0 기준으로 갱신, `/feature` 명령 추가 | docs(spec) |
| 2026-11-13 | S1 | 로컬 인프라 docker-compose (PostgreSQL+PostGIS+pgvector, Redis, SeaweedFS) | chore(S1) `1605151` |
| 2026-11-13 | S2 | Spring Boot 4.1 골격, 공통 에러·traceId·로그 마스킹·+09:00 시간, Flyway V1, Testcontainers, CI | chore(S2) `094a4ee` |
| 2026-11-13 | S3 | JWT ES256·kid, Refresh 교체·재사용 감지(V2), 즉시 세션 폐기, 그룹 경로 인가, 요청 제한 | feat(S3) `f08457c` |
| 2026-11-13 | AUTH-01~03 | 카카오·Google 로그인·가입, 만 14세 확인, 기기 목록·원격 로그아웃 | feat(AUTH-01~03) `b6de551` |
| 2026-11-14 | GRP-01~05 | 그룹 생성·초대(잠금)·전환·가족 역할·권한·위임·내보내기·나가기, 감사 로그(V3) | feat(GRP-01~05) `d7660b5` |
| 2026-11-17 | - | API 계약 `docs/api/openapi.yaml` 생성(`updateOpenApi`)·CI 확인, 문서 레포 내보내기 스크립트, HANDOFF 갱신 | chore(api) `3310f85` |
| 2026-11-17 | CHAT-01~04·06 | STOMP 실시간 채팅(JWT CONNECT, 구독·전송마다 구성원 확인), 방별 seq·clientMessageId 멱등(V4), Redis Pub/Sub 중계, 내역 페이징, 읽음·안 읽은 수, 답장·공감·5분 삭제, 입장·퇴장 시스템 메시지, 나감·로그아웃 시 연결 끊기 | feat(CHAT) `3426af7` |
| 2026-11-17 | CHAT-02 | Redis 중계 수신을 스레드 하나로 처리해 이벤트 순서 보장 (기본 컨테이너는 메시지마다 새 스레드 → main CI #12에서 read.updated가 message.created보다 먼저 도착) | fix(CHAT-02) `3bcbed4` |
| 2026-11-17 | CHAT-05 | 미디어 업로드(Presigned PUT·매직 바이트 검사, V5), Redis Streams 처리 요청·결과, 사진 묶음·동영상 메시지, 302 내려받기, Python 워커(GPS 제거·재인코딩·썸네일 3단계·촬영 시각·재시도 3회 후 DLQ), compose·CI | feat(CHAT-05) |
| 2026-11-18 | CHAT-05 | 워커: AMR-NB 등 컨테이너에 못 넣는 소리는 AAC로만 변환(영상은 복사 유지), 그래도 실패하면 `UNSUPPORTED_CODEC` | fix(CHAT-05) |
| 2026-11-18 | NOTI-01·SET-02 | FCM 푸시(data 메시지, HTTP v1): 기기 토큰 등록·삭제·로그아웃 시 삭제·무효 토큰 정리, 새 메시지·입장/퇴장·내보내짐·새 기기 로그인 푸시, 그룹 알림 끄기·미리보기 숨기기·방해 금지(V6) | feat(S11) |
| 2026-11-18 | SCH-01~05·TODO-01 | 일정 등록·수정·삭제(낙관적 잠금), 반복 RRULE 펼치기, 구성원·역할 필터, 겹침 조회, 리마인드 스케줄러·푸시, 공휴일 2026~2027, 할 일·커서 목록·완료 채팅 알림(V7) | feat(S12) |
| 2026-11-18 | TODO-01 | 시스템 메시지 이름을 그룹 호칭 우선으로 (입장·퇴장·할 일 완료) | fix(TODO-01) |
| 2026-11-19 | PLC-01~06 | 카카오 로컬 검색 프록시(결과 캐시·요청 제한), 장소 공유·채팅 장소 카드·푸시, 지역·공유자·카테고리·지도 영역 목록, 지역별 개수, 내 주변(PostGIS, 좌표 비저장·위치 이용 기록·플래그), 다녀옴·가고 싶어요, 별칭 장소, 일정 장소 연결(V8) | feat(S14) |
| 2026-11-19 | PLC-04 | 위치기반서비스 동의 API(`GET·PUT /users/me/consents`, LOCATION만, 버전 확인·이력 보존·철회 시 사진 위치 삭제), 내 주변은 동의 필요(403) | feat(PLC-04) |
| 2026-11-19 | PLC-01 | 추천 카테고리: 카카오 문화시설(`CT1`, 영화관·공연장 포함)을 여행지 대신 기타로 | fix(PLC-01) |
| 2026-11-19 | ARC-01·SET-01 | AI 인식 파이프라인 골격: 규칙 필터·30초 멈춤·보조 분석(30개·10분) 트리거, 마스킹·가명(M1…), Redis Streams 요청·결과, 워커 소비·재시도·DLQ·가짜 LLM·출력 검증, 그룹 AI 분석 설정·개인 분석 제외, `ai_jobs` 기록(V9) | feat(S16) |
| 2026-11-19 | ARC-02·04 | 날짜·시각·구성원 정규화(상대 날짜·기간·요일·월말/연말 경계, 오전·오후 추정·단서, 역할·호칭·가족 전체 매칭), 단위 테스트 89개(AR-9 날짜 46) | feat(S17) |
